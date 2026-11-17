package io.github.jdawg867.anvildesk.runtime

/**
 * M8-only guest diagnostics. These are NOT included in the completed M7
 * package set and must be installed only in a separately authorized runtime.
 */
object M8X11ProbeRequirements {
    val REQUIRED_GUEST_PACKAGES: List<String> = listOf("x11-utils", "xauth")
    val REQUIRED_GUEST_BINARIES: List<String> = listOf("/usr/bin/xdpyinfo", "/usr/bin/xauth")

    fun binariesPresent(rootfs: java.io.File): Boolean =
        REQUIRED_GUEST_BINARIES.all { relative ->
            java.io.File(rootfs, relative.removePrefix("/")).isFile
        }
}
