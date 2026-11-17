package io.github.jdawg867.anvildesk.m8.diagnostic;

/**
 * Standalone JVM-only fixture. Not packaged in Android APK or shipped app.
 * Registered dynamically from a disposable C++ JNI Invocation API test.
 */
public final class M8HostRegisteredCommand {
    private M8HostRegisteredCommand() {}
    public static native int dispatch(int version, long handle, long generation,
                                      int command, int width, int height);
}
