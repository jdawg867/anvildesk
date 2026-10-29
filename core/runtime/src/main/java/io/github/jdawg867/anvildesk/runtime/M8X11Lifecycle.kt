package io.github.jdawg867.anvildesk.runtime

/**
 * M8 control-plane state machine. No JNI execution, socket creation, rootfs mutation,
 * or automatic startup occurs here. Observations must originate from the host transport
 * and an authenticated guest X11 handshake; a file existing is not a readiness signal.
 */
class M8X11Lifecycle {
    sealed interface State {
        data object Idle : State
        data class Starting(val token: Long, val deadlineMillis: Long) : State
        data class Ready(val token: Long) : State
        data class Stopping(val token: Long) : State
        data class Failed(val reason: String) : State
    }

    private var state: State = State.Idle
    private var nextToken = 0L

    @Synchronized fun snapshot(): State = state

    /** Caller must explicitly request a launch; the machine never autostarts. */
    @Synchronized fun begin(nowMillis: Long, timeoutMillis: Long): Long {
        require(timeoutMillis in 1..120_000) { "Invalid launch timeout" }
        check(state == State.Idle) { "M8 session already active or requires reset" }
        check(nowMillis <= Long.MAX_VALUE - timeoutMillis) { "Deadline overflow" }
        check(nextToken < Long.MAX_VALUE) { "Session token exhausted" }
        val token = ++nextToken
        state = State.Starting(token, nowMillis + timeoutMillis)
        return token
    }

    /** Authenticated X11 probe must pass before caller can report readiness. */
    @Synchronized fun authenticatedReady(token: Long, nowMillis: Long) {
        val current = state
        check(current is State.Starting && current.token == token) {
            "Readiness for inactive/stale session"
        }
        check(nowMillis <= current.deadlineMillis) { "Readiness arrived after timeout" }
        state = State.Ready(token)
    }

    @Synchronized fun expired(nowMillis: Long): Boolean {
        val current = state
        if (current is State.Starting && nowMillis > current.deadlineMillis) {
            state = State.Failed("X11 startup timed out")
            return true
        }
        return false
    }

    @Synchronized fun requestStop(token: Long) {
        val current = state
        check((current is State.Starting && current.token == token) ||
              (current is State.Ready && current.token == token)) { "Invalid stop request" }
        state = State.Stopping(token)
    }

    /** Caller confirms native process and private socket are no longer owned. */
    @Synchronized fun stopped(token: Long) {
        check(state == State.Stopping(token)) { "Stale shutdown confirmation" }
        state = State.Idle
    }

    @Synchronized fun crashed(token: Long, reason: String) {
        val current = state
        check(reason.isNotBlank())
        check((current is State.Starting && current.token == token) ||
              (current is State.Ready && current.token == token) ||
              (current is State.Stopping && current.token == token)) { "Stale crash event" }
        state = State.Failed(reason.take(256))
    }

    /** Recovery is explicit and permitted only after external resource cleanup. */
    @Synchronized fun resetAfterCleanup() {
        check(state is State.Failed) { "Only failed state can reset" }
        state = State.Idle
    }
}
