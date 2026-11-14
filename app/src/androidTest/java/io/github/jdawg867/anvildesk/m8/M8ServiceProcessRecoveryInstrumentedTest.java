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

/** Emulator only; native library loading is never performed. */
@RunWith(AndroidJUnit4.class)
public final class M8ServiceProcessRecoveryInstrumentedTest {
    private static final class Observer implements ServiceConnection {
        final M8ServiceConnection state = new M8ServiceConnection();
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch disconnected = new CountDownLatch(1);
        volatile IBinder binder;
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            binder = service;
            state.onServiceConnected(name, service);
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
    }

    private static int remotePid(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningAppProcessInfo info : manager.getRunningAppProcesses()) {
            if ((context.getPackageName() + ":m8x11").equals(info.processName)) return info.pid;
        }
        return -1;
    }

    @Test public void freshBindAfterProcessDeathUsesNewProcess() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PackageManager pm = context.getPackageManager();
        ComponentName component = new ComponentName(context, M8X11ProcessService.class);
        Observer first = new Observer();
        boolean firstBound = false;
        Observer second = new Observer();
        boolean secondBound = false;
        try {
            pm.setComponentEnabledSetting(component,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            Intent intent = new Intent(context, M8X11ProcessService.class);
            firstBound = context.bindService(intent, first, Context.BIND_AUTO_CREATE);
            assertTrue(firstBound);
            assertTrue(first.connected.await(20, TimeUnit.SECONDS));
            assertEquals(M8ServiceStatusClient.State.STOPPED, first.state.queryStatus());
            int pid = remotePid(context);
            assertTrue("remote PID unavailable", pid > 0 && pid != Process.myPid());
            Process.killProcess(pid);
            assertTrue("death callback missing", first.disconnected.await(20, TimeUnit.SECONDS));
            assertEquals(M8ServiceStatusClient.State.UNAVAILABLE, first.state.queryStatus());
            first.state.close();
            context.unbindService(first);
            firstBound = false;

            secondBound = context.bindService(intent, second, Context.BIND_AUTO_CREATE);
            assertTrue(secondBound);
            assertTrue("new process did not connect", second.connected.await(20, TimeUnit.SECONDS));
            int newPid = remotePid(context);
            assertTrue("new remote PID missing", newPid > 0 && newPid != Process.myPid());
            assertTrue("reused killed PID", newPid != pid);
            assertEquals(M8ServiceStatusClient.State.STOPPED, second.state.queryStatus());
            assertEquals(M8ServiceStatusClient.State.UNAVAILABLE, first.state.queryStatus());
        } finally {
            first.state.close();
            second.state.close();
            if (firstBound) context.unbindService(first);
            if (secondBound) context.unbindService(second);
            pm.setComponentEnabledSetting(component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        }
    }
}
