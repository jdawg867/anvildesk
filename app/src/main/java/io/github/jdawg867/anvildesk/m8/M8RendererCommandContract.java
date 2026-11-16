package io.github.jdawg867.anvildesk.m8;

/**
 * Inert versioned command wire contract for a future independently reviewed
 * JNI entry point. This class has NO native methods and loads NO libraries.
 * The native side must validate handle + generation while holding a lease.
 */
final class M8RendererCommandContract {
    static final int VERSION = 1;
    static final int COMMAND_VIEWPORT = 1;
    static final int COMMAND_REFRESH = 2;
    static final int RESULT_APPLIED = 0;
    static final int RESULT_STALE_HANDLE = 1;
    static final int RESULT_INVALID_REQUEST = 2;
    static final int RESULT_BACKEND_REJECTED = 3;
    static final int MAX_VIEWPORT_DIMENSION = 16384;

    private M8RendererCommandContract() {}

    static boolean valid(int version, long handle, long generation,
                         int command, int width, int height) {
        if (version != VERSION || handle <= 0 || generation <= 0) return false;
        if (command == COMMAND_REFRESH) return width == 0 && height == 0;
        return command == COMMAND_VIEWPORT && width > 0 && height > 0
                && width <= MAX_VIEWPORT_DIMENSION
                && height <= MAX_VIEWPORT_DIMENSION;
    }
}
