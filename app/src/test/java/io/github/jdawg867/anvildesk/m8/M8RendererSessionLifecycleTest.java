package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;
import android.view.Surface;
import org.junit.Test;
import org.mockito.Mockito;

/** End-to-end fake-native lifecycle: no actual JNI or device operations. */
public final class M8RendererSessionLifecycleTest {
    private static Surface surface() {
        Surface s = Mockito.mock(Surface.class);
        Mockito.when(s.isValid()).thenReturn(true);
        return s;
    }

    @Test public void startupResizeShutdownAndRestartCannotReuseOldHandle() {
        final int[] created = {0};
        final int[] destroyed = {0};
        M8RendererAdapter renderer = new M8RendererAdapter(new M8RendererAdapter.NativeBridge() {
            @Override public long create() { return ++created[0] * 100L; }
            @Override public void attach(long handle, Surface s) {}
            @Override public void destroy(long handle) { destroyed[0]++; }
        });
        Surface firstSurface = surface();
        long firstGeneration = renderer.attach(firstSurface);
        M8NativeViewportSink firstSink = new M8NativeViewportSink(100L,
                (handle, x, y, w, h, ew, eh, hidden) -> assertEquals(100L, handle));
        M8RendererViewportSession first = new M8RendererViewportSession(
                renderer, new M8ViewportState(), firstSink);
        assertTrue(first.bind(firstGeneration, firstSurface));
        assertTrue(first.resize(1080, 2340));
        first.revoke();
        assertFalse(first.resize(2340, 1080));
        assertTrue(renderer.detach(firstGeneration));

        Surface secondSurface = surface();
        long secondGeneration = renderer.attach(secondSurface);
        assertTrue(secondGeneration > firstGeneration);
        assertFalse(first.bind(secondGeneration, secondSurface));
        assertFalse(renderer.ownsResource(secondGeneration, secondSurface, 100L));
        M8NativeViewportSink secondSink = new M8NativeViewportSink(200L,
                (handle, x, y, w, h, ew, eh, hidden) -> assertEquals(200L, handle));
        M8RendererViewportSession second = new M8RendererViewportSession(
                renderer, new M8ViewportState(), secondSink);
        assertTrue(second.bind(secondGeneration, secondSurface));
        assertTrue(second.resize(2340, 1080));
        second.revoke();
        assertTrue(renderer.detach(secondGeneration));
        assertEquals(2, destroyed[0]);
    }
}
