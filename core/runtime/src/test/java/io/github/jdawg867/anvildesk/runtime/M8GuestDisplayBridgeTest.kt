package io.github.jdawg867.anvildesk.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class M8GuestDisplayBridgeTest {
    @Test fun narrowSocketAndAuthorityBindsAreExplicit() {
        val files = Files.createTempDirectory("m8-bridge").toFile()
        try {
            val workspace = M8PrivateSocketWorkspace(files)
            val layout = workspace.prepare()
            val authority = M8X11LaunchGate(workspace).createAuthority(layout)
            val guestRoot = File(files, "test-guest")
            val guestAuth = File(guestRoot, "root/.Xauthority")
            guestAuth.parentFile.mkdirs()
            guestAuth.createNewFile()
            val guestSocketDir = File(guestRoot, "tmp/.X11-unix")
            guestSocketDir.mkdirs()
            val plan = M8GuestDisplayBridge.plan(workspace, layout, authority, guestAuth, guestSocketDir)
            assertEquals(listOf(
                "--bind=${layout.socketDirectory.absolutePath}:/tmp/.X11-unix!",
                "--bind=${authority.absolutePath}:/root/.Xauthority!",
            ), plan.binds)
            assertEquals(":0", plan.guestEnvironment["DISPLAY"])
            assertEquals("/root/.Xauthority", plan.guestEnvironment["XAUTHORITY"])
            assertEquals(listOf("/usr/bin/xdpyinfo", "-display", ":0"), plan.guestDisplayProbe)
        } finally {
            files.deleteRecursively()
        }
    }

    @Test fun failClosedIfGuestTargetMissing() {
        val files = Files.createTempDirectory("m8-bridge-reject").toFile()
        try {
            val workspace = M8PrivateSocketWorkspace(files)
            val layout = workspace.prepare()
            val authority = M8X11LaunchGate(workspace).createAuthority(layout)
            val missing = File(files, "missing")
            val socketDir = File(files, "guest-tmp").apply { mkdir() }
            try {
                M8GuestDisplayBridge.plan(workspace, layout, authority, missing, socketDir)
                fail("Missing guest authority target accepted")
            } catch (_: IllegalArgumentException) {}
            assertTrue(!missing.exists())
        } finally {
            files.deleteRecursively()
        }
    }
}
