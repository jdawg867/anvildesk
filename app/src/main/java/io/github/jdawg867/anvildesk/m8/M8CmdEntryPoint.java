package io.github.jdawg867.anvildesk.m8;

import android.os.ParcelFileDescriptor;

/**
 * Signature-only JNI contract for the pinned X11 native registry.
 * Never construct this class or call its methods until the full native
 * lifecycle, rendering bridge and process-isolation review is complete.
 */
public final class M8CmdEntryPoint {
    private M8CmdEntryPoint() {}

    public native boolean start(String[] arguments);
    public native ParcelFileDescriptor getXConnection();
    public native ParcelFileDescriptor getLogcatOutput();
    public native void reportFatalError(String message);
    private static native boolean connected();

    // Native registration currently requires these instance callbacks.
    // Discovery/broadcasts are intentionally not implemented.
    @SuppressWarnings("unused")
    private void sendBroadcast() {
        throw new IllegalStateException("M8 native discovery not implemented");
    }

    @SuppressWarnings("unused")
    private void sendBroadcastDelayed() {
        throw new IllegalStateException("M8 native discovery not implemented");
    }

    @SuppressWarnings("unused")
    private void setDocumentTag(String tag) {
        throw new IllegalStateException("M8 native document tags not implemented");
    }
}
