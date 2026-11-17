package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public final class M8ServiceStatusClientTest {
    @Test public void absentConnectionIsUnavailable() {
        assertEquals(M8ServiceStatusClient.State.UNAVAILABLE,
                M8ServiceStatusClient.query(null));
    }
}
