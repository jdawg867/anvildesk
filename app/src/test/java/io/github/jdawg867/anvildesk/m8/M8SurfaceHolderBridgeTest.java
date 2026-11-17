package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;
import android.view.Surface;
import android.view.SurfaceHolder;
import org.junit.Test;
import org.mockito.Mockito;

public final class M8SurfaceHolderBridgeTest {
    private static final class Native implements M8RendererAdapter.NativeBridge {
        int creates, destroys;
        @Override public long create() { creates++; return 97; }
        @Override public void attach(long resource, Surface surface) {}
        @Override public void destroy(long resource) { destroys++; }
    }

    private static SurfaceHolder holder() {
        Surface s = Mockito.mock(Surface.class);
        Mockito.when(s.isValid()).thenReturn(true);
        SurfaceHolder h = Mockito.mock(SurfaceHolder.class);
        Mockito.when(h.getSurface()).thenReturn(s);
        return h;
    }

    @Test public void createDestroyRecreate() {
        Native nativeBridge = new Native();
        SurfaceHolder holder = holder();
        M8SurfaceHolderBridge bridge = new M8SurfaceHolderBridge(
                holder, new M8RendererAdapter(nativeBridge));
        bridge.surfaceCreated(holder);
        assertTrue(bridge.isAttached());
        bridge.surfaceCreated(holder);
        assertEquals(1, nativeBridge.creates);
        bridge.surfaceChanged(holder, 0, 1080, 2340);
        bridge.surfaceDestroyed(holder);
        assertFalse(bridge.isAttached());
        assertEquals(1, nativeBridge.destroys);
        bridge.surfaceCreated(holder);
        assertTrue(bridge.isAttached());
        assertEquals(2, nativeBridge.creates);
        bridge.close();
        assertEquals(2, nativeBridge.destroys);
        bridge.surfaceCreated(holder);
        assertFalse(bridge.isAttached());
    }

    @Test public void foreignHolderCallbacksIgnored() {
        Native nativeBridge = new Native();
        SurfaceHolder holder = holder();
        SurfaceHolder foreign = holder();
        M8SurfaceHolderBridge bridge = new M8SurfaceHolderBridge(
                holder, new M8RendererAdapter(nativeBridge));
        bridge.surfaceCreated(foreign);
        assertEquals(0, nativeBridge.creates);
        bridge.surfaceCreated(holder);
        bridge.surfaceDestroyed(foreign);
        assertTrue(bridge.isAttached());
        assertEquals(0, nativeBridge.destroys);
        bridge.surfaceDestroyed(holder);
        assertEquals(1, nativeBridge.destroys);
    }

    @Test public void closeWithoutAttachIsTerminal() {
        Native nativeBridge = new Native();
        SurfaceHolder holder = holder();
        M8SurfaceHolderBridge bridge = new M8SurfaceHolderBridge(
                holder, new M8RendererAdapter(nativeBridge));
        bridge.close();
        bridge.surfaceCreated(holder);
        assertEquals(0, nativeBridge.creates);
    }
}
