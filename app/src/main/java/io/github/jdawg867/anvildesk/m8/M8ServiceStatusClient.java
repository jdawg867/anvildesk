package io.github.jdawg867.anvildesk.m8;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Read-only, versioned cross-process status client. Never starts or binds
 * services, and never loads native code. A dead or incompatible Binder is
 * treated as unavailable, not as a running server.
 */
public final class M8ServiceStatusClient {
    public enum State { UNAVAILABLE, STOPPED }

    private M8ServiceStatusClient() {}

    public static State query(IBinder remote) {
        if (remote == null) return State.UNAVAILABLE;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(M8X11ProcessService.DESCRIPTOR);
            data.writeInt(M8ServiceProtocol.VERSION);
            if (!remote.transact(M8X11ProcessService.QUERY_STATUS, data, reply, 0))
                return State.UNAVAILABLE;
            reply.readException();
            int version = reply.readInt();
            int running = reply.readInt();
            int authorized = reply.readInt();
            if (version != M8ServiceProtocol.VERSION || authorized != 0 || running != 0)
                return State.UNAVAILABLE;
            if (reply.dataAvail() != 0) return State.UNAVAILABLE;
            return State.STOPPED;
        } catch (RemoteException | RuntimeException error) {
            return State.UNAVAILABLE;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
