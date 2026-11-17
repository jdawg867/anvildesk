package io.github.jdawg867.anvildesk.runtime

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class M8X11LaunchGateTest {
    @Test fun authorityIsOwnerPrivateAndPlanNeverAllowsTcpOrUnauthenticatedX() {
        val app = Files.createTempDirectory("m8-auth").toFile()
        try {
            val workspace = M8PrivateSocketWorkspace(app)
            val layout = workspace.prepare()
            val gate = M8X11LaunchGate(workspace)
            val authority = gate.createAuthority(layout)
            val record = authority.readBytes()
            assertEquals(65535, (record[0].toInt() and 255) * 256 + (record[1].toInt() and 255))
            assertTrue(record.toString(Charsets.ISO_8859_1).contains("MIT-MAGIC-COOKIE-1"))
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(authority.toPath()))
            val plan = gate.plan(layout, authority)
            assertEquals(":0", plan.display)
            assertEquals(listOf(":0", "-nolisten", "tcp", "-auth", authority.absolutePath), plan.nativeArguments)
            assertFalse(plan.nativeArguments.contains("-ac"))
            try {
                gate.createAuthority(layout)
                fail("Second authority would overwrite first")
            } catch (_: java.nio.file.FileAlreadyExistsException) {}
        } finally {
            app.deleteRecursively()
        }
    }

    @Test fun missingAuthorityIsRejected() {
        val app = Files.createTempDirectory("m8-auth-missing").toFile()
        try {
            val workspace = M8PrivateSocketWorkspace(app)
            val layout = workspace.prepare()
            val gate = M8X11LaunchGate(workspace)
            try {
                gate.plan(layout, java.io.File(layout.root, "Xauthority"))
                fail("Missing Xauthority was accepted")
            } catch (_: IllegalArgumentException) {}
        } finally {
            app.deleteRecursively()
        }
    }
}
