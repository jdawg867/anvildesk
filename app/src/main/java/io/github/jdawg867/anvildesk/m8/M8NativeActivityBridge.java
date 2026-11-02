package io.github.jdawg867.anvildesk.m8;

/**
 * JNI registration target for upstream activity-specific method.
 * No reflective Android hidden API calls are made by this Java adapter.
 * Native library loading remains disabled pending full bridge validation.
 */
public final class M8NativeActivityBridge {
    private M8NativeActivityBridge() {}

    public static native void nativeSetLaunchWindowingModeFreeform(Object options);
}
