package io.github.jdawg867.anvildesk

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.jdawg867.anvildesk.runtime.DeviceInspector
import io.github.jdawg867.anvildesk.runtime.RootAccess
import io.github.jdawg867.anvildesk.runtime.RootVerification
import io.github.jdawg867.anvildesk.runtime.RootfsCatalog
import io.github.jdawg867.anvildesk.runtime.RootfsInstallStore
import io.github.jdawg867.anvildesk.runtime.RootfsProvisioner
import io.github.jdawg867.anvildesk.runtime.RootfsProvisioningState
import io.github.jdawg867.anvildesk.runtime.RootlessRuntimeLauncher
import io.github.jdawg867.anvildesk.runtime.RootlessRuntimeResult
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val snapshot = DeviceInspector.snapshot()
        val density = resources.displayMetrics.density
        val padding = (24 * density).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(padding, padding, padding, padding)
        }

        content.addView(TextView(this).apply {
            text = "AnvilDesk"
            textSize = 30f
        })

        content.addView(TextView(this).apply {
            text = "Trusted Linux runtime bootstrap for Android"
            textSize = 16f
            setPadding(0, (8 * density).toInt(), 0, (24 * density).toInt())
        })

        val deviceText = TextView(this).apply {
            text = buildString {
                appendLine("Device: ${snapshot.manufacturer} ${snapshot.model}")
                appendLine("Android API: ${snapshot.sdkInt}")
                appendLine("Primary ABI: ${snapshot.primaryAbi}")
                appendLine("ARM64 capable: ${yesNo(snapshot.arm64Capable)}")
                append("Root binary detected: ${yesNo(snapshot.rootCandidateDetected)}")
            }
            textSize = 16f
        }
        content.addView(deviceText)

        val rootStatus = TextView(this).apply {
            text = if (snapshot.rootCandidateDetected) {
                "Root binary detected. Root has not been requested."
            } else {
                "No root binary detected. Root verification is unavailable on this device."
            }
            textSize = 16f
            setPadding(0, (24 * density).toInt(), 0, (12 * density).toInt())
        }
        content.addView(rootStatus)

        val verifyRootButton = Button(this).apply {
            text = if (snapshot.rootCandidateDetected) {
                "Verify root access"
            } else {
                "Root verification unavailable"
            }
            isEnabled = snapshot.rootCandidateDetected
            setOnClickListener {
                isEnabled = false
                rootStatus.text = "Requesting root through the installed root manager…"
                thread(name = "anvildesk-root-check") {
                    val result = RootAccess.verify()
                    runOnUiThread {
                        rootStatus.text = when (result) {
                            RootVerification.Granted -> "Root access verified (uid 0)."
                            is RootVerification.Denied -> "Root access unavailable: ${result.reason}"
                        }
                        isEnabled = true
                    }
                }
            }
        }
        content.addView(verifyRootButton)

        content.addView(TextView(this).apply {
            text = "Linux rootfs"
            textSize = 22f
            setPadding(0, (32 * density).toInt(), 0, (8 * density).toInt())
        })

        val manifest = RootfsCatalog.Ubuntu24045Arm64
        val rootfsStore = RootfsInstallStore(filesDir)
        val provisioner = RootfsProvisioner(rootfsStore)
        val initialState = try {
            provisioner.currentState(manifest)
        } catch (error: Throwable) {
            RootfsProvisioningState.Failed(
                "Installed rootfs state could not be read: ${error.message ?: error::class.java.simpleName}",
            )
        }

        content.addView(TextView(this).apply {
            text = buildString {
                appendLine("Ubuntu Base ${manifest.version} ARM64")
                appendLine("Source: Canonical cdimage.ubuntu.com")
                append("Pinned SHA-256: ${manifest.sha256}")
            }
            textSize = 14f
        })

        val rootfsStatus = TextView(this).apply {
            text = rootfsStatusText(initialState)
            textSize = 16f
            setPadding(0, (16 * density).toInt(), 0, (12 * density).toInt())
        }
        content.addView(rootfsStatus)

        var smokeTestButton: Button? = null
        var managedSessionButton: Button? = null

        val installRootfsButton = Button(this).apply {
            text = when {
                !snapshot.arm64Capable -> "ARM64 rootfs unavailable"
                initialState is RootfsProvisioningState.Ready -> "Ubuntu rootfs ready"
                initialState is RootfsProvisioningState.Failed -> "Rootfs state needs attention"
                else -> "Install verified Ubuntu rootfs"
            }
            isEnabled = snapshot.arm64Capable &&
                initialState !is RootfsProvisioningState.Ready &&
                initialState !is RootfsProvisioningState.Failed

            setOnClickListener {
                isEnabled = false
                text = "Installing Ubuntu rootfs…"
                thread(name = "anvildesk-rootfs-provision") {
                    try {
                        provisioner.provision(manifest) { state ->
                            runOnUiThread {
                                rootfsStatus.text = rootfsStatusText(state)
                                when (state) {
                                    is RootfsProvisioningState.Ready -> {
                                        text = "Ubuntu rootfs ready"
                                        isEnabled = false
                                        smokeTestButton?.isEnabled = snapshot.arm64Capable
                                        managedSessionButton?.isEnabled = snapshot.arm64Capable
                                    }
                                    is RootfsProvisioningState.Failed -> {
                                        text = "Retry verified Ubuntu install"
                                        isEnabled = snapshot.arm64Capable
                                    }
                                    else -> {
                                        text = "Installing Ubuntu rootfs…"
                                        isEnabled = false
                                    }
                                }
                            }
                        }
                    } catch (_: Throwable) {
                        // RootfsProvisioner reports the failure through the state callback.
                    }
                }
            }
        }
        content.addView(installRootfsButton)

        val runtimeLauncher = RootlessRuntimeLauncher(
            store = rootfsStore,
            nativeLibraryDirectory = File(applicationInfo.nativeLibraryDir),
            appCacheDirectory = cacheDir,
            sessionDataDirectory = File(filesDir, "linux-sessions/default"),
        )

        content.addView(TextView(this).apply {
            text = "Linux userspace smoke test"
            textSize = 22f
            setPadding(0, (32 * density).toInt(), 0, (8 * density).toInt())
        })

        val runtimeStatus = TextView(this).apply {
            text = if (initialState is RootfsProvisioningState.Ready) {
                "Ready to execute fixed Ubuntu command: /usr/bin/uname -a"
            } else {
                "Install and verify Ubuntu rootfs before running the smoke test."
            }
            textSize = 16f
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        content.addView(runtimeStatus)

        smokeTestButton = Button(this).apply {
            text = "Run Linux smoke test"
            isEnabled = snapshot.arm64Capable && initialState is RootfsProvisioningState.Ready
            setOnClickListener {
                isEnabled = false
                text = "Running Linux smoke test…"
                runtimeStatus.text = "Running /usr/bin/uname -a inside verified Ubuntu rootfs…"

                thread(name = "anvildesk-rootless-smoke") {
                    try {
                        val result = runtimeLauncher.runUbuntuSmokeTest(manifest)
                        runOnUiThread {
                            runtimeStatus.text = rootlessResultText(result)
                            text = "Run Linux smoke test again"
                            isEnabled = true
                        }
                    } catch (error: Throwable) {
                        runOnUiThread {
                            runtimeStatus.text =
                                "Linux smoke test failed: ${error.message ?: error::class.java.simpleName}"
                            text = "Retry Linux smoke test"
                            isEnabled = true
                        }
                    }
                }
            }
        }
        content.addView(smokeTestButton)

        content.addView(TextView(this).apply {
            text = "Managed Ubuntu session"
            textSize = 22f
            setPadding(0, (32 * density).toInt(), 0, (8 * density).toInt())
        })

        val managedSessionStatus = TextView(this).apply {
            text = if (initialState is RootfsProvisioningState.Ready) {
                "Ready to verify a fixed managed Ubuntu shell session with app-private /root and /tmp."
            } else {
                "Install and verify Ubuntu rootfs before starting a managed session."
            }
            textSize = 16f
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        content.addView(managedSessionStatus)

        managedSessionButton = Button(this).apply {
            text = "Verify managed Ubuntu session"
            isEnabled = snapshot.arm64Capable && initialState is RootfsProvisioningState.Ready
            setOnClickListener {
                isEnabled = false
                text = "Verifying managed session…"
                managedSessionStatus.text =
                    "Starting fixed /bin/sh diagnostic inside verified Ubuntu rootfs…"

                thread(name = "anvildesk-managed-session") {
                    try {
                        val result = runtimeLauncher.runManagedSessionVerification(manifest)
                        runOnUiThread {
                            managedSessionStatus.text = managedSessionResultText(result)
                            text = "Verify managed Ubuntu session again"
                            isEnabled = true
                        }
                    } catch (error: Throwable) {
                        runOnUiThread {
                            managedSessionStatus.text =
                                "Managed Ubuntu session failed: ${error.message ?: error::class.java.simpleName}"
                            text = "Retry managed Ubuntu session"
                            isEnabled = true
                        }
                    }
                }
            }
        }
        content.addView(managedSessionButton)

        content.addView(TextView(this).apply {
            text = "Milestone 4 keeps command input fixed in code. The guest shell receives no user-supplied command text, /root and /tmp are mapped only to AnvilDesk app-private directories, stdout/stderr are bounded, and no external storage or network listener is exposed."
            textSize = 14f
            setPadding(0, (24 * density).toInt(), 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun rootfsStatusText(state: RootfsProvisioningState): String = when (state) {
        RootfsProvisioningState.NotInstalled -> "Ubuntu rootfs is not installed."
        is RootfsProvisioningState.Downloading -> {
            val downloaded = formatBytes(state.bytesDownloaded)
            val total = state.totalBytes?.takeIf { it > 0L }?.let(::formatBytes)
            if (total != null) "Downloading verified rootfs: $downloaded / $total" else "Downloading verified rootfs: $downloaded"
        }
        RootfsProvisioningState.Verifying -> "Download complete. Verifying pinned SHA-256…"
        RootfsProvisioningState.Extracting -> "SHA-256 verified. Extracting into app-private staging…"
        is RootfsProvisioningState.Ready ->
            "Verified Ubuntu rootfs ready (${state.record.entriesExtracted} archive entries)."
        is RootfsProvisioningState.Failed -> "Rootfs install failed: ${state.message}"
    }

    private fun rootlessResultText(result: RootlessRuntimeResult): String {
        if (result.timedOut) {
            return "Linux smoke test timed out and was terminated."
        }

        return buildString {
            appendLine("Command: /usr/bin/uname -a")
            appendLine("Exit code: ${result.exitCode ?: "unknown"}")
            appendLine("stdout:")
            appendLine(result.stdout.trim().ifBlank { "<empty>" })
            if (result.stdoutTruncated) appendLine("<stdout truncated at safety limit>")
            val stderr = result.stderr.trim()
            if (stderr.isNotEmpty()) {
                appendLine("stderr:")
                appendLine(stderr)
            }
            if (result.stderrTruncated) append("<stderr truncated at safety limit>")
        }.trimEnd()
    }

    private fun managedSessionResultText(result: RootlessRuntimeResult): String {
        if (result.timedOut) {
            return "Managed Ubuntu session timed out and was terminated."
        }

        return buildString {
            appendLine("Managed session exit code: ${result.exitCode ?: "unknown"}")
            appendLine("stdout:")
            appendLine(result.stdout.trim().ifBlank { "<empty>" })
            if (result.stdoutTruncated) appendLine("<stdout truncated at safety limit>")
            val stderr = result.stderr.trim()
            if (stderr.isNotEmpty()) {
                appendLine("stderr:")
                appendLine(stderr)
            }
            if (result.stderrTruncated) append("<stderr truncated at safety limit>")
        }.trimEnd()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"
}
