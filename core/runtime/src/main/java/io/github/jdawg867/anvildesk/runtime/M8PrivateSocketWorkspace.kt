package io.github.jdawg867.anvildesk.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermission

/** Owns only the dedicated M8 transport namespace; never touches M7 data. */
class M8PrivateSocketWorkspace(private val appFiles: File) {
    data class Layout(
        val root: File,
        val tmp: File,
        val socketDirectory: File,
        val socket: File,
    )

    private val ownerOnly = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
    )

    fun inspect(): Layout {
        val root = M8PrivateDisplayContract.validateHostNamespace(appFiles, File(appFiles, "m8-display"))
        val tmp = File(root, "tmp")
        val sockets = File(tmp, ".X11-unix")
        for (dir in listOf(root, tmp, sockets)) {
            require(Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "Missing private M8 transport directory: ${dir.name}"
            }
            require(!Files.isSymbolicLink(dir.toPath())) { "Symlink forbidden in M8 transport namespace" }
            require(Files.getPosixFilePermissions(dir.toPath()) == ownerOnly) {
                "M8 transport directory must be owner-only"
            }
        }
        val socket = File(sockets, "X0")
        // The server creates the socket. Never unlink any existing pathname.
        require(!Files.exists(socket.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Existing X11 socket pathname requires explicit lifecycle cleanup"
        }
        return Layout(root, tmp, sockets, socket)
    }

    /** Creates one fresh private workspace; refuses pre-existing state. */
    fun prepare(): Layout {
        val root = M8PrivateDisplayContract.validateHostNamespace(appFiles, File(appFiles, "m8-display"))
        require(!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Previous M8 session namespace exists; refusing destructive reuse"
        }
        // Parent is app-private; no caller-supplied path and no recursive deletion.
        Files.createDirectory(root.toPath())
        Files.setPosixFilePermissions(root.toPath(), ownerOnly)
        val tmp = File(root, "tmp")
        Files.createDirectory(tmp.toPath())
        Files.setPosixFilePermissions(tmp.toPath(), ownerOnly)
        val sockets = File(tmp, ".X11-unix")
        Files.createDirectory(sockets.toPath())
        Files.setPosixFilePermissions(sockets.toPath(), ownerOnly)
        return inspect()
    }

    fun guestTempBind(layout: Layout): String {
        require(layout == inspect()) { "M8 namespace changed after preparation" }
        // Fixed narrow session bind, not Android /tmp or public storage.
        return "--bind=${layout.tmp.absolutePath}:/tmp!"
    }
}
