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
            text = "Root has not been requested."
            textSize = 16f
            setPadding(0, (24 * density).toInt(), 0, (12 * density).toInt())
        }
        content.addView(rootStatus)

        val verifyRootButton = Button(this).apply {
            text = "Verify root access"
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
            text = "Milestone 1 intentionally performs no rootfs download and executes no privileged operation beyond an explicit uid check."
            textSize = 14f
            setPadding(0, (24 * density).toInt(), 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"
}
