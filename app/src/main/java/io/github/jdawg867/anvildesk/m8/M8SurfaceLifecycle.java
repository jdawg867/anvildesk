package io.github.jdawg867.anvildesk.m8;

import android.view.Surface;

/**
 * Thread-confined, fail-closed Android Surface ownership state machine.
 *
 * No native methods are called here. A future renderer adapter must hold the
 * generation token, validate it before use, and explicitly release its native
 * resources before reporting detachment.
 */
public final class M8SurfaceLifecycle {
    public enum State { DETACHED, ATTACHED, STOPPING }

    private State state = State.DETACHED;
    private long generation = 0;
    private Surface surface;

    public synchronized long attach(Surface candidate) {
        if (candidate == null || !candidate.isValid() || state != State.DETACHED) {
            return -1;
        }
        if (generation == Long.MAX_VALUE) {
            return -1;
        }
        generation++;
        surface = candidate;
        state = State.ATTACHED;
        return generation;
    }

    public synchronized boolean isCurrent(long token, Surface candidate) {
        return token > 0 && state == State.ATTACHED && generation == token
                && surface == candidate && candidate != null && candidate.isValid();
    }

    public synchronized boolean beginDetach(long token) {
        if (state != State.ATTACHED || token <= 0 || generation != token) {
            return false;
        }
        state = State.STOPPING;
        return true;
    }

    public synchronized boolean finishDetach(long token) {
        if (state != State.STOPPING || token <= 0 || generation != token) {
            return false;
        }
        surface = null;
        state = State.DETACHED;
        return true;
    }

    public synchronized State state() {
        return state;
    }
}
