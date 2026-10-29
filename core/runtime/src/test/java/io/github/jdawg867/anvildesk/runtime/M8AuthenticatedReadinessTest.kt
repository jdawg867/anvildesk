package io.github.jdawg867.anvildesk.runtime

import org.junit.Assert.*
import org.junit.Test

class M8AuthenticatedReadinessTest {
    private val valid = RootlessRuntimeResult(
        stdout = "name of display:    :0\nversion number:    11.0\n",
        stderr = "", exitCode = 0, timedOut = false,
    )

    @Test fun requiresRealProbeAndLiveServer() {
        val state = M8X11Lifecycle()
        val gate = M8AuthenticatedReadiness(state)
        val token = state.begin(100, 1000)
        assertFalse(gate.verifyAndMarkReady(token, 101, false, valid))
        assertFalse(gate.verifyAndMarkReady(token, 102, true, valid.copy(exitCode = 1)))
        assertEquals(M8X11Lifecycle.State.Starting(token, 1100), state.snapshot())
        assertTrue(gate.verifyAndMarkReady(token, 103, true, valid))
        assertEquals(M8X11Lifecycle.State.Ready(token), state.snapshot())
        assertFalse(gate.verifyAndMarkReady(token, 104, true, valid))
    }

    @Test fun staleSessionProbeCannotMarkReady() {
        val state = M8X11Lifecycle()
        val gate = M8AuthenticatedReadiness(state)
        val token = state.begin(0, 100)
        assertFalse(gate.verifyAndMarkReady(token + 1, 1, true, valid))
        assertEquals(M8X11Lifecycle.State.Starting(token, 100), state.snapshot())
        assertFalse(gate.verifyAndMarkReady(token, 101, true, valid))
        assertEquals(M8X11Lifecycle.State.Failed("X11 startup timed out"), state.snapshot())
    }

    @Test fun cannotMarkReadyWithoutExplicitStart() {
        val state = M8X11Lifecycle()
        assertFalse(M8AuthenticatedReadiness(state).verifyAndMarkReady(1, 1, true, valid))
        assertEquals(M8X11Lifecycle.State.Idle, state.snapshot())
    }
}
