package io.github.jdawg867.anvildesk.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Pure, non-launching M8 transport contract.
 * Does not start an X server, create a socket, or mutate the M7 runtime.
 */
object M8PrivateDisplayContract {
    const val DISPLAY_NUMBER = 0
    const val GUEST_DISPLAY = ":0"
    const val GUEST_SOCKET_DIR = "/tmp/.X11-unix"
    const val GUEST_SOCKET = "$GUEST_SOCKET_DIR/X0"
    const val GUEST_AUTHORITY = "/root/.Xauthority"

    fun validateHostNamespace(appFiles: File, sessionRoot: File): File {
        val files = appFiles.canonicalFile
        require(Files.isDirectory(files.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "App files directory missing"
        }
        require(!Files.isSymbolicLink(appFiles.toPath())) { "App files directory must not be a symlink" }

        // Do not resolve a path through symlinks before checking the expected lexical layout.
        val expected = File(appFiles, "m8-display").toPath().toAbsolutePath().normalize()
        val supplied = sessionRoot.toPath().toAbsolutePath().normalize()
        require(supplied == expected) { "M8 session path must be the fixed app-private directory" }
        require(!Files.isSymbolicLink(sessionRoot.toPath())) { "M8 session root must not be a symlink" }
        require(sessionRoot.canonicalFile.parentFile == files) {
            "M8 session root escapes the app files directory"
        }
        return sessionRoot
    }

    /**
     * Only a positive live-server + authenticated guest probe qualifies as ready.
     * Existence of the socket file alone is never sufficient.
     */
    fun isReady(serverAlive: Boolean, authenticatedClientConnected: Boolean, matchingDisplay: Boolean): Boolean =
        serverAlive && authenticatedClientConnected && matchingDisplay
}

enum class M8DisplayPhase {
    IDLE, PREPARING, STARTING_SERVER, WAITING_FOR_READY, RUNNING, STOPPING, FAILED
}

class M8DisplayLifecycle {
    var phase: M8DisplayPhase = M8DisplayPhase.IDLE
        private set

    fun begin() {
        check(phase == M8DisplayPhase.IDLE) { "A display session is already active or needs cleanup" }
        phase = M8DisplayPhase.PREPARING
    }

    fun prepared() {
        check(phase == M8DisplayPhase.PREPARING)
        phase = M8DisplayPhase.STARTING_SERVER
    }

    fun serverStarted() {
        check(phase == M8DisplayPhase.STARTING_SERVER)
        phase = M8DisplayPhase.WAITING_FOR_READY
    }

    fun ready(serverAlive: Boolean, authenticatedClientConnected: Boolean, matchingDisplay: Boolean) {
        check(phase == M8DisplayPhase.WAITING_FOR_READY)
        check(M8PrivateDisplayContract.isReady(serverAlive, authenticatedClientConnected, matchingDisplay)) {
            "Live X server and authenticated guest handshake required"
        }
        phase = M8DisplayPhase.RUNNING
    }

    fun fail() {
        check(phase != M8DisplayPhase.IDLE) { "No session to fail" }
        phase = M8DisplayPhase.FAILED
    }

    fun stop() {
        check(phase != M8DisplayPhase.IDLE)
        phase = M8DisplayPhase.STOPPING
    }

    /** Caller must have closed server, guests, and sockets before acknowledging cleanup. */
    fun cleanupComplete() {
        check(phase == M8DisplayPhase.STOPPING || phase == M8DisplayPhase.FAILED)
        phase = M8DisplayPhase.IDLE
    }
}
