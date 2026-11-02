package io.github.jdawg867.anvildesk.m8;

import android.view.SurfaceHolder;

/**
 * Owns one registration of M8 callbacks to a SurfaceHolder.
 * Close is terminal and unregisters before attempting native cleanup.
 */
public final class M8SurfaceCallbackRegistration implements AutoCloseable {
    private final SurfaceHolder holder;
    private final M8SurfaceHolderBridge callback;
    private boolean closed;

    public M8SurfaceCallbackRegistration(SurfaceHolder holder, M8RendererAdapter renderer) {
        if (holder == null || renderer == null) {
            throw new IllegalArgumentException("holder and renderer required");
        }
        this.holder = holder;
        this.callback = new M8SurfaceHolderBridge(holder, renderer);
        holder.addCallback(callback);
    }

    public synchronized boolean isAttached() {
        return !closed && callback.isAttached();
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        holder.removeCallback(callback);
        callback.close();
    }
}
