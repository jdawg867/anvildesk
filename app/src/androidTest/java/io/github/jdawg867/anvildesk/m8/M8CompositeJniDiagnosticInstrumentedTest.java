package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Isolated androidTest-only JNI callback probe. An explicit instrumentation
 * argument and an isolated diagnostic APK are required to execute this test.
 * Never load the diagnostic from production startup or a normal Android test.
 */
@RunWith(AndroidJUnit4.class)
public final class M8CompositeJniDiagnosticInstrumentedTest {
    @Test public void ownedLooperCallbackAttachesToRealJVMAndJoins() {
        assumeTrue("requires explicit M8 composite JNI diagnostic opt-in",
                "true".equals(InstrumentationRegistry.getArguments()
                        .getString("m8CompositeJniDiagnostic", "false")));
        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
        File binary = new File(testContext.getApplicationInfo().nativeLibraryDir,
                "libm8-composite-jni-diagnostic.so");
        assertTrue("Test APK must include isolated diagnostic library", binary.isFile());
        assertFalse(M8NativeLoadingPolicy.mayLoadLibrary(null));
        System.load(binary.getAbsolutePath()); // Explicitly scoped to test process.
        assertEquals("Composite JNI callback probe failure stage", 0,
                M8CompositeJniDiagnostic.runProbe());
        assertFalse(M8NativeLoadingPolicy.mayLoadLibrary(null));
    }
}
