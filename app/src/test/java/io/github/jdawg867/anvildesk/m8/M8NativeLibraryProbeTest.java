package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;
import org.junit.Test;

public final class M8NativeLibraryProbeTest {
    @Test public void absentLibraryCannotBeAuthorized() {
        assertEquals(M8NativeLibraryProbe.Result.NOT_PACKAGED,
                M8NativeLibraryProbe.inspect(null));
        assertEquals(M8NativeLibraryProbe.Result.NOT_PACKAGED,
                M8NativeLibraryProbe.inspect(""));
        assertEquals(M8NativeLibraryProbe.Result.NOT_PACKAGED,
                M8NativeLibraryProbe.inspect("/nonexistent/anvildesk/m8"));
        assertFalse(M8NativeLibraryProbe.loadingAuthorized(
                M8NativeLibraryProbe.Result.NOT_PACKAGED));
    }
    @Test public void noProbeOutcomeAuthorizesNativeExecution() {
        for (M8NativeLibraryProbe.Result result : M8NativeLibraryProbe.Result.values()) {
            assertFalse(M8NativeLibraryProbe.loadingAuthorized(result));
        }
        assertFalse(M8NativeLibraryProbe.loadingAuthorized(null));
    }
}
