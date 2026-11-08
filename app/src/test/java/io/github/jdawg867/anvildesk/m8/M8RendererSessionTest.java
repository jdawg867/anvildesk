package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** Synthetic-only; no JNI, native loading, Android graphics or file descriptors. */
public final class M8RendererSessionTest {
    private static class FakeBackend implements M8RendererSession.Backend {
        final List<String> events = new ArrayList<>();
        @Override public void onInput(long h, int event) { events.add("input:" + h + ":" + event); }
        @Override public void onDisconnect(long h) { events.add("disconnect:" + h); }
        @Override public void onDestroy(long h) { events.add("destroy:" + h); }
    }

    @Test public void inputDisconnectDestroyAreOrderedAndOneShot() {
        FakeBackend backend = new FakeBackend();
        M8RendererSession session = new M8RendererSession(51, backend);
        session.input(8);
        assertTrue(session.disconnect());
        assertFalse(session.disconnect());
        try {
            session.input(9);
            fail("Input after disconnect is invalid");
        } catch (IllegalStateException expected) {
            // Expected.
        }
        assertTrue(session.close());
        assertFalse(session.close());
        assertFalse(session.disconnect());
        assertEquals(Arrays.asList("input:51:8", "disconnect:51", "destroy:51"),
                backend.events);
    }

    @Test public void directCloseRejectsFutureEvents() {
        FakeBackend backend = new FakeBackend();
        M8RendererSession session = new M8RendererSession(7, backend);
        assertTrue(session.close());
        try {
            session.input(1);
            fail("Closed session must reject input");
        } catch (IllegalStateException expected) {
            // Expected.
        }
        assertFalse(session.disconnect());
        assertEquals(Arrays.asList("destroy:7"), backend.events);
    }

    @Test public void failedDisconnectIsTerminalForInputButCanStillClose() {
        FakeBackend backend = new FakeBackend() {
            @Override public void onDisconnect(long h) {
                super.onDisconnect(h);
                throw new IllegalStateException("synthetic disconnect failure");
            }
        };
        M8RendererSession session = new M8RendererSession(9, backend);
        try {
            session.disconnect();
            fail("Expected synthetic disconnect failure");
        } catch (IllegalStateException expected) {
            // Expected.
        }
        assertFalse(session.disconnect());
        assertTrue(session.close());
        assertEquals(Arrays.asList("disconnect:9", "destroy:9"), backend.events);
    }

    @Test public void reentrantCloseIsRejectedUntilInputReturns() {
        AtomicReference<M8RendererSession> holder = new AtomicReference<>();
        FakeBackend backend = new FakeBackend() {
            @Override public void onInput(long h, int event) {
                try {
                    holder.get().close();
                    fail("Reentrant close must not free active handle");
                } catch (IllegalStateException expected) {
                    // Expected.
                }
                super.onInput(h, event);
            }
        };
        M8RendererSession session = new M8RendererSession(10, backend);
        holder.set(session);
        session.input(2);
        assertTrue(session.close());
        assertEquals(Arrays.asList("input:10:2", "destroy:10"), backend.events);
    }

    @Test public void concurrentCloseWaitsForActiveInput() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        FakeBackend backend = new FakeBackend() {
            @Override public void onInput(long h, int event) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("timeout");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                super.onInput(h, event);
            }
        };
        M8RendererSession session = new M8RendererSession(11, backend);
        Thread user = new Thread(() -> {
            try { session.input(1); } catch (Throwable t) { error.compareAndSet(null, t); }
        });
        Thread closer = new Thread(() -> {
            try { session.close(); } catch (Throwable t) { error.compareAndSet(null, t); }
            finally { closed.countDown(); }
        });
        user.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        closer.start();
        try {
            assertFalse("Destroy raced with active input", closed.await(100, TimeUnit.MILLISECONDS));
        } finally {
            release.countDown();
            user.join(5000);
            closer.join(5000);
        }
        assertFalse(user.isAlive());
        assertFalse(closer.isAlive());
        assertNull(error.get());
        assertTrue(session.isClosed());
        assertEquals(Arrays.asList("input:11:1", "destroy:11"), backend.events);
    }
}
