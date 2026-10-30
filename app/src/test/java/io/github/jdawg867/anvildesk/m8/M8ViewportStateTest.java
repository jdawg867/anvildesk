package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;
import org.junit.Test;

public final class M8ViewportStateTest {
    @Test public void rotationUpdatesOnlyCurrentGeneration() {
        M8ViewportState viewport = new M8ViewportState();
        assertTrue(viewport.update(1, 1080, 2340));
        assertEquals(1080, viewport.current(1).width);
        assertTrue(viewport.update(1, 2340, 1080));
        assertEquals(2340, viewport.current(1).width);
        assertTrue(viewport.update(2, 1080, 2340));
        assertFalse(viewport.update(1, 2340, 1080));
        viewport.clear(1);
        assertNotNull(viewport.current(2));
        viewport.clear(2);
        assertNull(viewport.current(2));
    }
    @Test public void rejectsInvalidDimensionsAndTokens() {
        M8ViewportState viewport = new M8ViewportState();
        assertFalse(viewport.update(0, 100, 200));
        assertFalse(viewport.update(1, -1, 200));
        assertFalse(viewport.update(1, 100, 0));
        assertFalse(viewport.update(1, 16385, 200));
        assertFalse(viewport.update(1, 100, 16385));
        assertNull(viewport.current(1));
    }
}
