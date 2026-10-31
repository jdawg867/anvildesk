package io.github.jdawg867.anvildesk.m8;

/**
 * Production-side native loading policy. This gate is intentionally sealed:
 * neither packaged artifacts nor caller-provided evidence flags can enable
 * native JNI loading until a reviewed, independently verified host exists.
 *
 * A future implementation must replace this class in a reviewed change
 * rather than quietly converting test evidence into production authorization.
 */
public final class M8NativeLoadingPolicy {
    public enum Status {
        NOT_AUTHORIZED
    }

    private M8NativeLoadingPolicy() {}

    public static Status evaluate(M8NativeLoadGate.Evidence evidence) {
        // M8NativeLoadGate is a diagnostic preflight, not an authority.
        return Status.NOT_AUTHORIZED;
    }

    public static boolean mayLoadLibrary(M8NativeLoadGate.Evidence evidence) {
        return false;
    }
}
