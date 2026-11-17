package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Process;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Emulator-only: kill the separate M8 service process, never the target app
 * process. No native X11 loading, installation over a device, or rootfs access.
 */
@RunWith(AndroidJUnit4.class)
public final class M8ServiceProcessDeathInstrumentedTest {
    @Test public void remoteProcessDeathRevokesConnection() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PackageManager pm = context.getPackageManager();
        ComponentName component = new ComponentName(context, M8X11ProcessService.class);
        M8ServiceConnection state = new M8ServiceConnection();
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        ServiceConnection observer = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                state.onServiceConnected(name, binder);
                connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                state.onServiceDisconnected(name);
                disconnected.countDown();
            }
            @Override public void onBindingDied(ComponentName name) {
                state.onBindingDied(name);
                disconnected.countDown();
            }
            @Override public void onNullBinding(ComponentName name) {
                state.onNullBinding(name);
                disconnected.countDown();
            }
        };
        boolean bound = false;
        try {
            pm.setComponentEnabledSetting(component,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            bound = context.bindService(new Intent(context, M8X11ProcessService.class),
                    observer, Context.BIND_AUTO_CREATE);
            assertTrue("service bind failed", bound);
            assertTrue("service did not connect", connected.await(20, TimeUnit.SECONDS));
            assertEquals(M8ServiceStatusClient.State.STOPPED, state.queryStatus());

            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            String processName = context.getPackageName() + ":m8x11";
            int remotePid = -1;
            for (ActivityManager.RunningAppProcessInfo info : manager.getRunningAppProcesses()) {
                if (processName.equals(info.processName)) {
                    remotePid = info.pid;
                    break;
                }
            }
            assertTrue("could not identify isolated service PID", remotePid > 0);
            assertTrue("refuse to kill instrumentation process", remotePid != Process.myPid());
            Process.killProcess(remotePid);
            assertTrue("did not receive Binder process-death callback",
                    disconnected.await(20, TimeUnit.SECONDS));
            assertEquals(M8ServiceStatusClient.State.UNAVAILABLE, state.queryStatus());
        } finally {
            state.close();
            if (bound) context.unbindService(observer);
            pm.setComponentEnabledSetting(component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        }
    }
}
