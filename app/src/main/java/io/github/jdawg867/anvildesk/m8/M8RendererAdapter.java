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

    public M8RendererAdapter(NativeBridge bridge) {
        if (bridge == null) throw new IllegalArgumentException("bridge required");
        this.bridge = bridge;
    }

    public synchronized long attach(Surface surface) {
        if (resource != 0 || token != 0) return -1;
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

    public synchronized boolean detach(long generation) {
        if (generation <= 0 || generation != token || resource <= 0) return false;
        if (!lifecycle.beginDetach(generation)) return false;
        // If native destroy fails, leave STOPPING and retain the resource token:
        // never permit a new attachment while cleanup is uncertain.
        bridge.destroy(resource);
        resource = 0;
        token = 0;
        return lifecycle.finishDetach(generation);
    }

    public synchronized M8SurfaceLifecycle.State state() {
        return lifecycle.state();
    }
}
