package io.github.jdawg867.anvildesk.m8;

import android.view.Surface;
import android.view.SurfaceHolder;

/**
 * SurfaceHolder events for a single, explicitly registered holder.
 * Native rendering remains disabled until a reviewed NativeBridge is provided.
 *
 * All callbacks and shutdown are serialized. A destroy failure leaves the
 * adapter STOPPING and prevents accidental reuse of native resources.
 */
public final class M8SurfaceHolderBridge implements SurfaceHolder.Callback {
    private final SurfaceHolder holder;
    private final M8RendererAdapter renderer;
    private long generation;
    private Surface attachedSurface;
    private boolean closed;
    private final M8ViewportState viewport = new M8ViewportState();

    public M8SurfaceHolderBridge(SurfaceHolder holder, M8RendererAdapter renderer) {
        if (holder == null || renderer == null) {
            throw new IllegalArgumentException("holder and renderer required");
        }
        this.holder = holder;
        this.renderer = renderer;
    }

    @Override public synchronized void surfaceCreated(SurfaceHolder callbackHolder) {
        if (closed || callbackHolder != holder || generation != 0) return;
        Surface candidate = callbackHolder.getSurface();
        long next = renderer.attach(candidate);
        if (next > 0) {
            generation = next;
            attachedSurface = candidate;
        }
    }

    @Override public synchronized void surfaceChanged(
            SurfaceHolder callbackHolder, int format, int width, int height) {
        if (closed || callbackHolder != holder || generation == 0 || width <= 0 || height <= 0) {
            return;
        }
        // Rotation/resize updates metadata only; JNI viewport changes stay gated.
        if (!renderer.isCurrent(generation, attachedSurface)) return;
        viewport.update(generation, width, height);
    }

    @Override public synchronized void surfaceDestroyed(SurfaceHolder callbackHolder) {
        if (callbackHolder != holder || generation == 0) return;
        long previous = generation;
        // Preserve state when native cleanup throws. No subsequent attach allowed.
        if (renderer.detach(previous)) {
            viewport.clear(previous);
            generation = 0;
            attachedSurface = null;
        }
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (generation != 0 && renderer.detach(generation)) {
            viewport.clear(generation);
            generation = 0;
            attachedSurface = null;
        }
    }

    public synchronized M8ViewportState.Snapshot viewport() {
        return !closed && renderer.isCurrent(generation, attachedSurface)
                ? viewport.current(generation) : null;
    }

    public synchronized boolean isAttached() {
        return !closed && generation != 0 && renderer.isCurrent(generation, attachedSurface);
    }
}
