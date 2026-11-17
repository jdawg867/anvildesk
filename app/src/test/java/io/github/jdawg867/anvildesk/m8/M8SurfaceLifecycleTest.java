package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;

import android.view.Surface;
import org.junit.Test;
import org.mockito.Mockito;

/** Does not load X11 or touch device state. */
public final class M8SurfaceLifecycleTest {
    private static Surface validSurface() {
        Surface surface = Mockito.mock(Surface.class);
        Mockito.when(surface.isValid()).thenReturn(true);
        return surface;
    }

    @Test public void rejectsNullAndInvalidSurface() {
        M8SurfaceLifecycle lifecycle = new M8SurfaceLifecycle();
        assertEquals(-1, lifecycle.attach(null));
        Surface invalid = Mockito.mock(Surface.class);
        assertEquals(-1, lifecycle.attach(invalid));
        assertEquals(M8SurfaceLifecycle.State.DETACHED, lifecycle.state());
    }

    @Test public void rejectsDuplicateAttachAndStaleGeneration() {
        M8SurfaceLifecycle lifecycle = new M8SurfaceLifecycle();
        Surface a = validSurface();
        Surface b = validSurface();
        long first = lifecycle.attach(a);
        assertTrue(first > 0);
        assertEquals(-1, lifecycle.attach(b));
        assertTrue(lifecycle.isCurrent(first, a));
        assertFalse(lifecycle.isCurrent(first, b));
        assertFalse(lifecycle.isCurrent(first + 1, a));
        assertFalse(lifecycle.beginDetach(first + 1));
        assertTrue(lifecycle.beginDetach(first));
        assertFalse(lifecycle.isCurrent(first, a));
        assertEquals(-1, lifecycle.attach(b));
        assertFalse(lifecycle.finishDetach(first + 1));
        assertTrue(lifecycle.finishDetach(first));
        long second = lifecycle.attach(b);
        assertTrue(second > first);
        assertFalse(lifecycle.isCurrent(first, a));
        assertTrue(lifecycle.isCurrent(second, b));
    }

    @Test public void invalidatedSurfaceIsNeverCurrent() {
        M8SurfaceLifecycle lifecycle = new M8SurfaceLifecycle();
        Surface surface = validSurface();
        long token = lifecycle.attach(surface);
        Mockito.when(surface.isValid()).thenReturn(false);
        assertFalse(lifecycle.isCurrent(token, surface));
        assertTrue(lifecycle.beginDetach(token));
        assertTrue(lifecycle.finishDetach(token));
    }

    @Test public void finishRequiresBeginDetach() {
        M8SurfaceLifecycle lifecycle = new M8SurfaceLifecycle();
        Surface surface = validSurface();
        long token = lifecycle.attach(surface);
        assertFalse(lifecycle.finishDetach(token));
        assertTrue(lifecycle.beginDetach(token));
        assertFalse(lifecycle.beginDetach(token));
        assertTrue(lifecycle.finishDetach(token));
        assertFalse(lifecycle.finishDetach(token));
        assertEquals(M8SurfaceLifecycle.State.DETACHED, lifecycle.state());
    }
}
