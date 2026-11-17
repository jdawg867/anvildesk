package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Inspects app packaging on Android; never loads libXlorie or starts X11. */
@RunWith(AndroidJUnit4.class)
public final class M8NativeLibraryProbeInstrumentedTest {
    @Test public void packagingMatchesIsolatedVariantWithoutNativeExecution() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String path = context.getApplicationInfo().nativeLibraryDir;
        assertNotNull(path);
        M8NativeLibraryProbe.Result result = M8NativeLibraryProbe.inspect(path);
        boolean spike = context.getPackageName().endsWith(".m8spike");
        if (spike) {
            // Only the dedicated ARM64 diagnostic packaging variant stages Xlorie.
            assertEquals(M8NativeLibraryProbe.Result.PACKAGED_UNVERIFIED, result);
        } else {
            // x86_64 CI emulator and normal debug builds never stage Xlorie.
            assertEquals(M8NativeLibraryProbe.Result.NOT_PACKAGED, result);
        }
        assertFalse(M8NativeLibraryProbe.loadingAuthorized(result));
    }
}
