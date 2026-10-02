package io.github.jdawg867.anvildesk

import android.net.ConnectivityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.jdawg867.anvildesk.runtime.RootfsManifest
import io.github.jdawg867.anvildesk.runtime.RootlessRuntimeLauncher
import io.github.jdawg867.anvildesk.runtime.RootlessRuntimeResult
import io.github.jdawg867.anvildesk.runtime.X11BootstrapLauncher
import io.github.jdawg867.anvildesk.runtime.X11BootstrapPackageSet
import kotlin.concurrent.thread

internal fun addX11BootstrapSection(
    activity: MainActivity,
    content: LinearLayout,
    density: Float,
    manifest: RootfsManifest,
    rootfsReady: Boolean,
    runtimeLauncher: RootlessRuntimeLauncher,
    x11Launcher: X11BootstrapLauncher,
): Button {
    content.addView(TextView(activity).apply {
        text = "Controlled X11 bootstrap packages"
        textSize = 22f
        setPadding(0, (32 * density).toInt(), 0, (8 * density).toInt())
    })

    var initialError: String? = null
    val initiallyInstalled = if (rootfsReady) {
        try {
            x11Launcher.binariesPresent(manifest)
        } catch (error: Throwable) {
            initialError = error.message ?: error::class.java.simpleName
            false
        }
    } else {
        false
    }

    val status = TextView(activity).apply {
        text = when {
            !rootfsReady -> "Install and verify Ubuntu rootfs before installing the X11 bootstrap package set."
            initialError != null -> "X11 bootstrap state needs attention: $initialError"
            initiallyInstalled -> buildString {
                appendLine("${X11BootstrapPackageSet.ID} tools detected in the mutable runtime.")
                append("Ready to verify or reinstall the fixed package set.")
            }
            else -> buildString {
                appendLine("Ready to install fixed package set ${X11BootstrapPackageSet.ID}.")
                append("Packages: ${X11BootstrapPackageSet.PACKAGES.joinToString(", ")}")
            }
        }
        textSize = 16f
        setPadding(0, 0, 0, (12 * density).toInt())
    }
    content.addView(status)

    val button = Button(activity).apply {
        text = if (initiallyInstalled) {
            "Verify / reinstall X11 bootstrap packages"
        } else {
            "Install X11 bootstrap packages"
        }
        isEnabled = rootfsReady && initialError == null
        setOnClickListener {
            isEnabled = false
            text = "Refreshing package indexes…"
            status.text = "Refreshing Ubuntu package metadata through the existing managed Android-DNS path…"

            thread(name = "anvildesk-x11-bootstrap") {
                try {
                    val dnsServers = activeDnsServers(activity)
                    val refresh = runtimeLauncher.runPackageIndexRefresh(
                        manifest = manifest,
                        dnsServers = dnsServers,
                    )
                    requireSuccess("apt-get update", refresh)

                    activity.runOnUiThread {
                        text = "Installing fixed X11 package set…"
                        status.text = buildString {
                            appendLine("Package index refresh exit code: 0")
                            appendLine("Installing ${X11BootstrapPackageSet.ID} with --no-install-recommends.")
                            append("Packages: ${X11BootstrapPackageSet.PACKAGES.joinToString(", ")}")
                        }
                    }

                    val install = x11Launcher.install(
                        manifest = manifest,
                        dnsServers = dnsServers,
                    )
                    requireSuccess("apt-get install", install)

                    activity.runOnUiThread {
                        text = "Verifying X11 package set…"
                        status.text = "Install exited 0. Verifying exact package state and required guest binaries…"
                    }

                    val verification = x11Launcher.verify(manifest)
                    requireSuccess("dpkg-query verification", verification)

                    activity.runOnUiThread {
                        status.text = successText(refresh, install, verification)
                        text = "Verify / reinstall X11 bootstrap packages"
                        isEnabled = true
                    }
                } catch (error: Throwable) {
                    activity.runOnUiThread {
                        status.text = "X11 bootstrap package flow failed: ${error.message ?: error::class.java.simpleName}"
                        text = "Retry X11 bootstrap packages"
                        isEnabled = true
                    }
                }
            }
        }
    }
    content.addView(button)

    content.addView(TextView(activity).apply {
        text = "Milestone 6 installs only the fixed x11-bootstrap-v1 allowlist in the provenance-linked mutable runtime. No package names come from user input, no shell command is interpolated, and the existing restricted DNS/device-file bind policy is reused."
        textSize = 14f
        setPadding(0, (24 * density).toInt(), 0, 0)
    })

    return button
}

private fun activeDnsServers(activity: MainActivity): List<String> {
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

private fun requireSuccess(label: String, result: RootlessRuntimeResult) {
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

private fun successText(
    refresh: RootlessRuntimeResult,
    install: RootlessRuntimeResult,
    verification: RootlessRuntimeResult,
): String = buildString {
    appendLine("X11 bootstrap package set: ${X11BootstrapPackageSet.ID}")
    appendLine("Packages: ${X11BootstrapPackageSet.PACKAGES.joinToString(", ")}")
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
    append("Required guest binaries present: dbus-launch, xauth, xdpyinfo, xset")
}.trimEnd()
