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
        val provisioner = RootfsProvisioner(RootfsInstallStore(filesDir))
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

        content.addView(TextView(this).apply {
            text = "Milestone 2 only downloads, verifies, and installs the rootfs into app-private storage. It does not execute Linux, expose a shell, or request root."
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

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"
}
