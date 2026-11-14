package io.github.jdawg867.anvildesk.m8;

/**
 * Tracks evidence of a joined native X11 server thread.
 * This class never calls native code and cannot stop Xorg itself.
 * A timeout or failed join is terminal, forbidding in-process restart.
 */
public final class M8ServerShutdownGate {
    public enum State { IDLE, STARTING, RUNNING, STOP_REQUESTED, CLEANED, FAILED }
    private State state = State.IDLE;
    private long generation;

    public synchronized long begin() {
        if (state != State.IDLE || generation == Long.MAX_VALUE) return -1;
        state = State.STARTING;
        return ++generation;
    }

    public synchronized boolean markRunning(long token) {
        if (state != State.STARTING || token != generation) return false;
        state = State.RUNNING;
        return true;
    }

    public synchronized boolean requestStop(long token) {
        if (state != State.RUNNING || token != generation) return false;
        state = State.STOP_REQUESTED;
        return true;
    }

    public synchronized boolean recordJoinedAndCleaned(long token, boolean threadJoined,
                                                        boolean resourcesCleaned) {
        if (state != State.STOP_REQUESTED || token != generation) return false;
        if (!threadJoined || !resourcesCleaned) {
            state = State.FAILED;
            return false;
        }
        state = State.CLEANED;
        return true;
    }

    public synchronized void fail(long token) {
        if (token == generation && state != State.CLEANED) state = State.FAILED;
    }

    public synchronized State state() { return state; }
}
