package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real Android package inspection, without loading libXlorie or starting X11. */
@RunWith(AndroidJUnit4.class)
public final class M8NativeLibraryProbeInstrumentedTest {
    @Test public void inspectInstalledLibraryDirectoryWithoutNativeExecution() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String path = context.getApplicationInfo().nativeLibraryDir;
        assertNotNull(path);
        M8NativeLibraryProbe.Result result = M8NativeLibraryProbe.inspect(path);
        // The x86_64 emulator test APK intentionally does not stage the
        // ARM64-only libXlorie.so. Packaging is validated separately in CI.
        assertEquals(M8NativeLibraryProbe.Result.NOT_PACKAGED, result);
        assertFalse(M8NativeLibraryProbe.loadingAuthorized(result));
    }
}
