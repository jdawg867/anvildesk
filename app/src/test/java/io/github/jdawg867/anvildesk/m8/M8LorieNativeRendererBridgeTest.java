package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;
import android.view.Surface;
import org.junit.Test;
import org.mockito.Mockito;

public final class M8LorieNativeRendererBridgeTest {
    private static final class Fake implements M8LorieNativeRendererBridge.JniCalls {
        int inits, attaches, viewports, destroys;
        boolean failInit, failDestroy;
        @Override public long nativeInit() {
            inits++;
            if (failInit) throw new IllegalStateException("init failed");
            return 47;
        }
        @Override public void surfaceChanged(long ptr, Surface s) { attaches++; }
        @Override public void nativeDestroy(long ptr) {
            destroys++;
            if (failDestroy) throw new IllegalStateException("destroy failed");
        }
        @Override public void setViewport(long ptr, int x, int y, int w, int h,
                                           int ew, int eh, int hidden) { viewports++; }
    }
    private static Surface surface() {
        Surface s = Mockito.mock(Surface.class);
        Mockito.when(s.isValid()).thenReturn(true);
        return s;
    }
    @Test public void disarmedBridgeRejectsAllNativeCalls() {
        Fake fake = new Fake();
        M8LorieNativeRendererBridge bridge = new M8LorieNativeRendererBridge(fake);
        assertEquals(0, bridge.create());
        assertEquals(0, fake.inits);
        try {
            bridge.attach(47, surface());
            fail("unowned JNI resource accepted");
        } catch (IllegalStateException expected) {}
        assertEquals(0, fake.attaches);
    }
    @Test public void onlyOwnedHandleCanRenderAndDestroy() {
        Fake fake = new Fake();
        M8LorieNativeRendererBridge bridge = new M8LorieNativeRendererBridge(fake);
        bridge.authorize();
        assertEquals(47, bridge.create());
        assertEquals(0, bridge.create());
        bridge.attach(47, surface());
        try {
            bridge.setViewport(48, 0, 0, 100, 200, 100, 200, 0);
            fail("foreign viewport resource accepted");
        } catch (IllegalStateException expected) {}
        bridge.setViewport(47, 0, 0, 100, 200, 100, 200, 0);
        assertEquals(1, fake.viewports);
        bridge.revoke();
        try {
            bridge.attach(47, surface());
            fail("revoked renderer accepted");
        } catch (IllegalStateException expected) {}
        bridge.destroy(47);
        assertEquals(1, fake.destroys);
        assertEquals(0, bridge.create());
    }
    @Test public void failedInitPoisonPreventsRetry() {
        Fake fake = new Fake();
        fake.failInit = true;
        M8LorieNativeRendererBridge bridge = new M8LorieNativeRendererBridge(fake);
        bridge.authorize();
        try {
            bridge.create();
            fail("init exception expected");
        } catch (IllegalStateException expected) {}
        assertEquals(0, bridge.create());
        assertEquals(1, fake.inits);
    }
    @Test public void failedDestroyNeverRetriesUnsafeCleanup() {
        Fake fake = new Fake();
        fake.failDestroy = true;
        M8LorieNativeRendererBridge bridge = new M8LorieNativeRendererBridge(fake);
        bridge.authorize();
        bridge.create();
        try {
            bridge.destroy(47);
            fail("destroy failure expected");
        } catch (IllegalStateException expected) {}
        assertEquals(0, bridge.create());
        assertEquals(1, fake.destroys);
    }
}
