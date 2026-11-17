package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;

import android.view.Surface;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.mockito.Mockito;

/** Java-only contract tests. Numeric token handoff DOES NOT certify JNI. */
public final class M8RendererGenerationHandoffTest {
    private static final class Fake implements M8RendererAdapter.NativeBridge {
        final AtomicInteger destroyed = new AtomicInteger();
        @Override public long create() { return 719L; }
        @Override public void attach(long resource, Surface surface) {}
        @Override public void destroy(long resource) { destroyed.incrementAndGet(); }
    }

    private static Surface surface() {
        Surface s = Mockito.mock(Surface.class);
        Mockito.when(s.isValid()).thenReturn(true);
        return s;
    }

    @Test public void pairedGenerationAndHandleRejectStaleValues() {
        Fake fake = new Fake();
        M8RendererAdapter adapter = new M8RendererAdapter(fake);
        Surface first = surface();
        long generation = adapter.attach(first);
        AtomicInteger calls = new AtomicInteger();
        assertFalse(adapter.withCurrentResource(generation + 1, first, (h, g) -> calls.incrementAndGet()));
        assertFalse(adapter.withCurrentResource(generation, surface(), (h, g) -> calls.incrementAndGet()));
        assertTrue(adapter.withCurrentResource(generation, first, (h, g) -> {
            assertEquals(719L, h);
            assertEquals(generation, g);
            calls.incrementAndGet();
        }));
        assertEquals(1, calls.get());
        assertTrue(adapter.detach(generation));
        assertFalse(adapter.withCurrentResource(generation, first, (h, g) -> calls.incrementAndGet()));
        assertEquals(1, calls.get());
    }

    @Test public void detachBlocksUntilAdmittedOperationReturns() throws Exception {
        Fake fake = new Fake();
        M8RendererAdapter adapter = new M8RendererAdapter(fake);
        Surface first = surface();
        long generation = adapter.attach(first);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> work = executor.submit(() ->
                adapter.withCurrentResource(generation, first, (h, g) -> {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS))
                            throw new IllegalStateException("timed out");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Future<Boolean> destruction = executor.submit(() -> adapter.detach(generation));
            assertFalse(destruction.isDone());
            assertEquals(0, fake.destroyed.get());
            release.countDown();
            assertTrue(work.get(5, TimeUnit.SECONDS));
            assertTrue(destruction.get(5, TimeUnit.SECONDS));
            assertEquals(1, fake.destroyed.get());
            assertFalse(adapter.withCurrentResource(generation, first, (h, g) -> fail("stale")));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void reentrantDestroyRejectedAndUncertainOperationPoisoned() {
        Fake fake = new Fake();
        M8RendererAdapter adapter = new M8RendererAdapter(fake);
        Surface first = surface();
        long generation = adapter.attach(first);
        try {
            adapter.withCurrentResource(generation, first, (h, g) -> adapter.detach(g));
            fail("expected reentrant destruction rejection");
        } catch (IllegalStateException expected) {
            assertEquals(M8SurfaceLifecycle.State.STOPPING, adapter.state());
        }
        assertEquals(0, fake.destroyed.get());
        assertFalse(adapter.withCurrentResource(generation, first, (h, g) -> fail("poisoned")));
        assertEquals(-1L, adapter.attach(surface()));
    }

    @Test public void nullOperationRejected() {
        M8RendererAdapter adapter = new M8RendererAdapter(new Fake());
        try {
            adapter.withCurrentResource(1L, surface(), null);
            fail("expected invalid operation");
        } catch (IllegalArgumentException expected) {
            assertEquals(M8SurfaceLifecycle.State.DETACHED, adapter.state());
        }
    }
}
