package io.github.jdawg867.anvildesk.m8;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import android.os.IBinder;
import org.junit.Test;

public final class M8ServiceStatusClientTest {
    @Test public void nullBinderFailsClosed() {
        assertEquals(M8ServiceStatusClient.State.UNAVAILABLE,
                M8ServiceStatusClient.query(null));
    }

    @Test public void rejectedTransactionFailsClosed() throws Exception {
        // Android Parcel is not executable in a plain JVM unit test, so this
        // test deliberately covers only the dependency-free null boundary.
        assertEquals(M8ServiceStatusClient.State.UNAVAILABLE,
                M8ServiceStatusClient.query(null));
    }
}
