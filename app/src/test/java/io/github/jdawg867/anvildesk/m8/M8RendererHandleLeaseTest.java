package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Uses synthetic handles only; never loads the X11 JNI library. */
public final class M8RendererHandleLeaseTest {
    @Test public void nullHandleRejected() {
        try {
            new M8RendererHandleLease(0);
            fail("Null handle must be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    @Test public void exactlyOneDestroyAndNoFurtherUse() {
        M8RendererHandleLease lease = new M8RendererHandleLease(123);
        assertEquals(123L, (long) lease.withHandle(h -> h));
        AtomicInteger destroys = new AtomicInteger();
        assertTrue(lease.close(h -> { assertEquals(123L, h); destroys.incrementAndGet(); return null; }));
        assertFalse(lease.close(h -> { destroys.incrementAndGet(); return null; }));
        assertEquals(1, destroys.get());
        assertTrue(lease.isClosed());
        try {
            lease.withHandle(h -> h);
            fail("Closed handle must not be used");
        } catch (IllegalStateException expected) {
            // Expected.
        }
    }

    @Test public void failingOperationDoesNotPreventLaterClose() {
        M8RendererHandleLease lease = new M8RendererHandleLease(17);
        try {
            lease.withHandle(h -> { throw new IllegalArgumentException("synthetic"); });
            fail("Expected injected failure");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
        assertTrue(lease.close(h -> null));
    }

    @Test public void failureDuringDestroyNeverRetriesPointer() {
        M8RendererHandleLease lease = new M8RendererHandleLease(17);
        try {
            lease.close(h -> { throw new IllegalStateException("synthetic destroy failure"); });
            fail("Expected injected destroy failure");
        } catch (IllegalStateException expected) {
            // Expected.
        }
        assertTrue(lease.isClosed());
        assertFalse(lease.close(h -> { fail("Never destroy twice"); return null; }));
    }

    @Test public void reentrantUseAndCloseRejected() {
        M8RendererHandleLease lease = new M8RendererHandleLease(42);
        lease.withHandle(h -> {
            try {
                lease.withHandle(other -> other);
                fail("Reentrant access must be rejected");
            } catch (IllegalStateException expected) {
                // Expected.
            }
            try {
                lease.close(other -> null);
                fail("Reentrant close must be rejected");
            } catch (IllegalStateException expected) {
                // Expected.
            }
            return null;
        });
        assertTrue(lease.close(h -> null));
    }

    @Test public void closeWaitsUntilConcurrentUseFinishes() throws Exception {
        M8RendererHandleLease lease = new M8RendererHandleLease(42);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread user = new Thread(() -> {
            try {
                lease.withHandle(h -> {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out");
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(ex);
                    }
                    return null;
                });
            } catch (Throwable t) { error.compareAndSet(null, t); }
        });
        Thread closer = new Thread(() -> {
            try { lease.close(h -> null); } catch (Throwable t) { error.compareAndSet(null, t); }
            finally { closed.countDown(); }
        });
        user.start();
        assertTrue("Use never entered", entered.await(5, TimeUnit.SECONDS));
        closer.start();
        try {
            assertFalse("Close must wait for active operation",
                    closed.await(100, TimeUnit.MILLISECONDS));
        } finally {
            release.countDown();
            user.join(5000);
            closer.join(5000);
        }
        assertFalse(user.isAlive());
        assertFalse(closer.isAlive());
        assertNull(error.get());
        assertTrue(lease.isClosed());
    }
}
