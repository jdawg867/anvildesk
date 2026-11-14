package io.github.jdawg867.anvildesk.runtime

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class M8X11StartCoordinatorTest {
    private fun plan() = M8X11LaunchGate.LaunchPlan(
        tempDirectory = File("/private/m8/tmp"),
        socketPath = File("/private/m8/tmp/.X11-unix/X0"),
        authorityFile = File("/private/m8/Xauthority"),
        display = ":0",
        nativeArguments = listOf(":0", "-nolisten", "tcp", "-auth", "/private/m8/Xauthority"),
    )
    private class FakeHost(var accept: Boolean = true, var alive: Boolean = true, var stopped: Boolean = true) :
        M8X11StartCoordinator.NativeHost {
        var starts = 0
        override fun start(plan: M8X11LaunchGate.LaunchPlan): Boolean { starts++; return accept }
        override fun stop() = stopped
        override fun isAlive() = alive
    }
    private val probe = RootlessRuntimeResult(
        stdout = "name of display:    :0\n", stderr = "", exitCode = 0, timedOut = false,
    )

    @Test fun explicitStartProbeAndStop() {
        val lifecycle = M8X11Lifecycle()
        val coordinator = M8X11StartCoordinator(lifecycle)
        val host = FakeHost()
        assertEquals(M8X11Lifecycle.State.Idle, lifecycle.snapshot())
        val token = coordinator.start(plan(), 0, 1000, host)
        assertEquals(1, host.starts)
        assertTrue(coordinator.verify(token, 100, probe, host))
        assertEquals(M8X11Lifecycle.State.Ready(token), lifecycle.snapshot())
        assertTrue(coordinator.stop(token, host))
        assertEquals(M8X11Lifecycle.State.Idle, lifecycle.snapshot())
    }

    @Test fun rejectedStartNeverReady() {
        val lifecycle = M8X11Lifecycle()
        val coordinator = M8X11StartCoordinator(lifecycle)
        val host = FakeHost(accept = false)
        val token = coordinator.start(plan(), 0, 1000, host)
        assertTrue(lifecycle.snapshot() is M8X11Lifecycle.State.Failed)
        assertFalse(coordinator.verify(token, 1, probe, host))
    }

    @Test fun unsafeArgumentsNeverInvokeNativeHost() {
        val lifecycle = M8X11Lifecycle()
        val coordinator = M8X11StartCoordinator(lifecycle)
        val host = FakeHost()
        val unsafe = plan().copy(nativeArguments = listOf(":0", "-ac"))
        assertThrows(IllegalArgumentException::class.java) {
            coordinator.start(unsafe, 0, 1000, host)
        }
        assertEquals(0, host.starts)
        assertEquals(M8X11Lifecycle.State.Idle, lifecycle.snapshot())
    }

    @Test fun failedStopRequiresExplicitRecovery() {
        val lifecycle = M8X11Lifecycle()
        val coordinator = M8X11StartCoordinator(lifecycle)
        val host = FakeHost(stopped = false)
        val token = coordinator.start(plan(), 0, 1000, host)
        assertFalse(coordinator.stop(token, host))
        assertTrue(lifecycle.snapshot() is M8X11Lifecycle.State.Failed)
    }
}
