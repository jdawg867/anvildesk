package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;
import android.view.Surface;
import org.junit.Test;
import org.mockito.Mockito;

public final class M8RendererCleanupFailureTest {
    private static Surface surface() {
        Surface s = Mockito.mock(Surface.class);
        Mockito.when(s.isValid()).thenReturn(true);
        return s;
    }
    @Test public void failedInitCleanupPermanentlyBlocksReuse() {
        final int[] creates = {0};
        M8RendererAdapter adapter = new M8RendererAdapter(new M8RendererAdapter.NativeBridge() {
            @Override public long create() { creates[0]++; return 99; }
            @Override public void attach(long ptr, Surface s) {
                throw new IllegalStateException("native attach failed");
            }
            @Override public void destroy(long ptr) {
                throw new IllegalStateException("native cleanup failed");
            }
        });
        try {
            adapter.attach(surface());
            fail("expected attach exception");
        } catch (IllegalStateException expected) {
            assertEquals(1, expected.getSuppressed().length);
        }
        assertEquals(-1, adapter.attach(surface()));
        assertEquals(1, creates[0]);
    }
    @Test public void nativeDestroyFailureRetainsStoppingState() {
        M8RendererAdapter adapter = new M8RendererAdapter(new M8RendererAdapter.NativeBridge() {
            @Override public long create() { return 99; }
            @Override public void attach(long ptr, Surface s) {}
            @Override public void destroy(long ptr) {
                throw new IllegalStateException("native destroy failed");
            }
        });
        long token = adapter.attach(surface());
        try {
            adapter.detach(token);
            fail("expected destroy exception");
        } catch (IllegalStateException expected) {
            assertEquals(M8SurfaceLifecycle.State.STOPPING, adapter.state());
            assertEquals(-1, adapter.attach(surface()));
        }
    }
}
