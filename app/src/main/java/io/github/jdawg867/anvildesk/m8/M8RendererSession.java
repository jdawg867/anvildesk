package io.github.jdawg867.anvildesk.m8;

import java.util.Objects;

/**
 * Non-native renderer session orchestrator for ownership review.
 *
 * This class never initializes, loads, or references libXlorie. The caller
 * supplies a handle and callbacks; production does not construct a session.
 * Only synthetic handles/backends are exercised by tests. A native-backed
 * version requires an independently reviewed JNI shutdown and callback gate.
 */
final class M8RendererSession {
    interface Backend {
        void onInput(long handle, int event);
        void onDisconnect(long handle);
        void onDestroy(long handle);
    }

    private final M8RendererHandleLease lease;
    private final Backend backend;
    private boolean disconnected;

    M8RendererSession(long syntheticHandle, Backend backend) {
        this.lease = new M8RendererHandleLease(syntheticHandle);
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    synchronized void input(int event) {
        if (disconnected) throw new IllegalStateException("Renderer disconnected");
        try {
            lease.withHandle(handle -> {
                backend.onInput(handle, event);
                return null;
            });
        } catch (RuntimeException | Error failure) {
            // The backend may have partly changed native state. Fail closed:
            // stop input, preserve the original failure, and still allow
            // exactly one explicit teardown through close().
            disconnected = true;
            throw failure;
        }
    }

    /**
     * Disconnect is one-shot and terminal for input. It does not free the
     * handle; close() performs destruction exactly once.
     */
    synchronized boolean disconnect() {
        if (lease.isClosed() || disconnected) return false;
        disconnected = true;
        lease.withHandle(handle -> {
            backend.onDisconnect(handle);
            return null;
        });
        return true;
    }

    synchronized boolean close() {
        disconnected = true;
        return lease.close(handle -> {
            backend.onDestroy(handle);
            return null;
        });
    }

    synchronized boolean isClosed() {
        return lease.isClosed();
    }
}
