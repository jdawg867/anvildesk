package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;
import android.view.Surface;
import org.junit.Test;
import org.mockito.Mockito;

public final class M8LorieViewJniCallsTest {
    @Test public void constructorRejectsNullView() {
        try {
            new M8LorieViewJniCalls(null);
            fail("expected invalid view rejection");
        } catch (IllegalArgumentException expected) {
            assertEquals("view required", expected.getMessage());
        }
    }
    @Test public void constructorDoesNotInvokeNativeMethods() {
        M8LorieView mock = Mockito.mock(M8LorieView.class);
        new M8LorieViewJniCalls(mock);
        Mockito.verifyNoInteractions(mock);
    }
}
