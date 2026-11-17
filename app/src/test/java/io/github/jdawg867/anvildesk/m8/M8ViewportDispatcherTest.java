package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;
import org.junit.Test;

public final class M8ViewportDispatcherTest {
    private static final class Fake implements M8ViewportDispatcher.Sink {
        int calls, width, height;
        boolean succeed = true;
        @Override public boolean apply(int w, int h) {
            calls++;
            width = w;
            height = h;
            return succeed;
        }
    }

    @Test public void sendsOnlyValidCurrentGenerationAndChanges() {
        M8ViewportState state = new M8ViewportState();
        Fake sink = new Fake();
        M8ViewportDispatcher dispatcher = new M8ViewportDispatcher(state, sink);
        assertFalse(dispatcher.dispatch(1));
        assertTrue(state.update(1, 1080, 2340));
        assertFalse(dispatcher.dispatch(2));
        assertTrue(dispatcher.dispatch(1));
        assertTrue(dispatcher.dispatch(1));
        assertEquals(1, sink.calls);
        assertTrue(state.update(1, 2340, 1080));
        assertTrue(dispatcher.dispatch(1));
        assertEquals(2, sink.calls);
        assertEquals(2340, sink.width);
        assertEquals(1080, sink.height);
        assertTrue(state.update(2, 1080, 2340));
        assertFalse(dispatcher.dispatch(1));
        assertTrue(dispatcher.dispatch(2));
        assertEquals(3, sink.calls);
    }

    @Test public void failedDeliveryIsRetried() {
        M8ViewportState state = new M8ViewportState();
        Fake sink = new Fake();
        M8ViewportDispatcher dispatcher = new M8ViewportDispatcher(state, sink);
        state.update(1, 500, 900);
        sink.succeed = false;
        assertFalse(dispatcher.dispatch(1));
        sink.succeed = true;
        assertTrue(dispatcher.dispatch(1));
        assertEquals(2, sink.calls);
        dispatcher.reset(1);
        assertTrue(dispatcher.dispatch(1));
        assertEquals(3, sink.calls);
    }
}
