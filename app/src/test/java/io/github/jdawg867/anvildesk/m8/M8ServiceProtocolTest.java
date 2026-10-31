package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;
import org.junit.Test;

public final class M8ServiceProtocolTest {
    @Test public void rejectsUnverifiedAndWrongVersion() {
        assertEquals(M8ServiceProtocol.Decision.REJECT,
                M8ServiceProtocol.evaluate(2, M8ServiceProtocol.Operation.QUERY, 0, true));
        assertEquals(M8ServiceProtocol.Decision.REJECT,
                M8ServiceProtocol.evaluate(1, M8ServiceProtocol.Operation.QUERY, 0, false));
        assertEquals(M8ServiceProtocol.Decision.REJECT,
                M8ServiceProtocol.evaluate(1, null, 0, true));
    }

    @Test public void allowsReadOnlyQueryButNeverNativeStartOrStop() {
        assertEquals(M8ServiceProtocol.Decision.READ_ONLY,
                M8ServiceProtocol.evaluate(1, M8ServiceProtocol.Operation.QUERY, 0, true));
        assertEquals(M8ServiceProtocol.Decision.REJECT,
                M8ServiceProtocol.evaluate(1, M8ServiceProtocol.Operation.START, 0, true));
        assertEquals(M8ServiceProtocol.Decision.NOT_IMPLEMENTED,
                M8ServiceProtocol.evaluate(1, M8ServiceProtocol.Operation.START, 17, true));
        assertEquals(M8ServiceProtocol.Decision.NOT_IMPLEMENTED,
                M8ServiceProtocol.evaluate(1, M8ServiceProtocol.Operation.STOP, 17, true));
    }
}
