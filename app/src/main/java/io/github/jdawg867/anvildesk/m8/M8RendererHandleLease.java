package io.github.jdawg867.anvildesk.m8;

import java.util.Objects;

/**
 * Java-side ownership model for a future reviewed renderer boundary.
 *
 * This class does not allocate, load, call, or destroy native code. All tests
 * use synthetic handles. The native implementation still needs independent
 * synchronization and teardown review before this can guard a real pointer.
 *
 * A lease cannot escape its monitor through a returned handle: operations are
 * performed synchronously while holding the lease monitor. Callers must never
 * retain the handle supplied to an operation.
 */
final class M8RendererHandleLease {
    interface Operation<T> {
        T execute(long handle);
    }

    private final long handle;
    private boolean closed;
    private boolean busy;

    M8RendererHandleLease(long handle) {
        if (handle == 0L) throw new IllegalArgumentException("Null renderer handle");
        this.handle = handle;
    }

    synchronized <T> T withHandle(Operation<T> operation) {
        Objects.requireNonNull(operation, "operation");
        if (closed) throw new IllegalStateException("Renderer handle already closed");
        if (busy) throw new IllegalStateException("Reentrant renderer operation");
        busy = true;
        try {
            return operation.execute(handle);
        } finally {
            busy = false;
        }
    }

    /**
     * Performs the supplied destruction at most once. Failed destruction
     * remains terminal; automatically retrying a native free may double-free.
     */
    synchronized boolean close(Operation<?> destroy) {
        Objects.requireNonNull(destroy, "destroy");
        if (busy) throw new IllegalStateException("Cannot close during renderer operation");
        if (closed) return false;
        closed = true;
        busy = true;
        try {
            destroy.execute(handle);
        } finally {
            busy = false;
        }
        return true;
    }

    synchronized boolean isClosed() {
        return closed;
    }
}
