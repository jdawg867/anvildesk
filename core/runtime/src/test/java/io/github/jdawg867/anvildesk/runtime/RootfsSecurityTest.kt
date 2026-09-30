package io.github.jdawg867.anvildesk.runtime

import java.net.URI
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootfsSecurityTest {
    @Test
    fun sha256MatchesKnownFile() {
        val file = Files.createTempFile("anvildesk-sha", ".txt").toFile()
        try {
            file.writeText("hello")
            assertEquals(
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                Sha256.digest(file),
            )
            assertTrue(
                Sha256.matches(
                    file,
                    "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                ),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun relativeHttpsRedirectIsAllowed() {
        val redirect = VerifiedRootfsDownloader.resolveHttpsRedirect(
            URI("https://example.com/releases/rootfs.tar.gz"),
            "../mirror/rootfs.tar.gz",
        )

        assertEquals("https://example.com/mirror/rootfs.tar.gz", redirect.toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun redirectDowngradeToHttpIsRejected() {
        VerifiedRootfsDownloader.resolveHttpsRedirect(
            URI("https://example.com/rootfs.tar.gz"),
            "http://example.com/rootfs.tar.gz",
        )
    }

    @Test
    fun normalArchiveEntryStaysUnderRoot() {
        val root = Files.createTempDirectory("anvildesk-root")
        try {
            val resolved = ArchivePathPolicy.resolveEntry(root, "usr/bin/bash")
            assertEquals(root.resolve("usr/bin/bash").toAbsolutePath().normalize(), resolved)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun parentTraversalIsRejected() {
        val root = Files.createTempDirectory("anvildesk-root")
        try {
            ArchivePathPolicy.resolveEntry(root, "../../data/local/tmp/escape")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun absoluteArchiveEntryIsRejected() {
        val root = Files.createTempDirectory("anvildesk-root")
        try {
            ArchivePathPolicy.resolveEntry(root, "/data/local/tmp/escape")
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
