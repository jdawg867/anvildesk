package io.github.jdawg867.anvildesk.runtime

import org.junit.Assert.*
import org.junit.Test

class M8X11LifecycleTest {
    @Test fun explicitStartReadyAndStop() {
        val machine = M8X11Lifecycle()
        assertEquals(M8X11Lifecycle.State.Idle, machine.snapshot())
        val token = machine.begin(100, 1000)
        assertEquals(M8X11Lifecycle.State.Starting(token, 1100), machine.snapshot())
        machine.authenticatedReady(token, 200)
        assertEquals(M8X11Lifecycle.State.Ready(token), machine.snapshot())
        machine.requestStop(token)
        assertEquals(M8X11Lifecycle.State.Stopping(token), machine.snapshot())
        machine.stopped(token)
        assertEquals(M8X11Lifecycle.State.Idle, machine.snapshot())
    }

    @Test fun rejectsStaleTokensAndFalseReadiness() {
        val machine = M8X11Lifecycle()
        val token = machine.begin(0, 10)
        assertThrows(IllegalStateException::class.java) { machine.authenticatedReady(token + 1, 2) }
        assertThrows(IllegalStateException::class.java) { machine.begin(1, 10) }
        assertThrows(IllegalStateException::class.java) { machine.authenticatedReady(token, 11) }
        assertTrue(machine.expired(11))
        assertEquals(M8X11Lifecycle.State.Failed("X11 startup timed out"), machine.snapshot())
        assertThrows(IllegalStateException::class.java) { machine.begin(12, 10) }
        machine.resetAfterCleanup()
        assertTrue(machine.begin(12, 10) > token)
    }

    @Test fun stopAndCrashRequireCurrentSession() {
        val machine = M8X11Lifecycle()
        val token = machine.begin(0, 10)
        assertThrows(IllegalStateException::class.java) { machine.requestStop(token + 1) }
        machine.requestStop(token)
        assertThrows(IllegalStateException::class.java) { machine.stopped(token + 1) }
        machine.crashed(token, "native process exited")
        assertTrue(machine.snapshot() is M8X11Lifecycle.State.Failed)
        machine.resetAfterCleanup()
        assertEquals(M8X11Lifecycle.State.Idle, machine.snapshot())
    }

    @Test fun rejectsInvalidDeadline() {
        val machine = M8X11Lifecycle()
        assertThrows(IllegalArgumentException::class.java) { machine.begin(0, 0) }
        assertThrows(IllegalArgumentException::class.java) { machine.begin(0, 120001) }
        assertThrows(IllegalStateException::class.java) { machine.begin(Long.MAX_VALUE, 1) }
    }
}
