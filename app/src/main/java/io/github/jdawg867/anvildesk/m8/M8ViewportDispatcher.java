package io.github.jdawg867.anvildesk.m8;

/**
 * Guarded viewport dispatch contract, independent of native library loading.
 * A future reviewed JNI backend may implement Sink; no native call occurs here.
 */
public final class M8ViewportDispatcher {
    public interface Sink {
        boolean apply(int width, int height);
    }

    private final M8ViewportState viewport;
    private final Sink sink;
    private long lastGeneration;
    private int lastWidth;
    private int lastHeight;

    public M8ViewportDispatcher(M8ViewportState viewport, Sink sink) {
        if (viewport == null || sink == null) {
            throw new IllegalArgumentException("viewport and sink required");
        }
        this.viewport = viewport;
        this.sink = sink;
    }

    /**
     * Dispatch only when caller validates the active renderer generation.
     * On any rejected/failed dispatch no dimensions are marked applied.
     */
    public synchronized boolean dispatch(long activeGeneration) {
        if (activeGeneration <= 0) return false;
        M8ViewportState.Snapshot current = viewport.current(activeGeneration);
        if (current == null) return false;
        if (lastGeneration == activeGeneration && lastWidth == current.width
                && lastHeight == current.height) return true;
        if (!sink.apply(current.width, current.height)) return false;
        lastGeneration = activeGeneration;
        lastWidth = current.width;
        lastHeight = current.height;
        return true;
    }

    public synchronized void reset(long generation) {
        if (lastGeneration == generation) {
            lastGeneration = 0;
            lastWidth = 0;
            lastHeight = 0;
        }
    }
}
