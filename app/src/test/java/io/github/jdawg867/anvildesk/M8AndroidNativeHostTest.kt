package io.github.jdawg867.anvildesk

import io.github.jdawg867.anvildesk.runtime.M8X11LaunchGate
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class M8AndroidNativeHostTest {
    private fun plan() = M8X11LaunchGate.LaunchPlan(
        tempDirectory = File("/private/m8/tmp"),
        socketPath = File("/private/m8/tmp/.X11-unix/X0"),
        authorityFile = File("/private/m8/Xauthority"),
        display = ":0",
        nativeArguments = listOf(":0", "-nolisten", "tcp", "-auth", "/private/m8/Xauthority"),
    )

    @Test fun missingLibraryIsBlocked() {
        val dir = Files.createTempDirectory("m8-host").toFile()
        try {
            val host = M8AndroidNativeHost(dir)
            assertEquals(M8AndroidNativeHost.Capability.LIBRARY_NOT_PACKAGED, host.capability())
            assertFalse(host.start(plan()))
            assertFalse(host.isAlive())
            assertFalse(host.stop())
        } finally { dir.deleteRecursively() }
    }

    @Test fun packagedButIncompatibleJniIsBlocked() {
        val dir = Files.createTempDirectory("m8-host").toFile()
        try {
            File(dir, "libXlorie.so").writeText("dummy marker, never loaded")
            val host = M8AndroidNativeHost(dir)
            assertEquals(M8AndroidNativeHost.Capability.JNI_ADAPTER_NOT_IMPLEMENTED, host.capability())
            assertFalse(host.start(plan()))
            assertFalse(host.isAlive())
        } finally { dir.deleteRecursively() }
    }
}
