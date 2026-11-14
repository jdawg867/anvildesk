package io.github.jdawg867.anvildesk.m8;

/**
 * Explicitly gated adapter for the pinned M8LorieView.setViewport(JIIIIIII)V ABI.
 *
 * It never loads libXlorie and intentionally has no default JNI implementation.
 * A future authorized native host must supply the resource handle and reviewed
 * invocation method after JNI_OnLoad and Surface lifecycle checks succeed.
 */
public final class M8NativeViewportSink implements M8ViewportDispatcher.Sink {
    public interface NativeCalls {
        void setViewport(long resource, int x, int y, int width, int height,
                         int effectiveWidth, int effectiveHeight, int hidden);
    }

    private final NativeCalls calls;
    private final long resource;
    private boolean enabled;

    public M8NativeViewportSink(long resource, NativeCalls calls) {
        if (resource <= 0 || calls == null) {
            throw new IllegalArgumentException("valid resource and native calls required");
        }
        this.resource = resource;
        this.calls = calls;
    }

    public long resourceHandle() {
        return resource;
    }

    /** A reviewed owner may explicitly authorize only an already-live renderer. */
    public synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override public synchronized boolean apply(int width, int height) {
        if (!enabled || width <= 0 || height <= 0 || width > 16384 || height > 16384) {
            return false;
        }
        // No cropping, hidden offset or inferred rotation: native viewport ABI
        // receives a simple full-surface geometry pending display acceptance.
        calls.setViewport(resource, 0, 0, width, height, width, height, 0);
        return true;
    }
}
