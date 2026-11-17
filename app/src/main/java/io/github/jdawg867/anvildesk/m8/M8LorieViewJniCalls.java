package io.github.jdawg867.anvildesk.m8;

import android.view.Surface;

/**
 * Exact Java forwarding boundary to the pinned M8LorieView native ABI.
 * Construction does not load libXlorie; the native-loading owner must complete
 * JNI registration and lifecycle review before providing this to a renderer.
 */
public final class M8LorieViewJniCalls implements M8LorieNativeRendererBridge.JniCalls {
    private final M8LorieView view;

    public M8LorieViewJniCalls(M8LorieView view) {
        if (view == null) throw new IllegalArgumentException("view required");
        this.view = view;
    }

    @Override public long nativeInit() {
        return view.nativeInit();
    }

    @Override public void surfaceChanged(long resource, Surface surface) {
        view.surfaceChanged(resource, surface);
    }

    @Override public void nativeDestroy(long resource) {
        view.nativeDestroy(resource);
    }

    @Override public void setViewport(long resource, int x, int y, int width,
            int height, int effectiveWidth, int effectiveHeight, int hidden) {
        view.setViewport(resource, x, y, width, height,
                effectiveWidth, effectiveHeight, hidden);
    }
}
