package io.github.jdawg867.anvildesk.m8;

import android.view.Surface;

/**
 * Fail-closed, serialized renderer lifecycle. NativeBridge must only be supplied
 * after a separately reviewed native-loading gate authorizes JNI_OnLoad.
 *
 * Does not load libXlorie or start a server; caller owns the Surface object.
 */
public final class M8RendererAdapter {
    public interface NativeBridge {
        long create();
        void attach(long resource, Surface surface);
        void destroy(long resource);
    }

    private final NativeBridge bridge;
    private final M8SurfaceLifecycle lifecycle = new M8SurfaceLifecycle();
    private long resource;
    private long token;
    private boolean poisoned;
    private boolean operationActive;

    /** Java-only call boundary. A native bridge must independently lease and
     * validate BOTH values inside JNI; this monitor is not native memory safety.
     * Implementations must not retain the handle or reenter attach/detach.
     */
    interface GenerationOperation {
        void execute(long resource, long generation);
    }

    public M8RendererAdapter(NativeBridge bridge) {
        if (bridge == null) throw new IllegalArgumentException("bridge required");
        this.bridge = bridge;
    }

    public synchronized long attach(Surface surface) {
        if (poisoned || operationActive || resource != 0 || token != 0) return -1;
        long next = lifecycle.attach(surface);
        if (next <= 0) return -1;
        long created = 0;
        try {
            created = bridge.create();
            if (created <= 0) throw new IllegalStateException("invalid renderer resource");
            if (!lifecycle.isCurrent(next, surface)) {
                throw new IllegalStateException("surface invalidated during attach");
            }
            bridge.attach(created, surface);
            resource = created;
            token = next;
            return next;
        } catch (RuntimeException | Error failure) {
            if (created > 0) {
                try {
                    bridge.destroy(created);
                } catch (RuntimeException | Error cleanup) {
                    // Native resource ownership is uncertain; deny reuse forever.
                    poisoned = true;
                    failure.addSuppressed(cleanup);
                }
            }
            lifecycle.beginDetach(next);
            lifecycle.finishDetach(next);
            throw failure;
        }
    }

    public synchronized boolean isCurrent(long generation, Surface surface) {
        return resource > 0 && token == generation && lifecycle.isCurrent(generation, surface);
    }

    /** Verify the exact native renderer handle under the current Surface generation. */
    public synchronized boolean ownsResource(long generation, Surface candidate, long handle) {
        return !poisoned && handle > 0 && resource == handle
                && token == generation && lifecycle.isCurrent(generation, candidate);
    }

    /**
     * Delivers the current handle and generation together while serializing
     * Surface detach with Java operations. No real JNI implementation is used.
     * The eventual JNI entry MUST acquire the native registry lease before
     * dereferencing anything; callers may not store these numeric identifiers.
     */
    synchronized boolean withCurrentResource(long generation, Surface candidate,
                                              GenerationOperation operation) {
        if (operation == null) throw new IllegalArgumentException("operation required");
        if (operationActive || !ownsResource(generation, candidate, resource)) return false;
        operationActive = true;
        try {
            operation.execute(resource, generation);
            return true;
        } catch (RuntimeException | Error failure) {
            // An unknown native-style operation failure leaves ownership
            // uncertain. Block further attachment and operation admission.
            poisoned = true;
            lifecycle.beginDetach(generation);
            throw failure;
        } finally {
            operationActive = false;
        }
    }

    public synchronized boolean detach(long generation) {
        if (operationActive) throw new IllegalStateException("Cannot destroy during renderer operation");
        if (generation <= 0 || generation != token || resource <= 0) return false;
        if (!lifecycle.beginDetach(generation)) return false;
        // If native destroy fails, leave STOPPING and retain the resource token:
        // never permit a new attachment while cleanup is uncertain.
        try {
            bridge.destroy(resource);
        } catch (RuntimeException | Error failure) {
            poisoned = true;
            throw failure;
        }
        resource = 0;
        token = 0;
        return lifecycle.finishDetach(generation);
    }

    public synchronized M8SurfaceLifecycle.State state() {
        return lifecycle.state();
    }
}
