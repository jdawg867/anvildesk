package io.github.jdawg867.anvildesk.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceInspectorTest {
    @Test
    fun detectsArm64Abi() {
        assertTrue(DeviceInspector.supportsArm64(arrayOf("arm64-v8a", "armeabi-v7a")))
    }

    @Test
    fun rejectsNonArm64AbiSet() {
        assertFalse(DeviceInspector.supportsArm64(arrayOf("x86_64", "x86")))
    }
}
