package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * On-device Parcel/Binder transaction tests. The service is instantiated
 * directly; these tests do NOT enable it or prove cross-process lifecycle.
 */
@RunWith(AndroidJUnit4.class)
public final class M8BinderParcelInstrumentedTest {
    @Test public void supportedReadOnlyQueryReturnsStopped() {
        IBinder endpoint = new M8X11ProcessService().onBind(null);
        assertEquals(M8ServiceStatusClient.State.STOPPED,
                M8ServiceStatusClient.query(endpoint));
    }

    @Test public void missingBinderIsUnavailable() {
        assertEquals(M8ServiceStatusClient.State.UNAVAILABLE,
                M8ServiceStatusClient.query(null));
    }

    @Test public void unknownVersionIsRejected() throws RemoteException {
        IBinder endpoint = new M8X11ProcessService().onBind(null);
        Parcel request = Parcel.obtain();
        Parcel response = Parcel.obtain();
        try {
            request.writeInterfaceToken(M8X11ProcessService.DESCRIPTOR);
            request.writeInt(M8ServiceProtocol.VERSION + 1);
            // A local Binder may propagate SecurityException to transact().
            boolean rejected;
            try {
                boolean handled = endpoint.transact(
                        M8X11ProcessService.QUERY_STATUS, request, response, 0);
                if (!handled) {
                    rejected = true;
                } else {
                    try {
                        response.readException();
                        rejected = false;
                    } catch (SecurityException expected) {
                        rejected = true;
                    }
                }
            } catch (SecurityException expected) {
                rejected = true;
            }
            assertTrue(rejected);
        } finally {
            response.recycle();
            request.recycle();
        }
    }

    @Test public void startStopTransactionsNotImplemented() throws RemoteException {
        IBinder endpoint = new M8X11ProcessService().onBind(null);
        Parcel request = Parcel.obtain();
        Parcel response = Parcel.obtain();
        try {
            request.writeInterfaceToken(M8X11ProcessService.DESCRIPTOR);
            request.writeInt(M8ServiceProtocol.VERSION);
            assertFalse(endpoint.transact(
                    M8X11ProcessService.QUERY_STATUS + 1, request, response, 0));
        } finally {
            response.recycle();
            request.recycle();
        }
    }
}
