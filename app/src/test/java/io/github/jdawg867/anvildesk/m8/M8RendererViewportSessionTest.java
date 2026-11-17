package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;
import android.view.Surface;
import org.junit.Test;
import org.mockito.Mockito;

public final class M8RendererViewportSessionTest {
    private static class FakeBridge implements M8RendererAdapter.NativeBridge {
        @Override public long create() { return 42; }
        @Override public void attach(long ptr, Surface s) {}
        @Override public void destroy(long ptr) {}
    }
    private static class FakeViewport implements M8NativeViewportSink.NativeCalls {
        int calls;
        @Override public void setViewport(long ptr, int x, int y, int w, int h,
                                           int ew, int eh, int hidden) { calls++; }
    }
    private static Surface valid() {
        Surface s = Mockito.mock(Surface.class);
        Mockito.when(s.isValid()).thenReturn(true);
        return s;
    }
    @Test public void revocationBlocksDispatchAndStaleGeneration() {
        M8RendererAdapter renderer = new M8RendererAdapter(new FakeBridge());
        FakeViewport nativeCalls = new FakeViewport();
        M8NativeViewportSink sink = new M8NativeViewportSink(42, nativeCalls);
        M8RendererViewportSession session = new M8RendererViewportSession(
                renderer, new M8ViewportState(), sink);
        Surface surface = valid();
        long generation = renderer.attach(surface);
        assertTrue(session.bind(generation, surface));
        assertTrue(session.resize(800, 600));
        assertEquals(1, nativeCalls.calls);
        session.revoke();
        assertFalse(session.resize(900, 600));
        assertFalse(sink.apply(900, 600));
        assertEquals(1, nativeCalls.calls);
        assertTrue(renderer.detach(generation));
        assertFalse(session.bind(generation, surface));
    }
    @Test public void invalidatedSurfaceRevokesPermission() {
        M8RendererAdapter renderer = new M8RendererAdapter(new FakeBridge());
        FakeViewport nativeCalls = new FakeViewport();
        M8NativeViewportSink sink = new M8NativeViewportSink(42, nativeCalls);
        M8RendererViewportSession session = new M8RendererViewportSession(
                renderer, new M8ViewportState(), sink);
        Surface surface = valid();
        long generation = renderer.attach(surface);
        assertTrue(session.bind(generation, surface));
        Mockito.when(surface.isValid()).thenReturn(false);
        assertFalse(session.resize(800, 600));
        assertFalse(sink.apply(800, 600));
        assertEquals(0, nativeCalls.calls);
    }
}
