package io.github.jdawg867.anvildesk.runtime

/**
 * Couples an observed native-server health check and a real guest xdpyinfo
 * result to the explicit M8 lifecycle token. No side effects or auto-start.
 * The caller is responsible for obtaining the result from the restricted
 * PRoot guest with the M8 Xauthority and socket bind.
 */
class M8AuthenticatedReadiness(private val lifecycle: M8X11Lifecycle) {
    @Synchronized
    fun verifyAndMarkReady(
        token: Long,
        nowMillis: Long,
        nativeServerAlive: Boolean,
        guestProbe: RootlessRuntimeResult,
    ): Boolean {
        // Fail closed, without accepting stale/other-session probes.
        val state = lifecycle.snapshot()
        if (state !is M8X11Lifecycle.State.Starting || state.token != token) return false
        if (nowMillis > state.deadlineMillis) {
            lifecycle.expired(nowMillis)
            return false
        }
        if (!M8X11ProbeVerifier.accepted(guestProbe, nativeServerAlive)) return false
        lifecycle.authenticatedReady(token, nowMillis)
        return true
    }
}
