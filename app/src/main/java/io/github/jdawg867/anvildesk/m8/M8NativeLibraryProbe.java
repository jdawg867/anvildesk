package io.github.jdawg867.anvildesk.m8;

/**
 * Native library availability probe for a future separate-process experiment.
 * Intentionally never invokes System.loadLibrary or JNI_OnLoad.
 */
public final class M8NativeLibraryProbe {
    public enum Result { NOT_PACKAGED, PACKAGED_UNVERIFIED }
    private M8NativeLibraryProbe() {}

    public static Result inspect(String nativeLibraryDir) {
        if (nativeLibraryDir == null || nativeLibraryDir.isEmpty()) return Result.NOT_PACKAGED;
        java.io.File candidate = new java.io.File(nativeLibraryDir, "libXlorie.so");
        if (!candidate.isFile() || !candidate.canRead()) return Result.NOT_PACKAGED;
        return Result.PACKAGED_UNVERIFIED;
    }

    public static boolean loadingAuthorized(Result result) {
        return false;
    }
}
