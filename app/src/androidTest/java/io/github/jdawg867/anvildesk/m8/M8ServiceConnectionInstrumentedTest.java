package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;

import android.content.ComponentName;
import android.os.IBinder;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercises connection revocation without enabling the disabled X11 service. */
@RunWith(AndroidJUnit4.class)
public final class M8ServiceConnectionInstrumentedTest {
    private static final ComponentName SERVICE = new ComponentName(
            "io.github.jdawg867.anvildesk", M8X11ProcessService.class.getName());

    @Test public void disconnectRevokesBinderAndAdvancesGeneration() {
        M8ServiceConnection connection = new M8ServiceConnection();
        assertEquals(M8ServiceStatusClient.State.UNAVAILABLE, connection.queryStatus());
        long initial = connection.generation();
        IBinder endpoint = new M8X11ProcessService().onBind(null);
        connection.onServiceConnected(SERVICE, endpoint);
        assertTrue(connection.generation() > initial);
        assertEquals(M8ServiceStatusClient.State.STOPPED, connection.queryStatus());
        long connected = connection.generation();
        connection.onServiceDisconnected(SERVICE);
        assertTrue(connection.generation() > connected);
        assertEquals(M8ServiceStatusClient.State.UNAVAILABLE, connection.queryStatus());
    }

    @Test public void bindingDeathNullBindingAndCloseRevokeAccess() {
        M8ServiceConnection connection = new M8ServiceConnection();
        IBinder endpoint = new M8X11ProcessService().onBind(null);
        connection.onServiceConnected(SERVICE, endpoint);
        connection.onBindingDied(SERVICE);
        assertEquals(M8ServiceStatusClient.State.UNAVAILABLE, connection.queryStatus());
        connection.onServiceConnected(SERVICE, endpoint);
        connection.onNullBinding(SERVICE);
        assertEquals(M8ServiceStatusClient.State.UNAVAILABLE, connection.queryStatus());
        connection.onServiceConnected(SERVICE, endpoint);
        connection.close();
        assertEquals(M8ServiceStatusClient.State.UNAVAILABLE, connection.queryStatus());
    }
}
