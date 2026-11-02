package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;
import android.view.Surface;
import android.view.SurfaceHolder;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

public final class M8SurfaceCallbackRegistrationTest {
    private static final class FakeNative implements M8RendererAdapter.NativeBridge {
        int creates, destroys;
        @Override public long create() { creates++; return 5; }
        @Override public void attach(long pointer, Surface surface) {}
        @Override public void destroy(long pointer) { destroys++; }
    }

    @Test public void registersOnlyOnceAndClosesTerminally() {
        Surface surface = Mockito.mock(Surface.class);
        Mockito.when(surface.isValid()).thenReturn(true);
        SurfaceHolder holder = Mockito.mock(SurfaceHolder.class);
        Mockito.when(holder.getSurface()).thenReturn(surface);
        FakeNative nativeBridge = new FakeNative();

        M8SurfaceCallbackRegistration owner =
                new M8SurfaceCallbackRegistration(holder, new M8RendererAdapter(nativeBridge));
        ArgumentCaptor<SurfaceHolder.Callback> captor =
                ArgumentCaptor.forClass(SurfaceHolder.Callback.class);
        Mockito.verify(holder, Mockito.times(1)).addCallback(captor.capture());
        SurfaceHolder.Callback callback = captor.getValue();
        callback.surfaceCreated(holder);
        assertTrue(owner.isAttached());
        owner.close();
        assertFalse(owner.isAttached());
        assertEquals(1, nativeBridge.destroys);
        Mockito.verify(holder, Mockito.times(1)).removeCallback(callback);
        owner.close();
        Mockito.verify(holder, Mockito.times(1)).removeCallback(callback);
        callback.surfaceCreated(holder);
        assertEquals(1, nativeBridge.creates);
    }

    @Test public void rejectsMissingDependencies() {
        try {
            new M8SurfaceCallbackRegistration(null, new M8RendererAdapter(new FakeNative()));
            fail("expected rejection");
        } catch (IllegalArgumentException expected) {
            assertEquals("holder and renderer required", expected.getMessage());
        }
    }
}
