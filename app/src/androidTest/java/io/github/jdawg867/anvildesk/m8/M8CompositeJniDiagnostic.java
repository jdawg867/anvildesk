package io.github.jdawg867.anvildesk.m8;

/**
 * JNI entry point of the isolated, test-only ARM64 composite looper diagnostic.
 * No static initializer: this class NEVER loads a native library by itself.
 * The caller must explicitly load the diagnostic from the instrumentation APK.
 * Never copy to app/src/main or invoke from the production application.
 */
public final class M8CompositeJniDiagnostic {
    private M8CompositeJniDiagnostic() {}
    public static native int runProbe();
}
