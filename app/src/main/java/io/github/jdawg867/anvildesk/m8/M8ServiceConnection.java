package io.github.jdawg867.anvildesk.m8;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;

/**
 * Passive connection observer for a future explicitly authorized bind.
 * Does not bind, start, enable, or load any service/native library.
 */
public final class M8ServiceConnection implements ServiceConnection {
    private IBinder remote;
    private long generation;

    @Override public synchronized void onServiceConnected(ComponentName name, IBinder service) {
        remote = service;
        generation++;
    }

    @Override public synchronized void onServiceDisconnected(ComponentName name) {
        remote = null;
        generation++;
    }

    @Override public synchronized void onBindingDied(ComponentName name) {
        remote = null;
        generation++;
    }

    @Override public synchronized void onNullBinding(ComponentName name) {
        remote = null;
        generation++;
    }

    public synchronized void close() {
        remote = null;
        generation++;
    }

    public synchronized long generation() { return generation; }

    public synchronized M8ServiceStatusClient.State queryStatus() {
        return M8ServiceStatusClient.query(remote);
    }
}
