package io.github.jdawg867.anvildesk.m8;

/** Android instrumentation-only native declaration; never in app/src/main. */
public final class M8ArtRegistrationTarget {
    private M8ArtRegistrationTarget() {}
    public static native int dispatch(int version, long handle, long generation,
                                      int command, int width, int height);
}
