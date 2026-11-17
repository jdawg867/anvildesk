package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.IBinder;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Repeated real :m8x11 bind/unbind without starting native code. */
@RunWith(AndroidJUnit4.class)
public final class M8CrossProcessReconnectInstrumentedTest {
    @Test public void unbindRevokesAndSubsequentBindQueriesAgain() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ComponentName component = new ComponentName(context, M8X11ProcessService.class);
        PackageManager pm = context.getPackageManager();
        try {
            pm.setComponentEnabledSetting(component,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            for (int attempt = 0; attempt < 2; attempt++) {
                M8ServiceConnection connection = new M8ServiceConnection();
                CountDownLatch connected = new CountDownLatch(1);
                android.content.ServiceConnection observer = new android.content.ServiceConnection() {
                    @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                        connection.onServiceConnected(name, binder);
                        connected.countDown();
                    }
                    @Override public void onServiceDisconnected(ComponentName name) {
                        connection.onServiceDisconnected(name);
                    }
                    @Override public void onBindingDied(ComponentName name) {
                        connection.onBindingDied(name);
                    }
                    @Override public void onNullBinding(ComponentName name) {
                        connection.onNullBinding(name);
                        connected.countDown();
                    }
                };
                boolean bound = false;
                try {
                    bound = context.bindService(new Intent(context, M8X11ProcessService.class),
                            observer, Context.BIND_AUTO_CREATE);
                    assertTrue(bound);
                    assertTrue(connected.await(20, TimeUnit.SECONDS));
                    assertEquals(M8ServiceStatusClient.State.STOPPED, connection.queryStatus());
                } finally {
                    connection.close();
                    if (bound) context.unbindService(observer);
                }
                assertEquals(M8ServiceStatusClient.State.UNAVAILABLE, connection.queryStatus());
            }
        } finally {
            pm.setComponentEnabledSetting(component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        }
    }
}
