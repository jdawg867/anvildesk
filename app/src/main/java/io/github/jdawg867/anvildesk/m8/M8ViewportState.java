package io.github.jdawg867.anvildesk.m8;

/**
 * Generation-scoped Android viewport metadata. Does not invoke JNI.
 * Display rotation arrives as a new width/height pair from SurfaceHolder.
 */
public final class M8ViewportState {
    public static final class Snapshot {
        public final long generation;
        public final int width;
        public final int height;
        Snapshot(long generation, int width, int height) {
            this.generation = generation;
            this.width = width;
            this.height = height;
        }
    }

    private Snapshot current;

    public synchronized boolean update(long generation, int width, int height) {
        if (generation <= 0 || width <= 0 || height <= 0
                || width > 16384 || height > 16384) {
            return false;
        }
        if (current != null && generation < current.generation) return false;
        current = new Snapshot(generation, width, height);
        return true;
    }

    public synchronized Snapshot current(long generation) {
        return current != null && generation == current.generation ? current : null;
    }

    public synchronized void clear(long generation) {
        if (current != null && current.generation == generation) current = null;
    }
}
