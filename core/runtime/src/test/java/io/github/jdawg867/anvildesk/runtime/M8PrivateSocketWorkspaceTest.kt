package io.github.jdawg867.anvildesk.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class M8PrivateSocketWorkspaceTest {
    @Test fun createsPrivateFixedSocketWorkspaceWithoutTouchingOtherRuntimes() {
        val files = Files.createTempDirectory("m8-socket-workspace").toFile()
        try {
            val production = File(files, "runtime/mutable/ubuntu-24.04.5-arm64")
            assertTrue(production.mkdirs())
            val marker = File(production, "keep").apply { writeText("unchanged") }
            val workspace = M8PrivateSocketWorkspace(files)
            val layout = workspace.prepare()
            assertEquals(File(files, "m8-display/tmp/.X11-unix/X0"), layout.socket)
            assertEquals("--bind=${layout.tmp.absolutePath}:/tmp!", workspace.guestTempBind(layout))
            assertFalse(layout.socket.exists())
            val owner = setOf(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
            for (dir in listOf(layout.root, layout.tmp, layout.socketDirectory)) {
                assertEquals(owner, Files.getPosixFilePermissions(dir.toPath()))
            }
            assertEquals("unchanged", marker.readText())
            try {
                workspace.prepare()
                fail("Reused existing namespace")
            } catch (_: IllegalArgumentException) {}
        } finally {
            files.deleteRecursively()
        }
    }

    @Test fun refusesSocketPathCollision() {
        val files = Files.createTempDirectory("m8-socket-existing").toFile()
        try {
            val workspace = M8PrivateSocketWorkspace(files)
            val layout = workspace.prepare()
            layout.socket.writeText("not a socket")
            try {
                workspace.inspect()
                fail("Accepted existing socket path")
            } catch (_: IllegalArgumentException) {}
        } finally {
            files.deleteRecursively()
        }
    }

    @Test fun refusesWeakenedPermissions() {
        val files = Files.createTempDirectory("m8-socket-permissions").toFile()
        try {
            val workspace = M8PrivateSocketWorkspace(files)
            val layout = workspace.prepare()
            Files.setPosixFilePermissions(layout.tmp.toPath(), setOf(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ))
            try {
                workspace.inspect()
                fail("Accepted group-readable temp")
            } catch (_: IllegalArgumentException) {}
        } finally {
            files.deleteRecursively()
        }
    }
}
