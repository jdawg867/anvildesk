package io.github.jdawg867.anvildesk.m8;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * Disabled, non-exported secondary-process placeholder. This class neither
 * starts Xorg nor loads libXlorie. It must stay disabled until IPC, native
 * lifecycle, authentication and cleanup are reviewed and tested.
 */
public final class M8X11ProcessService extends Service {
    @Override public IBinder onBind(Intent intent) {
        // No IPC surface is exposed yet.
        return null;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        stopSelf(startId);
        return START_NOT_STICKY;
    }
}
