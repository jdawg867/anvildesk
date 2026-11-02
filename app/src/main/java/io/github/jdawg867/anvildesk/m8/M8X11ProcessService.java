package io.github.jdawg867.anvildesk.m8;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

/**
 * Disabled, non-exported cross-process Binder endpoint.
 * Only a versioned, read-only status transaction is recognized.
 * No native loading, start/stop command or Ubuntu filesystem access.
 */
public final class M8X11ProcessService extends Service {
    public static final String DESCRIPTOR = "io.github.jdawg867.anvildesk.m8.IStatus";
    public static final int QUERY_STATUS = IBinder.FIRST_CALL_TRANSACTION;

    private final Binder binder = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (Binder.getCallingUid() != Process.myUid()) {
                throw new SecurityException("M8 Binder caller UID rejected");
            }
            if (code != QUERY_STATUS || reply == null
                    || (flags & IBinder.FLAG_ONEWAY) != 0) {
                return super.onTransact(code, data, reply, flags);
            }
            data.enforceInterface(DESCRIPTOR);
            int version = data.readInt();
            data.enforceNoDataAvail();
            if (M8ServiceProtocol.evaluate(version, M8ServiceProtocol.Operation.QUERY,
                    0L, true) != M8ServiceProtocol.Decision.READ_ONLY) {
                throw new SecurityException("M8 protocol version rejected");
            }
            reply.writeNoException();
            reply.writeInt(M8ServiceProtocol.VERSION);
            reply.writeInt(0); // native server not running
            reply.writeInt(0); // native loading not authorized
            return true;
        }
    };

    @Override public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        stopSelf(startId);
        return START_NOT_STICKY;
    }
}
