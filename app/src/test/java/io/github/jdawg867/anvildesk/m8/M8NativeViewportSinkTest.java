package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;
import org.junit.Test;

public final class M8NativeViewportSinkTest {
    private static final class FakeCalls implements M8NativeViewportSink.NativeCalls {
        int calls;
        long pointer;
        int width, height, effectiveWidth, effectiveHeight;
        @Override public void setViewport(long ptr, int x, int y, int w, int h,
                                           int ew, int eh, int hidden) {
            calls++;
            pointer = ptr;
            assertEquals(0, x);
            assertEquals(0, y);
            assertEquals(0, hidden);
            width = w;
            height = h;
            effectiveWidth = ew;
            effectiveHeight = eh;
        }
    }
    @Test public void disabledByDefaultAndRevocable() {
        FakeCalls fake = new FakeCalls();
        M8NativeViewportSink sink = new M8NativeViewportSink(123, fake);
        assertFalse(sink.apply(1080, 2340));
        assertEquals(0, fake.calls);
        sink.setEnabled(true);
        assertTrue(sink.apply(1080, 2340));
        assertEquals(1, fake.calls);
        assertEquals(123, fake.pointer);
        assertEquals(fake.width, fake.effectiveWidth);
        assertEquals(fake.height, fake.effectiveHeight);
        sink.setEnabled(false);
        assertFalse(sink.apply(2340, 1080));
        assertEquals(1, fake.calls);
    }
    @Test public void rejectsInvalidGeometryAndResource() {
        FakeCalls fake = new FakeCalls();
        try {
            new M8NativeViewportSink(0, fake);
            fail("zero pointer accepted");
        } catch (IllegalArgumentException expected) {}
        M8NativeViewportSink sink = new M8NativeViewportSink(1, fake);
        sink.setEnabled(true);
        assertFalse(sink.apply(-1, 100));
        assertFalse(sink.apply(100, 0));
        assertFalse(sink.apply(16385, 100));
        assertEquals(0, fake.calls);
    }
}
