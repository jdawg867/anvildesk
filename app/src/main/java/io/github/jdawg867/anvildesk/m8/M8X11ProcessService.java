package io.github.jdawg867.anvildesk.m8;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;

/**
 * Disabled, non-exported M8 process endpoint. Read-only local Binder status;
 * native execution, writable commands and X11 transport are not implemented.
 */
public final class M8X11ProcessService extends Service {
    public static final class Status {
        public final int protocolVersion;
        public final boolean nativeRunning;
        public final boolean nativeLoadingAllowed;

        private Status() {
            protocolVersion = M8ServiceProtocol.VERSION;
            nativeRunning = false;
            nativeLoadingAllowed = false;
        }
    }

    public final class LocalBinder extends Binder {
        public Status queryStatus() {
            // Enforce the app UID even if the manifest gets changed later.
            if (Binder.getCallingUid() != Process.myUid()) {
                throw new SecurityException("M8 Binder caller UID rejected");
            }
            return new Status();
        }
    }

    private final IBinder binder = new LocalBinder();

    @Override public IBinder onBind(Intent intent) {
        // Service remains disabled by manifest. No START/STOP IPC is exposed.
        if (Binder.getCallingUid() != Process.myUid()) {
            return null;
        }
        return binder;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        stopSelf(startId);
        return START_NOT_STICKY;
    }
}
