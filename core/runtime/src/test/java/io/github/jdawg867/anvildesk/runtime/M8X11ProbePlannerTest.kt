package io.github.jdawg867.anvildesk.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class M8X11ProbePlannerTest {
    @Test fun guestProbeUsesOnlyPinnedBinaryAndPrivateNestedBinds() {
        val appFiles = Files.createTempDirectory("m8-probe").toFile()
        try {
            val workspace = M8PrivateSocketWorkspace(appFiles)
            val layout = workspace.prepare()
            val authority = M8X11LaunchGate(workspace).createAuthority(layout)
            val guestHome = File(appFiles, "guest-home").apply { mkdir() }
            File(guestHome, ".Xauthority").createNewFile()
            val guestTemp = File(appFiles, "guest-temp").apply { mkdir() }
            File(guestTemp, ".X11-unix").mkdir()
            val bridge = M8GuestDisplayBridge.plan(workspace, layout, authority, guestHome, guestTemp)
            val rootfs = File(appFiles, "rootfs").apply { mkdir() }
            File(rootfs, "usr/bin/xdpyinfo").apply { parentFile.mkdirs(); writeText("test") }
            val native = File(appFiles, "native").apply { mkdir() }
            File(native, RootlessRuntimePlanner.PROOT_LIBRARY).writeText("test")
            File(native, RootlessRuntimePlanner.LOADER_LIBRARY).writeText("test")
            val cache = File(appFiles, "cache").apply { mkdir() }
            val invocation = RootlessRuntimePlanner.m8AuthenticatedDisplayProbe(rootfs, native, cache, bridge)
            assertEquals("/usr/bin/xdpyinfo", invocation.command.takeLast(3)[0])
            assertEquals(listOf("/usr/bin/xdpyinfo", "-display", ":0"), invocation.command.takeLast(3))
            assertEquals(bridge.binds, invocation.command.dropLast(3).takeLast(4))
            assertEquals(":0", invocation.environment["DISPLAY"])
            assertEquals("/root/.Xauthority", invocation.environment["XAUTHORITY"])
            assertFalse(invocation.command.contains("-c"))
            assertFalse(invocation.command.any { it.contains("/sdcard") || it.contains("/storage") })
            assertTrue(invocation.command.contains("--kill-on-exit"))
        } finally { appFiles.deleteRecursively() }
    }
}
