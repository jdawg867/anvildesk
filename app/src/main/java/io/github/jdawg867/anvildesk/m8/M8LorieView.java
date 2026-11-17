package io.github.jdawg867.anvildesk.m8;

import android.view.Surface;

/**
 * Signature-only native rendering and input ABI for the pinned ARM64 X11 build.
 *
 * This class is intentionally NOT a SurfaceView and does not initialize native
 * rendering. JNI_OnLoad must never run until lifecycle and callback contracts,
 * Android Surface ownership, safe shutdown and native process protections pass.
 *
 * Methods here mirror the pinned upstream JNINativeMethod table.
 */
public final class M8LorieView {
    /* Package-private: only the reviewed M8 JNI boundary may instantiate it. */
    M8LorieView() {}

    public native long nativeInit();
    public native void nativeDestroy(long ptr);
    public native void surfaceChanged(long ptr, Surface surface);
    public native void setViewport(long ptr, int x, int y, int width, int height,
                                   int effectiveWidth, int effectiveHeight, int hidden);
    public native void setRendererZoom(long ptr, int percent);
    public native void setZoomAnchor(long ptr, float x, float y, float fractionX, float fractionY);
    public native void clearZoomAnchor(long ptr);
    public native void setFollowCursorPan(long ptr, boolean enabled);
    public native long getCursorPosition(long ptr);
    public native void sendSync(long ptr, int serial);
    public native void setFiltering(long ptr, int filtering);
    public native void connect(long ptr, int fd);
    public static native boolean connected(long ptr);
    public native void startLogcat(long ptr, int fd);
    public native void setClipboardSyncEnabled(long ptr, boolean enabled, boolean unused);
    public native void sendClipboardAnnounce(long ptr);
    public native void sendClipboardEvent(long ptr, byte[] text);
    public native void sendWindowChange(long ptr, int width, int height, int framerate, String name);
    public native void sendMouseEvent(long ptr, float x, float y, int button,
                                      boolean pressed, boolean relative);
    public native void sendTouchEvent(long ptr, int action, int id, int x, int y);
    public native void sendStylusEvent(long ptr, float x, float y, int pressure, int tiltX,
                                       int tiltY, int orientation, int buttons,
                                       boolean eraser, boolean mouse);
    public native void requestStylusEnabled(long ptr, boolean enabled);
    public native void sendLockKeysState(long ptr, int state);
    public native boolean sendKeyEvent(long ptr, int scanCode, int keyCode, boolean down);
    public native void sendTextEvent(long ptr, byte[] text);
    public native boolean requestConnection(long ptr);
    public static native long getLastInputTimestamp();
    public static native void markUserActivity();
}
