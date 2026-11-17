package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;
import org.junit.Test;

public final class M8ServerShutdownGateTest {
    @Test public void joinedAndCleanedRequired() {
        M8ServerShutdownGate gate = new M8ServerShutdownGate();
        long token = gate.begin();
        assertTrue(token > 0);
        assertFalse(gate.recordJoinedAndCleaned(token, true, true));
        assertTrue(gate.markRunning(token));
        assertTrue(gate.requestStop(token));
        assertFalse(gate.recordJoinedAndCleaned(token, false, true));
        assertEquals(M8ServerShutdownGate.State.FAILED, gate.state());
        assertEquals(-1, gate.begin());
    }

    @Test public void completedShutdownIsTerminalPendingRestartReview() {
        M8ServerShutdownGate gate = new M8ServerShutdownGate();
        long token = gate.begin();
        assertFalse(gate.markRunning(token + 1));
        assertTrue(gate.markRunning(token));
        assertFalse(gate.requestStop(token + 1));
        assertTrue(gate.requestStop(token));
        assertTrue(gate.recordJoinedAndCleaned(token, true, true));
        assertEquals(M8ServerShutdownGate.State.CLEANED, gate.state());
        assertEquals(-1, gate.begin());
        gate.fail(token);
        assertEquals(M8ServerShutdownGate.State.CLEANED, gate.state());
    }

    @Test public void cleanupFailureBlocksReuse() {
        M8ServerShutdownGate gate = new M8ServerShutdownGate();
        long token = gate.begin();
        gate.markRunning(token);
        gate.requestStop(token);
        assertFalse(gate.recordJoinedAndCleaned(token, true, false));
        assertEquals(M8ServerShutdownGate.State.FAILED, gate.state());
    }
}
