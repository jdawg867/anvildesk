package io.github.jdawg867.anvildesk.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class M8X11ProbeVerifierTest {
    private val success = RootlessRuntimeResult(
        stdout = "name of display:    :0\nversion number:    11.0\n",
        stderr = "", exitCode = 0, timedOut = false,
    )

    @Test fun acceptsLiveCleanGuestHandshakeOnly() {
        assertTrue(M8X11ProbeVerifier.accepted(success, nativeServerAlive = true))
        assertFalse(M8X11ProbeVerifier.accepted(success, nativeServerAlive = false))
        assertFalse(M8X11ProbeVerifier.accepted(success.copy(exitCode = 1), true))
        assertFalse(M8X11ProbeVerifier.accepted(success.copy(timedOut = true), true))
        assertFalse(M8X11ProbeVerifier.accepted(success.copy(stdoutTruncated = true), true))
        assertFalse(M8X11ProbeVerifier.accepted(success.copy(stderrTruncated = true), true))
        assertFalse(M8X11ProbeVerifier.accepted(success.copy(stdout = "name of display: :1\n"), true))
        assertFalse(M8X11ProbeVerifier.accepted(success.copy(stdout = "socket exists"), true))
    }
}
