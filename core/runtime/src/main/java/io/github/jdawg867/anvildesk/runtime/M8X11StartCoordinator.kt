package io.github.jdawg867.anvildesk.runtime

/**
 * Explicit, fail-closed handoff between M8 lifecycle and a future native host.
 * No JNI is loaded here. Only the launch plan produced by M8X11LaunchGate is
 * accepted; native startup and guest probes are injected by the actual host.
 */
class M8X11StartCoordinator(
    private val lifecycle: M8X11Lifecycle,
    private val readiness: M8AuthenticatedReadiness = M8AuthenticatedReadiness(lifecycle),
) {
    interface NativeHost {
        /** Must return only when startup has been accepted, not when X11 is ready. */
        fun start(plan: M8X11LaunchGate.LaunchPlan): Boolean
        /** Must stop native ownership and confirm cleanup before returning true. */
        fun stop(): Boolean
        fun isAlive(): Boolean
    }

    /** Does not create a workspace or modify any installed Ubuntu runtime. */
    @Synchronized
    fun start(
        plan: M8X11LaunchGate.LaunchPlan,
        nowMillis: Long,
        timeoutMillis: Long,
        host: NativeHost,
    ): Long {
        require(plan.nativeArguments == listOf(
            M8PrivateDisplayContract.GUEST_DISPLAY,
            "-nolisten", "tcp", "-auth", plan.authorityFile.absolutePath,
        )) { "Unexpected X11 launch arguments" }
        require(plan.socketPath.parentFile == java.io.File(plan.tempDirectory, ".X11-unix"))
        val token = lifecycle.begin(nowMillis, timeoutMillis)
        try {
            if (!host.start(plan)) lifecycle.crashed(token, "Native X11 startup rejected")
        } catch (error: Exception) {
            lifecycle.crashed(token, "Native X11 startup failed")
        }
        return token
    }

    @Synchronized
    fun verify(token: Long, nowMillis: Long, guestProbe: RootlessRuntimeResult, host: NativeHost): Boolean =
        readiness.verifyAndMarkReady(token, nowMillis, host.isAlive(), guestProbe)

    @Synchronized
    fun stop(token: Long, host: NativeHost): Boolean {
        lifecycle.requestStop(token)
        return try {
            if (host.stop()) {
                lifecycle.stopped(token)
                true
            } else {
                lifecycle.crashed(token, "Native X11 cleanup unconfirmed")
                false
            }
        } catch (error: Exception) {
            lifecycle.crashed(token, "Native X11 stop failed")
            false
        }
    }
}
