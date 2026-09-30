package io.github.jdawg867.anvildesk.runtime

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object Sha256 {
    fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    fun matches(file: File, expected: String): Boolean =
        digest(file) == expected.lowercase()

    internal fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { byte -> append("%02x".format(byte.toInt() and 0xff)) }
    }
}
