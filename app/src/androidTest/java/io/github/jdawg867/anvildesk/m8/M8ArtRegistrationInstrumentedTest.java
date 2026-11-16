package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * ART-only test of rejected RegisterNatives and explicit recovery.
 * Requires isolated test package AND instrumentation argument. NEVER run by
 * normal app startup or a production build, and NEVER load libXlorie.
 */
@RunWith(AndroidJUnit4.class)
public final class M8ArtRegistrationInstrumentedTest {
    @Test public void rejectedRegistrationRecoversAndUnregisters() {
        assumeTrue("requires explicit ART diagnostic opt-in",
                "true".equals(InstrumentationRegistry.getArguments()
                        .getString("m8ArtRegistrationDiagnostic", "false")));
        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("io.github.jdawg867.anvildesk.m8artdiag",
                targetContext.getPackageName());
        assertEquals("io.github.jdawg867.anvildesk.m8artdiag.test",
                testContext.getPackageName());
        assertFalse(M8NativeLoadingPolicy.mayLoadLibrary(null));
        File nativeLibrary = new File(testContext.getApplicationInfo().nativeLibraryDir,
                "libm8-art-registration-diagnostic.so");
        assertTrue("ART diagnostic must exist in instrumentation APK: " + nativeLibrary,
                nativeLibrary.isFile());
        // Test-only explicit load after package/opt-in checks.
        System.load(nativeLibrary.getAbsolutePath());
        assertEquals("ART registration recovery failure stage", 0,
                M8ArtRegistrationDiagnostic.runProbe(M8ArtRegistrationTarget.class));
        assertFalse(M8NativeLoadingPolicy.mayLoadLibrary(null));
    }
}
