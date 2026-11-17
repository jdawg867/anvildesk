package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;
import android.view.Surface;
import org.junit.Test;
import org.mockito.Mockito;

public final class M8RendererAdapterTest {
    private static final class Fake implements M8RendererAdapter.NativeBridge {
        int creates, destroys;
        boolean failDestroy, failAttach;
        @Override public long create() { creates++; return 71; }
        @Override public void attach(long ptr, Surface s) {
            if (failAttach) throw new IllegalStateException("attach failed");
        }
        @Override public void destroy(long ptr) {
            destroys++;
            if (failDestroy) throw new IllegalStateException("destroy failed");
        }
    }
    private static Surface validSurface() {
        Surface s = Mockito.mock(Surface.class);
        Mockito.when(s.isValid()).thenReturn(true);
        return s;
    }
    @Test public void attachDetachAndStaleToken() {
        Fake fake = new Fake();
        M8RendererAdapter adapter = new M8RendererAdapter(fake);
        Surface s = validSurface();
        long token = adapter.attach(s);
        assertTrue(token > 0);
        assertTrue(adapter.isCurrent(token, s));
        assertEquals(-1, adapter.attach(validSurface()));
        assertFalse(adapter.detach(token + 1));
        assertTrue(adapter.detach(token));
        assertFalse(adapter.isCurrent(token, s));
        long later = adapter.attach(validSurface());
        assertTrue(later > token);
        assertEquals(2, fake.creates);
        assertEquals(1, fake.destroys);
    }
    @Test public void failedAttachCleansUp() {
        Fake fake = new Fake();
        fake.failAttach = true;
        M8RendererAdapter adapter = new M8RendererAdapter(fake);
        try {
            adapter.attach(validSurface());
            fail("expected failure");
        } catch (IllegalStateException expected) {
            assertEquals(1, fake.destroys);
            assertEquals(M8SurfaceLifecycle.State.DETACHED, adapter.state());
        }
    }
    @Test public void failedDestroyBlocksFurtherAttach() {
        Fake fake = new Fake();
        M8RendererAdapter adapter = new M8RendererAdapter(fake);
        long token = adapter.attach(validSurface());
        fake.failDestroy = true;
        try {
            adapter.detach(token);
            fail("expected failure");
        } catch (IllegalStateException expected) {
            assertEquals(M8SurfaceLifecycle.State.STOPPING, adapter.state());
            assertEquals(-1, adapter.attach(validSurface()));
        }
    }
    @Test public void invalidSurfaceCannotCreate() {
        Fake fake = new Fake();
        assertEquals(-1, new M8RendererAdapter(fake).attach(null));
        assertEquals(0, fake.creates);
    }
}
