package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Emulator-only JNI_OnLoad failure injection, not a libXlorie test.
 * Native probe must be packaged in androidTest APK only.
 */
@RunWith(AndroidJUnit4.class)
public final class M8JniFailureProbeInstrumentedTest {
    @Test public void rejectedNativeRegistrationDoesNotKillInstrumentedProcess() {
        try {
            System.loadLibrary("m8jnifailureprobe");
            fail("An intentionally rejected JNI registration must prevent library loading");
        } catch (UnsatisfiedLinkError expected) {
            assertNotNull(expected.getMessage());
        }
        assertFalse(M8NativeLoadingPolicy.mayLoadLibrary(null));
        // Reaching this assertion means the instrumentation process survived.
        assertTrue(true);
    }
}
