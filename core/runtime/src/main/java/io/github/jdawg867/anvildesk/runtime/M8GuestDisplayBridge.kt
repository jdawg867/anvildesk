package io.github.jdawg867.anvildesk.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Strict, non-executing PRoot bridge plan. Native startup and guest execution
 * must remain gated on explicit tests and a live authenticated X11 handshake.
 */
object M8GuestDisplayBridge {
    data class Plan(
        val binds: List<String>,
        val guestEnvironment: Map<String, String>,
        val guestDisplayProbe: List<String>,
    )

    fun plan(
        workspace: M8PrivateSocketWorkspace,
        layout: M8PrivateSocketWorkspace.Layout,
        authority: File,
        guestAuthorityTarget: File,
        guestSocketDirectory: File,
    ): Plan {
        require(workspace.inspect() == layout) { "Unvalidated M8 workspace" }
        require(authority == File(layout.root, "Xauthority")) { "Unexpected Xauthority location" }
        require(Files.isRegularFile(authority.toPath(), LinkOption.NOFOLLOW_LINKS))
        require(Files.getPosixFilePermissions(authority.toPath()) ==
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)) {
            "Xauthority permissions must be owner-only"
        }
        // The guest's bind targets must be prepared and validated separately;
        // never create or replace them in an existing Ubuntu runtime here.
        require(Files.isRegularFile(guestAuthorityTarget.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Guest /root/.Xauthority bind target is missing or not a regular file"
        }
        require(Files.isDirectory(guestSocketDirectory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Guest /tmp/.X11-unix bind target is missing"
        }
        require(!Files.isSymbolicLink(guestAuthorityTarget.toPath()))
        require(!Files.isSymbolicLink(guestSocketDirectory.toPath()))
        return Plan(
            binds = listOf(
                "--bind=${layout.socketDirectory.absolutePath}:/tmp/.X11-unix!",
                "--bind=${authority.absolutePath}:/root/.Xauthority!",
            ),
            guestEnvironment = mapOf(
                "DISPLAY" to M8PrivateDisplayContract.GUEST_DISPLAY,
                "XAUTHORITY" to M8PrivateDisplayContract.GUEST_AUTHORITY,
            ),
            guestDisplayProbe = listOf("/usr/bin/xdpyinfo", "-display", M8PrivateDisplayContract.GUEST_DISPLAY),
        )
    }
}
