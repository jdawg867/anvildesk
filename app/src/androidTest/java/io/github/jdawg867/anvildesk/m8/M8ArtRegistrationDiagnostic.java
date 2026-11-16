package io.github.jdawg867.anvildesk.m8;

/** Opt-in ART diagnostic, separate from renderer/production JNI. */
public final class M8ArtRegistrationDiagnostic {
    private M8ArtRegistrationDiagnostic() {}
    public static native int runProbe(Class<?> target);
}
