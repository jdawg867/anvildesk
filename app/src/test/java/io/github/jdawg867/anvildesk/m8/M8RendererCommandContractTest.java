package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;
import org.junit.Test;

/** Inert Java wire-shape tests; no JNI native method or renderer invocation. */
public final class M8RendererCommandContractTest {
    @Test public void acceptsOnlyVersionedPairedHandleGeneration() {
        assertTrue(M8RendererCommandContract.valid(1, 7L, 3L, 1, 800, 600));
        assertTrue(M8RendererCommandContract.valid(1, 7L, 3L, 2, 0, 0));
        assertFalse(M8RendererCommandContract.valid(2, 7L, 3L, 2, 0, 0));
        assertFalse(M8RendererCommandContract.valid(1, 0L, 3L, 2, 0, 0));
        assertFalse(M8RendererCommandContract.valid(1, -1L, 3L, 2, 0, 0));
        assertFalse(M8RendererCommandContract.valid(1, 7L, 0L, 2, 0, 0));
        assertFalse(M8RendererCommandContract.valid(1, 7L, -1L, 2, 0, 0));
    }
    @Test public void invalidCommandAndViewportRejected() {
        assertFalse(M8RendererCommandContract.valid(1, 7L, 3L, 9, 0, 0));
        assertFalse(M8RendererCommandContract.valid(1, 7L, 3L, 1, 0, 60));
        assertFalse(M8RendererCommandContract.valid(1, 7L, 3L, 1, 80, 0));
        assertFalse(M8RendererCommandContract.valid(1, 7L, 3L, 1, 16385, 60));
        assertFalse(M8RendererCommandContract.valid(1, 7L, 3L, 1, 80, 16385));
        assertFalse(M8RendererCommandContract.valid(1, 7L, 3L, 2, 80, 60));
    }
    @Test public void statusValuesArePinned() {
        assertEquals(0, M8RendererCommandContract.RESULT_APPLIED);
        assertEquals(1, M8RendererCommandContract.RESULT_STALE_HANDLE);
        assertEquals(2, M8RendererCommandContract.RESULT_INVALID_REQUEST);
        assertEquals(3, M8RendererCommandContract.RESULT_BACKEND_REJECTED);
    }
}
