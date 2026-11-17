package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;
import org.junit.Test;

public final class M8NativeLoadGateTest {
    @Test public void missingEvidenceNeverApprovesLoading() {
        assertEquals(M8NativeLoadGate.Decision.DENIED, M8NativeLoadGate.evaluate(null));
        assertEquals(M8NativeLoadGate.Decision.DENIED,
                M8NativeLoadGate.evaluate(new M8NativeLoadGate.Evidence(false, true, true, true)));
    }
    @Test public void eachMissingNativeGuaranteeBlocksLoading() {
        assertEquals(M8NativeLoadGate.Decision.JNI_REGISTRY_UNVERIFIED,
                M8NativeLoadGate.evaluate(new M8NativeLoadGate.Evidence(true, false, true, true)));
        assertEquals(M8NativeLoadGate.Decision.SHUTDOWN_UNVERIFIED,
                M8NativeLoadGate.evaluate(new M8NativeLoadGate.Evidence(true, true, false, true)));
        assertEquals(M8NativeLoadGate.Decision.SERVER_ISOLATION_UNVERIFIED,
                M8NativeLoadGate.evaluate(new M8NativeLoadGate.Evidence(true, true, true, false)));
    }
    @Test public void allEvidenceOnlyApprovesReviewedLoader() {
        assertEquals(M8NativeLoadGate.Decision.APPROVED_FOR_REVIEWED_LOADER,
                M8NativeLoadGate.evaluate(new M8NativeLoadGate.Evidence(true, true, true, true)));
    }
}
