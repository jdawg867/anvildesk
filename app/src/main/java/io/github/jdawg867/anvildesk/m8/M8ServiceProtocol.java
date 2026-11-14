package io.github.jdawg867.anvildesk.m8;

/**
 * Versioned protocol definition for a future app-private M8 X11 service.
 * Pure validation only: not an Android Binder implementation and not authority
 * to start or load the native X11 server.
 */
public final class M8ServiceProtocol {
    public static final int VERSION = 1;
    public enum Operation { QUERY, START, STOP }
    public enum Decision { REJECT, READ_ONLY, NOT_IMPLEMENTED }

    private M8ServiceProtocol() {}

    public static Decision evaluate(int version, Operation operation, long session,
                                    boolean callerVerified) {
        if (version != VERSION || operation == null || !callerVerified) {
            return Decision.REJECT;
        }
        if (operation == Operation.QUERY) return Decision.READ_ONLY;
        if (session <= 0) return Decision.REJECT;
        // START and STOP remain unavailable until isolated process ownership,
        // authenticated transport and shutdown are implemented and reviewed.
        return Decision.NOT_IMPLEMENTED;
    }
}
