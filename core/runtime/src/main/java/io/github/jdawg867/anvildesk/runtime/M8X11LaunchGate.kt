package io.github.jdawg867.anvildesk.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.SecureRandom

/** Prepares X11 authentication and validates launch prerequisites; does not execute native code. */
class M8X11LaunchGate(private val workspace: M8PrivateSocketWorkspace) {
    data class LaunchPlan(
        val tempDirectory: File,
        val socketPath: File,
        val authorityFile: File,
        val display: String,
        val nativeArguments: List<String>,
    )

    /** Never turn on `-ac`, TCP listening or guest startup here. */
    fun plan(layout: M8PrivateSocketWorkspace.Layout, authority: File): LaunchPlan {
        require(workspace.inspect() == layout) { "M8 workspace must be freshly validated" }
        require(authority.parentFile == layout.root) { "Xauthority must stay inside M8 namespace" }
        require(Files.isRegularFile(authority.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Missing Xauthority file"
        }
        require(!Files.isSymbolicLink(authority.toPath())) { "Xauthority symlink forbidden" }
        return LaunchPlan(
            tempDirectory = layout.tmp,
            socketPath = layout.socket,
            authorityFile = authority,
            display = M8PrivateDisplayContract.GUEST_DISPLAY,
            nativeArguments = listOf(
                M8PrivateDisplayContract.GUEST_DISPLAY,
                "-nolisten", "tcp",
                "-auth", authority.absolutePath,
            ),
        )
    }

    /** Writes Xauthority MIT-MAGIC-COOKIE-1 record for local display 0, without shell commands. */
    fun createAuthority(layout: M8PrivateSocketWorkspace.Layout): File {
        require(workspace.inspect() == layout)
        val file = File(layout.root, "Xauthority")
        val cookie = ByteArray(16).also { SecureRandom().nextBytes(it) }
        // Xauthority binary file: family, address, number, name, cookie as big-endian u16-length fields.
        // FamilyWild=65535 matches the display irrespective of host naming; cookie remains secret.
        val record = java.io.ByteArrayOutputStream().apply {
            fun put16(value: Int) { write((value ushr 8) and 255); write(value and 255) }
            fun field(bytes: ByteArray) { put16(bytes.size); write(bytes) }
            put16(65535)
            field(byteArrayOf())
            field("0".toByteArray(Charsets.US_ASCII))
            field("MIT-MAGIC-COOKIE-1".toByteArray(Charsets.US_ASCII))
            field(cookie)
        }.toByteArray()
        Files.newOutputStream(file.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE).use {
            it.write(record)
        }
        Files.setPosixFilePermissions(file.toPath(), setOf(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
        ))
        cookie.fill(0)
        return file
    }
}
