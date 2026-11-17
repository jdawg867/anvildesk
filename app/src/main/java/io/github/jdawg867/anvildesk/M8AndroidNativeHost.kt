package io.github.jdawg867.anvildesk

import io.github.jdawg867.anvildesk.runtime.M8X11LaunchGate
import io.github.jdawg867.anvildesk.runtime.M8X11StartCoordinator
import java.io.File

/**
 * Android boundary for M8. Deliberately fails closed until the pinned native
 * libXlorie JNI registry is adapted to AnvilDesk's own classes.
 *
 * Do not System.loadLibrary("Xlorie") here: the pinned JNI_OnLoad currently
 * registers com.termux.x11.CmdEntryPoint, LorieView and MainActivity and can
 * abort on an AnvilDesk-only class loader.
 */
internal class M8AndroidNativeHost(
    private val nativeLibraryDirectory: File,
) : M8X11StartCoordinator.NativeHost {
    enum class Capability {
        LIBRARY_NOT_PACKAGED,
        JNI_ADAPTER_NOT_IMPLEMENTED,
    }

    fun capability(): Capability {
        val library = File(nativeLibraryDirectory, "libXlorie.so")
        return if (!library.isFile) Capability.LIBRARY_NOT_PACKAGED
        else Capability.JNI_ADAPTER_NOT_IMPLEMENTED
    }

    override fun start(plan: M8X11LaunchGate.LaunchPlan): Boolean {
        // The available native payload is an audit/packaging artifact only.
        // Never attempt to start a native thread that has no safe shutdown path.
        return false
    }

    override fun stop(): Boolean = false

    override fun isAlive(): Boolean = false
}
