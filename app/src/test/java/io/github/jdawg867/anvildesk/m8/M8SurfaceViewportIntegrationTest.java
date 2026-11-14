package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;
import android.view.Surface;
import android.view.SurfaceHolder;
import org.junit.Test;
import org.mockito.Mockito;

public final class M8SurfaceViewportIntegrationTest {
    @Test public void resizeAndDestroyRevokeNativeViewport() {
        Surface surface = Mockito.mock(Surface.class);
        Mockito.when(surface.isValid()).thenReturn(true);
        SurfaceHolder holder = Mockito.mock(SurfaceHolder.class);
        Mockito.when(holder.getSurface()).thenReturn(surface);
        M8RendererAdapter renderer = new M8RendererAdapter(new M8RendererAdapter.NativeBridge() {
            @Override public long create() { return 73; }
            @Override public void attach(long pointer, Surface s) {}
            @Override public void destroy(long pointer) {}
        });
        final int[] calls = {0};
        M8NativeViewportSink sink = new M8NativeViewportSink(73,
                (ptr, x, y, width, height, ew, eh, hidden) -> {
                    calls[0]++;
                    assertEquals(73, ptr);
                    assertEquals(width, ew);
                    assertEquals(height, eh);
                });
        M8RendererViewportSession session = new M8RendererViewportSession(
                renderer, new M8ViewportState(), sink);
        M8SurfaceHolderBridge bridge = new M8SurfaceHolderBridge(holder, renderer, session);
        bridge.surfaceCreated(holder);
        bridge.surfaceChanged(holder, 0, 1080, 2340);
        bridge.surfaceChanged(holder, 0, 1080, 2340);
        bridge.surfaceChanged(holder, 0, 2340, 1080);
        assertEquals(2, calls[0]);
        bridge.surfaceDestroyed(holder);
        assertFalse(sink.apply(1080, 2340));
        assertEquals(2, calls[0]);
        bridge.close();
    }
}
