package io.github.jdawg867.anvildesk.m8;

import android.view.Surface;

/**
 * Native JNI call boundary for the M8 renderer.
 *
 * A reviewed caller must provide a live, already-loaded JNI implementation.
 * This bridge never invokes System.loadLibrary and never starts the X11 server.
 * Construction is inert: native methods only run after explicit authorization.
 */
public final class M8LorieNativeRendererBridge
        implements M8RendererAdapter.NativeBridge, M8NativeViewportSink.NativeCalls {
    public interface JniCalls {
        long nativeInit();
        void surfaceChanged(long resource, Surface surface);
        void nativeDestroy(long resource);
        void setViewport(long resource, int x, int y, int width, int height,
                         int effectiveWidth, int effectiveHeight, int hidden);
    }

    private final JniCalls calls;
    private boolean authorized;
    private boolean terminal;
    private long activeResource;

    public M8LorieNativeRendererBridge(JniCalls calls) {
        if (calls == null) throw new IllegalArgumentException("JNI calls required");
        this.calls = calls;
    }

    /** Only a reviewed native-loading owner may call this after JNI validation. */
    public synchronized void authorize() {
        if (terminal || activeResource != 0) {
            throw new IllegalStateException("renderer bridge unavailable");
        }
        authorized = true;
    }

    public synchronized void revoke() {
        authorized = false;
    }

    @Override public synchronized long create() {
        if (!authorized || terminal || activeResource != 0) return 0;
        long created = calls.nativeInit();
        if (created <= 0) {
            terminal = true;
            return 0;
        }
        activeResource = created;
        return created;
    }

    @Override public synchronized void attach(long resource, Surface surface) {
        requireOwner(resource);
        if (surface == null || !surface.isValid()) {
            throw new IllegalArgumentException("valid Surface required");
        }
        calls.surfaceChanged(resource, surface);
    }

    @Override public synchronized void setViewport(long resource, int x, int y,
            int width, int height, int effectiveWidth, int effectiveHeight, int hidden) {
        requireOwner(resource);
        if (width <= 0 || height <= 0 || effectiveWidth <= 0 || effectiveHeight <= 0) {
            throw new IllegalArgumentException("invalid viewport");
        }
        calls.setViewport(resource, x, y, width, height, effectiveWidth, effectiveHeight, hidden);
    }

    @Override public synchronized void destroy(long resource) {
        // Cleanup is permitted after revoke, but only for the exact owned handle.
        if (terminal || resource <= 0 || resource != activeResource) {
            throw new IllegalStateException("renderer handle not owned");
        }
        authorized = false;
        terminal = true; // native failure must not permit reuse
        calls.nativeDestroy(resource);
        activeResource = 0;
    }

    private void requireOwner(long resource) {
        if (!authorized || terminal || resource <= 0 || resource != activeResource) {
            throw new IllegalStateException("renderer JNI access denied");
        }
    }
}
