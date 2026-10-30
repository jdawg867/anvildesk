package io.github.jdawg867.anvildesk.m8;

import android.content.Context;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * Isolated M8 surface host, not installed into the production Activity.
 *
 * The caller must supply an explicitly authorized renderer adapter. This view
 * does not load native code, initiate X11, or access the Ubuntu root filesystem.
 */
public final class M8DisplaySurfaceView extends SurfaceView implements AutoCloseable {
    private final M8SurfaceHolderBridge callbacks;
    private boolean closed;

    public M8DisplaySurfaceView(Context context, M8RendererAdapter renderer) {
        super(context);
        if (renderer == null) {
            throw new IllegalArgumentException("renderer required");
        }
        SurfaceHolder holder = getHolder();
        callbacks = new M8SurfaceHolderBridge(holder, renderer);
        holder.addCallback(callbacks);
    }

    public synchronized boolean isRendererAttached() {
        return !closed && callbacks.isAttached();
    }

    @Override public synchronized void close() {
        if (closed) return;
        // Make the view terminal before detachment; an exception must not
        // permit new attachments or stale callback registration.
        closed = true;
        getHolder().removeCallback(callbacks);
        callbacks.close();
    }

    @Override protected void onDetachedFromWindow() {
        try {
            close();
        } finally {
            super.onDetachedFromWindow();
        }
    }
}
