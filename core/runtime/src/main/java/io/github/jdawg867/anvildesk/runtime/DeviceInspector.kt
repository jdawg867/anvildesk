package io.github.jdawg867.anvildesk.runtime

import android.os.Build

data class DeviceSnapshot(
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val primaryAbi: String,
    val arm64Capable: Boolean,
    val rootCandidateDetected: Boolean,
)

object DeviceInspector {
    fun snapshot(): DeviceSnapshot = DeviceSnapshot(
        manufacturer = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
        sdkInt = Build.VERSION.SDK_INT,
        primaryAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
        arm64Capable = supportsArm64(Build.SUPPORTED_ABIS),
        rootCandidateDetected = RootAccess.findSuBinary() != null,
    )

    internal fun supportsArm64(abis: Array<String>): Boolean =
        abis.any { it.equals("arm64-v8a", ignoreCase = true) }
}
