package io.github.jdawg867.anvildesk.m8;

import android.view.Surface;

/**
 * Couples viewport authorization to the live renderer generation.
 *
 * No native library is loaded here. Owner must authorize native calls only
 * after a separately reviewed native host starts and validates its JNI state.
 */
public final class M8RendererViewportSession {
    private final M8RendererAdapter renderer;
    private final M8ViewportState viewport;
    private final M8ViewportDispatcher dispatcher;
    private final M8NativeViewportSink sink;
    private long generation;
    private Surface surface;

    public M8RendererViewportSession(M8RendererAdapter renderer, M8ViewportState viewport,
                                     M8NativeViewportSink sink) {
        if (renderer == null || viewport == null || sink == null) {
            throw new IllegalArgumentException("renderer, viewport and sink required");
        }
        this.renderer = renderer;
        this.viewport = viewport;
        this.sink = sink;
        this.dispatcher = new M8ViewportDispatcher(viewport, sink);
    }

    public synchronized boolean bind(long token, Surface candidate) {
        if (generation != 0 || token <= 0 || !renderer.ownsResource(token, candidate, sink.resourceHandle())) {
            return false;
        }
        generation = token;
        surface = candidate;
        sink.setEnabled(true);
        return true;
    }

    public synchronized boolean resize(int width, int height) {
        if (generation == 0 || !renderer.ownsResource(generation, surface, sink.resourceHandle())) {
            sink.setEnabled(false);
            return false;
        }
        if (!viewport.update(generation, width, height)) return false;
        return dispatcher.dispatch(generation);
    }

    /** Revoke immediately, before native teardown; failed cleanup stays denied. */
    public synchronized void revoke() {
        sink.setEnabled(false);
        if (generation != 0) {
            viewport.clear(generation);
            dispatcher.reset(generation);
        }
        generation = 0;
        surface = null;
    }
}
