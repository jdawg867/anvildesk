package io.github.jdawg867.anvildesk.m8;

/**
 * Preflight for a future native JNI load. Never loads native code itself.
 *
 * An explicit, reviewed integration owner must establish every precondition.
 * Packaging alone is not sufficient to authorize JNI_OnLoad.
 */
public final class M8NativeLoadGate {
    public enum Decision {
        DENIED,
        JNI_REGISTRY_UNVERIFIED,
        SHUTDOWN_UNVERIFIED,
        SERVER_ISOLATION_UNVERIFIED,
        APPROVED_FOR_REVIEWED_LOADER
    }

    public static final class Evidence {
        public final boolean packaged;
        public final boolean registryVerified;
        public final boolean shutdownVerified;
        public final boolean serverIsolationVerified;

        public Evidence(boolean packaged, boolean registryVerified,
                        boolean shutdownVerified, boolean serverIsolationVerified) {
            this.packaged = packaged;
            this.registryVerified = registryVerified;
            this.shutdownVerified = shutdownVerified;
            this.serverIsolationVerified = serverIsolationVerified;
        }
    }

    private M8NativeLoadGate() {}

    public static Decision evaluate(Evidence evidence) {
        if (evidence == null || !evidence.packaged) return Decision.DENIED;
        if (!evidence.registryVerified) return Decision.JNI_REGISTRY_UNVERIFIED;
        if (!evidence.shutdownVerified) return Decision.SHUTDOWN_UNVERIFIED;
        if (!evidence.serverIsolationVerified) return Decision.SERVER_ISOLATION_UNVERIFIED;
        return Decision.APPROVED_FOR_REVIEWED_LOADER;
    }
}
