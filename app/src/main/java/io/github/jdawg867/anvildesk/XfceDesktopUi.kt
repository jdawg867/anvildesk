package io.github.jdawg867.anvildesk

import android.net.ConnectivityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.jdawg867.anvildesk.runtime.RootfsManifest
import io.github.jdawg867.anvildesk.runtime.RootlessRuntimeResult
import io.github.jdawg867.anvildesk.runtime.XfceDesktopLauncher
import io.github.jdawg867.anvildesk.runtime.XfceDesktopPackageSet
import io.github.jdawg867.anvildesk.runtime.XfceDesktopPlanner
import kotlin.concurrent.thread

internal fun addXfceDesktopSection(
    activity: MainActivity,
    content: LinearLayout,
    density: Float,
    manifest: RootfsManifest,
    rootfsReady: Boolean,
    xfceLauncher: XfceDesktopLauncher,
): Button {
    content.addView(TextView(activity).apply {
        text = "Managed XFCE desktop stack"
        textSize = 22f
        setPadding(0, (32 * density).toInt(), 0, (8 * density).toInt())
    })

    var initialError: String? = null
    val initiallyInstalled = if (rootfsReady) {
        try {
            xfceLauncher.binariesPresent(manifest)
        } catch (error: Throwable) {
            initialError = error.message ?: error::class.java.simpleName
            false
        }
    } else {
        false
    }

    val status = TextView(activity).apply {
        text = when {
            !rootfsReady ->
                "Install and verify Ubuntu rootfs before installing the XFCE desktop core."
            initialError != null ->
                "XFCE desktop state needs attention: $initialError"
            initiallyInstalled -> buildString {
                appendLine("${XfceDesktopPackageSet.ID} tools detected in the mutable runtime.")
                appendLine("Managed session contract is ready for Milestone 8.")
                append("No X server or display listener is started by Milestone 7.")
            }
            else -> buildString {
                appendLine("Ready to install fixed package set ${XfceDesktopPackageSet.ID}.")
                append("Packages: ${XfceDesktopPackageSet.PACKAGES.joinToString(", ")}")
            }
        }
        textSize = 16f
        setPadding(0, 0, 0, (12 * density).toInt())
    }
    content.addView(status)

    val button = Button(activity).apply {
        text = if (initiallyInstalled) {
            "Verify / reinstall XFCE desktop core"
        } else {
            "Install XFCE desktop core"
        }
        isEnabled = rootfsReady && initialError == null
        setOnClickListener {
            isEnabled = false
            text = "Refreshing package indexes…"
            status.text = "Refreshing Ubuntu package metadata through the managed Android-DNS path…"

            thread(name = "anvildesk-xfce-desktop") {
                var stage = "apt-get update"
                try {
                    val dnsServers = activeDnsServersForXfce(activity)
                    val refresh = xfceLauncher.refreshIndexes(manifest, dnsServers)
                    requireXfceSuccess(stage, refresh)

                    activity.runOnUiThread {
                        text = "Installing fixed XFCE package set…"
                        status.text = buildString {
                            appendLine("Package index refresh exit code: 0")
                            appendLine("Installing ${XfceDesktopPackageSet.ID} with --no-install-recommends.")
                            append("Packages: ${XfceDesktopPackageSet.PACKAGES.joinToString(", ")}")
                        }
                    }

                    stage = "apt-get install"
                    val install = xfceLauncher.install(manifest, dnsServers)
                    requireXfceSuccess(stage, install)

                    activity.runOnUiThread {
                        text = "Verifying XFCE desktop core…"
                        status.text = "Install exited 0. Verifying exact package state and required guest binaries…"
                    }

                    stage = "dpkg-query verification"
                    val verification = xfceLauncher.verify(manifest)
                    requireXfceSuccess(stage, verification)

                    stage = "managed session contract"
                    val session = xfceLauncher.sessionPreview(manifest)

                    activity.runOnUiThread {
                        status.text = xfceSuccessText(refresh, install, verification, session)
                        text = "Verify / reinstall XFCE desktop core"
                        isEnabled = true
                    }
                } catch (error: Throwable) {
                    activity.runOnUiThread {
                        status.text =
                            "XFCE desktop flow failed during $stage: ${error.message ?: error::class.java.simpleName}"
                        text = "Retry XFCE desktop core"
                        isEnabled = true
                    }
                }
            }
        }
    }
    content.addView(button)

    content.addView(TextView(activity).apply {
        text = "Milestone 7 installs only the fixed xfce-desktop-core-v1 allowlist in the provenance-linked mutable runtime and constructs a fixed local XFCE session command for Milestone 8. It does not start an X server, open a TCP display listener, expose arbitrary package/shell input, or broaden host filesystem binds."
        textSize = 14f
        setPadding(0, (24 * density).toInt(), 0, 0)
    })

    return button
}

private fun activeDnsServersForXfce(activity: MainActivity): List<String> {
    val connectivity = activity.getSystemService(ConnectivityManager::class.java)
    val network = connectivity.activeNetwork
        ?: throw IllegalStateException("No active Android network is available")
    val linkProperties = connectivity.getLinkProperties(network)
        ?: throw IllegalStateException("Active Android network properties are unavailable")
    val servers = linkProperties.dnsServers.mapNotNull { address ->
        address.hostAddress?.takeIf { '%' !in it }
    }.distinct()
    if (servers.isEmpty()) {
        throw IllegalStateException("Active Android network did not provide a usable DNS server")
    }
    return servers
}

private fun requireXfceSuccess(label: String, result: RootlessRuntimeResult) {
    if (result.timedOut) {
        throw IllegalStateException("$label timed out and was terminated")
    }
    if (result.exitCode != 0) {
        val detail = buildString {
            val stdout = result.stdout.trim()
            val stderr = result.stderr.trim()
            if (stdout.isNotEmpty()) append(" stdout: ${stdout.takeLast(4000)}")
            if (stderr.isNotEmpty()) append(" stderr: ${stderr.takeLast(4000)}")
        }
        throw IllegalStateException("$label failed with exit code ${result.exitCode ?: "unknown"}.$detail")
    }
}

private fun xfceSuccessText(
    refresh: RootlessRuntimeResult,
    install: RootlessRuntimeResult,
    verification: RootlessRuntimeResult,
    session: io.github.jdawg867.anvildesk.runtime.RootlessRuntimeInvocation,
): String = buildString {
    appendLine("XFCE desktop package set: ${XfceDesktopPackageSet.ID}")
    appendLine("Packages: ${XfceDesktopPackageSet.PACKAGES.joinToString(", ")}")
    appendLine("apt-get update exit code: ${refresh.exitCode}")
    appendLine("apt-get install exit code: ${install.exitCode}")
    appendLine("dpkg-query verification exit code: ${verification.exitCode}")
    appendLine("Installed package versions:")
    appendLine(verification.stdout.trim().ifBlank { "<empty>" })
    if (verification.stdoutTruncated) appendLine("<verification stdout truncated at safety limit>")
    val stderr = verification.stderr.trim()
    if (stderr.isNotEmpty()) {
        appendLine("verification stderr:")
        appendLine(stderr)
    }
    appendLine("Required XFCE guest binaries present.")
    appendLine("Managed session command: dbus-launch --exit-with-session startxfce4")
    appendLine("DISPLAY contract: ${session.environment["DISPLAY"]}")
    appendLine("XDG_RUNTIME_DIR: ${session.environment["XDG_RUNTIME_DIR"]}")
    appendLine("XDG session type: ${session.environment["XDG_SESSION_TYPE"]}")
    append("Display transport: not started in Milestone 7; reserved for Milestone 8.")
}.trimEnd()
