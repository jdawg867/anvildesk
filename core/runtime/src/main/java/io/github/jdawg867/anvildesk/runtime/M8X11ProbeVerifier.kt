package io.github.jdawg867.anvildesk.runtime

/**
 * Evaluates a real guest xdpyinfo result, not a socket-existence check.
 * A native host still must independently prove that its X11 server is alive.
 */
object M8X11ProbeVerifier {
    fun accepted(result: RootlessRuntimeResult, nativeServerAlive: Boolean): Boolean {
        if (!nativeServerAlive || result.timedOut || result.exitCode != 0) return false
        if (result.stdoutTruncated || result.stderrTruncated) return false
        // xdpyinfo's standard output includes "name of display:    :0"
        return result.stdout.lineSequence().any { line ->
            val parts = line.split(':', limit = 2)
            parts.size == 2 &&
                parts[0].trim() == "name of display" &&
                parts[1].trim() == M8PrivateDisplayContract.GUEST_DISPLAY
        }
    }
}
