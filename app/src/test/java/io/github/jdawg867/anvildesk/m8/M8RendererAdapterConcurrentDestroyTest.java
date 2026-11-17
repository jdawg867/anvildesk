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

/**
 * Java-only Surface/native-adapter concurrency regression with a fake bridge.
 * Does not execute JNI, Lorie, or Android's native callback dispatcher.
 */
public final class M8RendererAdapterConcurrentDestroyTest {
    private static final class BlockingBridge implements M8RendererAdapter.NativeBridge {
        final CountDownLatch destroyEntered = new CountDownLatch(1);
        final CountDownLatch finishDestroy = new CountDownLatch(1);
        final AtomicInteger destroyCalls = new AtomicInteger();
        volatile boolean failDestroy;

        @Override public long create() { return 431L; }
        @Override public void attach(long resource, Surface surface) {
            assertEquals(431L, resource);
        }
        @Override public void destroy(long resource) {
            assertEquals(431L, resource);
            destroyCalls.incrementAndGet();
            destroyEntered.countDown();
            try {
                if (!finishDestroy.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting for destroy release");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
            if (failDestroy) throw new IllegalStateException("injected native destroy failure");
        }
    }

    private static Surface surface() {
        Surface result = Mockito.mock(Surface.class);
        Mockito.when(result.isValid()).thenReturn(true);
        return result;
    }

    @Test public void staleSurfaceChecksCannotPassAfterConcurrentDestroy()
            throws Exception {
        BlockingBridge backend = new BlockingBridge();
        M8RendererAdapter adapter = new M8RendererAdapter(backend);
        Surface old = surface();
        long generation = adapter.attach(old);
        assertTrue(generation > 0);
        assertTrue(adapter.ownsResource(generation, old, 431L));
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<Boolean> destroy = executor.submit(() -> adapter.detach(generation));
            assertTrue("destroy did not begin",
                    backend.destroyEntered.await(5, TimeUnit.SECONDS));
            Future<Boolean> staleCurrent = executor.submit(
                    () -> adapter.isCurrent(generation, old));
            Future<Boolean> staleOwner = executor.submit(
                    () -> adapter.ownsResource(generation, old, 431L));
            backend.finishDestroy.countDown();
            assertTrue(destroy.get(5, TimeUnit.SECONDS));
            assertFalse(staleCurrent.get(5, TimeUnit.SECONDS));
            assertFalse(staleOwner.get(5, TimeUnit.SECONDS));
            assertEquals(1, backend.destroyCalls.get());
            assertEquals(M8SurfaceLifecycle.State.DETACHED, adapter.state());
            assertFalse(adapter.ownsResource(generation, old, 431L));
        } finally {
            backend.finishDestroy.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void failedDestroyPoisonsAdapterAndBlocksNewSurface()
            throws Exception {
        BlockingBridge backend = new BlockingBridge();
        backend.failDestroy = true;
        M8RendererAdapter adapter = new M8RendererAdapter(backend);
        Surface old = surface();
        long generation = adapter.attach(old);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> destruction = executor.submit(
                    () -> adapter.detach(generation));
            assertTrue(backend.destroyEntered.await(5, TimeUnit.SECONDS));
            backend.finishDestroy.countDown();
            try {
                destruction.get(5, TimeUnit.SECONDS);
                fail("expected injected destroy failure");
            } catch (java.util.concurrent.ExecutionException expected) {
                assertTrue(expected.getCause() instanceof IllegalStateException);
            }
            assertEquals(M8SurfaceLifecycle.State.STOPPING, adapter.state());
            assertFalse(adapter.isCurrent(generation, old));
            assertFalse(adapter.ownsResource(generation, old, 431L));
            assertEquals(-1L, adapter.attach(surface()));
            assertEquals(1, backend.destroyCalls.get());
        } finally {
            backend.finishDestroy.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
