package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import android.content.Context;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Emulator-only JNI_OnLoad failure injection, not a libXlorie test.
 * Native probe must be packaged in androidTest APK only.
 */
@RunWith(AndroidJUnit4.class)
public final class M8JniFailureProbeInstrumentedTest {
    @Test public void rejectedNativeRegistrationDoesNotKillInstrumentedProcess() {
        Context instrumentation = InstrumentationRegistry.getInstrumentation().getContext();
        File probe = new File(instrumentation.getApplicationInfo().nativeLibraryDir,
                "libm8jnifailureprobe.so");
        assertTrue("Test APK must contain the JNI failure probe: " + probe,
                probe.isFile());
        try {
            System.load(probe.getAbsolutePath());
            fail("An intentionally rejected JNI registration must prevent library loading");
        } catch (UnsatisfiedLinkError expected) {
            assertNotNull(expected.getMessage());
            assertTrue("Probe must be rejected by JNI_OnLoad, not merely absent: " + expected,
                    expected.getMessage().contains("JNI_ERR")
                            || expected.getMessage().contains("JNI_OnLoad"));
        }
        assertFalse(M8NativeLoadingPolicy.mayLoadLibrary(null));
        // Reaching this assertion means the instrumentation process survived.
        assertTrue(true);
    }
}
