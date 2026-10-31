package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Emulator-only cross-process smoke test. Temporarily enables the disabled
 * non-exported component in the test app, then unbinds and disables it in
 * finally. No native code, X11, or Ubuntu runtime is started.
 */
@RunWith(AndroidJUnit4.class)
public final class M8CrossProcessBinderInstrumentedTest {
    @Test public void bindQueryUnbindWithoutNativeStartup() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PackageManager pm = context.getPackageManager();
        ComponentName name = new ComponentName(context, M8X11ProcessService.class);
        CountDownLatch connected = new CountDownLatch(1);
        final IBinder[] endpoint = {null};
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName component, IBinder binder) {
                endpoint[0] = binder;
                connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName component) {
                endpoint[0] = null;
            }
        };
        boolean bound = false;
        try {
            pm.setComponentEnabledSetting(name,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            Intent intent = new Intent(context, M8X11ProcessService.class);
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            assertTrue("same-app secondary service bind failed", bound);
            assertTrue("secondary service did not connect",
                    connected.await(20, TimeUnit.SECONDS));
            assertNotNull(endpoint[0]);
            assertFalse("expected a Binder proxy from :m8x11",
                    endpoint[0] instanceof android.os.Binder);
            assertEquals(M8ServiceStatusClient.State.STOPPED,
                    M8ServiceStatusClient.query(endpoint[0]));
        } finally {
            if (bound) context.unbindService(connection);
            pm.setComponentEnabledSetting(name,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        }
    }
}
