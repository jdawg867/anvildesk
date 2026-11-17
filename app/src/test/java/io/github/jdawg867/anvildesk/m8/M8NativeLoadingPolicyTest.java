package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;
import org.junit.Test;

public final class M8NativeLoadingPolicyTest {
    @Test public void productionPolicyIgnoresUnverifiedCallerFlags() {
        for (int mask = 0; mask < 16; mask++) {
            M8NativeLoadGate.Evidence evidence = new M8NativeLoadGate.Evidence(
                    (mask & 1) != 0, (mask & 2) != 0,
                    (mask & 4) != 0, (mask & 8) != 0);
            assertEquals(M8NativeLoadingPolicy.Status.NOT_AUTHORIZED,
                    M8NativeLoadingPolicy.evaluate(evidence));
            assertFalse(M8NativeLoadingPolicy.mayLoadLibrary(evidence));
        }
        assertFalse(M8NativeLoadingPolicy.mayLoadLibrary(null));
    }
    @Test public void diagnosticPreflightApprovalIsNotProductionAuthorization() {
        M8NativeLoadGate.Evidence allTrue =
                new M8NativeLoadGate.Evidence(true, true, true, true);
        assertEquals(M8NativeLoadGate.Decision.APPROVED_FOR_REVIEWED_LOADER,
                M8NativeLoadGate.evaluate(allTrue));
        assertFalse(M8NativeLoadingPolicy.mayLoadLibrary(allTrue));
    }
}
