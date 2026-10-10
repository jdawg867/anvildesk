package io.github.jdawg867.anvildesk

import android.app.AlertDialog
import android.os.StatFs
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.jdawg867.anvildesk.runtime.MutableRuntimeStore
import io.github.jdawg867.anvildesk.runtime.RootfsInstallStore
import io.github.jdawg867.anvildesk.runtime.RootfsManifest
import io.github.jdawg867.anvildesk.runtime.RootlessRuntimeResult
import io.github.jdawg867.anvildesk.runtime.XfceDesktopLauncher
import io.github.jdawg867.anvildesk.runtime.XfceDesktopPackageSet
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.concurrent.thread

/**
 * One-shot Samsung acceptance experiment. The production mutable runtime is
 * never passed to this launcher, and the sandbox is retained for diagnostics.
 */
internal fun addXfceIsolatedValidationSection(
    activity: MainActivity,
    content: LinearLayout,
    density: Float,
    manifest: RootfsManifest,
    rootfsReady: Boolean,
    rootfsStore: RootfsInstallStore,
) {
    val sandbox = File(activity.filesDir, "m7-isolated-validation-v1")
    val sandboxStore = MutableRuntimeStore(sandbox)
    val report = File(sandbox, "validation-report.txt")

    content.addView(TextView(activity).apply {
        text = "M7 isolated clean-install validation"
        textSize = 20f
        setPadding(0, (24 * density).toInt(), 0, (8 * density).toInt())
    })
    val status = TextView(activity).apply {
        text = "One-shot fresh mutable clone from the verified Ubuntu Base; requires network and at least 2 GiB free app storage. Does not alter the production XFCE runtime."
        textSize = 14f
    }
    content.addView(status)

    val button = Button(activity).apply {
        text = "Run isolated XFCE install test"
        isEnabled = rootfsReady
        setOnClickListener {
            isEnabled = false
            try {
                // Fail closed: do not reuse an earlier (possibly incomplete) canary.
                require(!Files.exists(sandbox.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    "Isolated validation already exists; preserve it for inspection rather than retrying."
                }
                val available = StatFs(activity.filesDir.absolutePath).availableBytes
                require(available >= 2L * 1024 * 1024 * 1024) {
                    "At least 2 GiB of free app storage is required for an isolated XFCE installation."
                }
                val verified = rootfsStore.currentRecord(manifest.id)
                    ?: error("Verified Ubuntu Base install is missing")
                require(verified.sha256 == manifest.sha256 && verified.version == manifest.version &&
                    verified.architecture == manifest.architecture) {
                    "Verified Ubuntu Base provenance mismatch"
                }

                AlertDialog.Builder(activity)
                    .setTitle("Run isolated clean XFCE installation?")
                    .setMessage(
                        "This downloads and installs packages into a NEW separate app-private Ubuntu clone. " +
                            "It can use substantial storage and network traffic. Existing Ubuntu/XFCE files stay untouched. " +
                            "The test directory and result log will be retained, even if installation fails."
                    )
                    .setNegativeButton("Cancel") { _, _ -> isEnabled = true }
                    .setOnCancelListener { isEnabled = true }
                    .setPositiveButton("Run isolated test") { _, _ ->
                        thread(name = "anvildesk-m7-isolated-validation") {
                            var stage = "prepare"
                            val evidence = StringBuilder()
                            fun record(line: String) {
                                evidence.appendLine(line)
                                activity.runOnUiThread { status.text = line }
                            }
                            fun requireSuccessful(step: String, result: RootlessRuntimeResult) {
                                evidence.appendLine("$step exitCode=${result.exitCode} timedOut=${result.timedOut}")
                                evidence.appendLine("$step stdout:\n${result.stdout.takeLast(3000)}")
                                evidence.appendLine("$step stderr:\n${result.stderr.takeLast(3000)}")
                                check(!result.timedOut && result.exitCode == 0) { "$step failed" }
                            }
                            try {
                                require(sandbox.mkdir()) { "Could not create isolated validation directory" }
                                val isolated = XfceDesktopLauncher(
                                    store = rootfsStore,
                                    mutableRuntimeStore = sandboxStore,
                                    nativeLibraryDirectory = File(activity.applicationInfo.nativeLibraryDir),
                                    appCacheDirectory = File(activity.cacheDir, "m7-isolated-validation-v1").apply { mkdirs() },
                                    sessionDataDirectory = File(sandbox, "sessions"),
                                )
                                // No Android shell input; same allowlisted planner as production.
                                stage = "clone verified Ubuntu Base"
                                record("Cloning fresh Ubuntu Base into isolated validation storage…")
                                // binariesPresent ensures the new clone exists; it must initially have no XFCE.
                                check(!isolated.binariesPresent(manifest)) {
                                    "Fresh Ubuntu Base unexpectedly contains the complete XFCE binary set"
                                }
                                stage = "Android DNS"
                                val dns = xfceValidationDnsServers(activity)
                                stage = "apt-get update"
                                record("Isolated apt-get update…")
                                requireSuccessful(stage, isolated.refreshIndexes(manifest, dns))
                                stage = "apt-get install"
                                record("Isolated fixed XFCE install…")
                                requireSuccessful(stage, isolated.install(manifest, dns))
                                stage = "dpkg-query verification"
                                record("Verifying installed package states…")
                                val verification = isolated.verify(manifest)
                                requireSuccessful(stage, verification)
                                val installed = verification.stdout.lineSequence().filter { it.isNotBlank() }.toList()
                                check(installed.size == XfceDesktopPackageSet.PACKAGES.size &&
                                    installed.all { it.trimEnd().endsWith("\tii ") }) {
                                    "dpkg-query did not confirm all eight packages installed"
                                }
                                stage = "managed session preview"
                                isolated.sessionPreview(manifest)
                                stage = "post-install dpkg inspection"
                                val assessment = isolated.assessRecovery(manifest)
                                check(assessment.blockers.isEmpty() && assessment.packagesToConfigure.isEmpty()) {
                                    "Incomplete known package state after installation"
                                }
                                record("PASS: isolated clean install, all eight packages, binaries, and session contract.")
                                evidence.appendLine("RESULT=PASS")
                            } catch (error: Throwable) {
                                record("FAIL during $stage: ${error.message ?: error::class.java.simpleName}")
                                evidence.appendLine("RESULT=FAIL stage=$stage")
                            } finally {
                                // Keep all partial state for failure analysis. No recursive deletion.
                                if (sandbox.isDirectory) {
                                    try {
                                        report.writeText(evidence.toString())
                                    } catch (error: Throwable) {
                                        activity.runOnUiThread {
                                            status.text = status.text.toString() +
                                                "\nWarning: evidence write failed: ${error.message}"
                                        }
                                    }
                                }
                                activity.runOnUiThread { text = "Isolated test completed — evidence retained" }
                            }
                        }
                    }
                    .show()
            } catch (error: Throwable) {
                status.text = "Isolated test refused: ${error.message ?: error::class.java.simpleName}"
                isEnabled = true
            }
        }
    }
    content.addView(button)
}

private fun xfceValidationDnsServers(activity: MainActivity): List<String> {
    val connectivity = activity.getSystemService(android.net.ConnectivityManager::class.java)
    val network = connectivity.activeNetwork ?: error("No active Android network")
    val links = connectivity.getLinkProperties(network) ?: error("No active Android network properties")
    return links.dnsServers.mapNotNull { it.hostAddress?.takeIf { host -> '%' !in host } }
        .distinct().also { require(it.isNotEmpty()) { "No Android DNS servers available" } }
}
