package io.github.jdawg867.anvildesk.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class M8PrivateDisplayContractTest {
    @Test fun fixedGuestDisplayAndSocket() {
        assertEquals(":0", M8PrivateDisplayContract.GUEST_DISPLAY)
        assertEquals("/tmp/.X11-unix/X0", M8PrivateDisplayContract.GUEST_SOCKET)
        assertEquals("/root/.Xauthority", M8PrivateDisplayContract.GUEST_AUTHORITY)
    }

    @Test fun rejectsNamespaceOutsideAppAndOtherNames() {
        val files = Files.createTempDirectory("m8-files").toFile()
        try {
            assertEquals(
                File(files, "m8-display"),
                M8PrivateDisplayContract.validateHostNamespace(files, File(files, "m8-display")),
            )
            for (bad in listOf(File(files, "runtime"), File(files, "../elsewhere"))) {
                try {
                    M8PrivateDisplayContract.validateHostNamespace(files, bad)
                    fail("Unexpected acceptance of $bad")
                } catch (_: IllegalArgumentException) {}
            }
            val target = File(files, "other").apply { mkdir() }
            val link = File(files, "m8-display")
            Files.createSymbolicLink(link.toPath(), target.toPath())
            try {
                M8PrivateDisplayContract.validateHostNamespace(files, link)
                fail("Symlink accepted")
            } catch (_: IllegalArgumentException) {}
        } finally {
            files.deleteRecursively()
        }
    }

    @Test fun readinessNeedsLiveServerAndAuthenticatedProbe() {
        assertTrue(M8PrivateDisplayContract.isReady(true, true, true))
        assertFalse(M8PrivateDisplayContract.isReady(true, false, true))
        assertFalse(M8PrivateDisplayContract.isReady(false, true, true))
        assertFalse(M8PrivateDisplayContract.isReady(true, true, false))
    }

    @Test fun lifecycleRequiresExplicitReadinessAndCleanup() {
        val machine = M8DisplayLifecycle()
        try {
            machine.prepared()
            fail("Prepared without begin")
        } catch (_: IllegalStateException) {}
        machine.begin()
        machine.prepared()
        machine.serverStarted()
        try {
            machine.ready(true, false, true)
            fail("Authenticated probe missing")
        } catch (_: IllegalStateException) {}
        assertEquals(M8DisplayPhase.WAITING_FOR_READY, machine.phase)
        machine.ready(true, true, true)
        assertEquals(M8DisplayPhase.RUNNING, machine.phase)
        machine.fail()
        try {
            machine.begin()
            fail("Restarted without cleanup")
        } catch (_: IllegalStateException) {}
        machine.cleanupComplete()
        machine.begin()
        machine.stop()
        machine.cleanupComplete()
        assertEquals(M8DisplayPhase.IDLE, machine.phase)
    }
}
