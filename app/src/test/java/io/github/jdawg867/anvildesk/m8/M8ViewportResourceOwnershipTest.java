package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;
import android.view.Surface;
import org.junit.Test;
import org.mockito.Mockito;

public final class M8ViewportResourceOwnershipTest {
    @Test public void unrelatedNativeResourceCannotReceiveViewportCalls() {
        Surface surface = Mockito.mock(Surface.class);
        Mockito.when(surface.isValid()).thenReturn(true);
        M8RendererAdapter renderer = new M8RendererAdapter(new M8RendererAdapter.NativeBridge() {
            @Override public long create() { return 73; }
            @Override public void attach(long ptr, Surface s) {}
            @Override public void destroy(long ptr) {}
        });
        long token = renderer.attach(surface);
        assertTrue(renderer.ownsResource(token, surface, 73));
        assertFalse(renderer.ownsResource(token, surface, 74));
        final int[] calls = {0};
        M8NativeViewportSink wrong = new M8NativeViewportSink(74,
                (ptr, x, y, w, h, ew, eh, hidden) -> calls[0]++);
        M8RendererViewportSession session = new M8RendererViewportSession(
                renderer, new M8ViewportState(), wrong);
        assertFalse(session.bind(token, surface));
        assertFalse(session.resize(1080, 2340));
        assertEquals(0, calls[0]);
        assertTrue(renderer.detach(token));
        assertFalse(renderer.ownsResource(token, surface, 73));
    }
}
