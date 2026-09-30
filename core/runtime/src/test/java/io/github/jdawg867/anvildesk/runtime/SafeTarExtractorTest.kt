package io.github.jdawg867.anvildesk.runtime

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeTarExtractorTest {
    @Test
    fun extractsRegularFileAndPreservesExecuteBit() {
        val workspace = Files.createTempDirectory("anvildesk-extract-test").toFile()
        try {
            val archive = File(workspace, "rootfs.tar.gz")
            val root = File(workspace, "root")
            createArchive(archive) { tar ->
                val bytes = "#!/bin/sh\necho hello\n".toByteArray()
                val entry = TarArchiveEntry("usr/bin/hello").apply {
                    size = bytes.size.toLong()
                    mode = 0x1ed // 0755
                }
                tar.putArchiveEntry(entry)
                tar.write(bytes)
                tar.closeArchiveEntry()
            }

            val result = SafeTarExtractor.extract(archive, root)
            val extracted = File(root, "usr/bin/hello")

            assertEquals("#!/bin/sh\necho hello\n", extracted.readText())
            assertTrue(extracted.canExecute())
            assertEquals(1, result.entriesExtracted)
            assertEquals(extracted.length(), result.regularFileBytes)
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun hardLinkIsMaterializedAsRegularFileCopy() {
        val workspace = Files.createTempDirectory("anvildesk-extract-test").toFile()
        try {
            val archive = File(workspace, "rootfs.tar.gz")
            val root = File(workspace, "root")
            val perlBytes = "fake-perl-binary".toByteArray()

            createArchive(archive) { tar ->
                val perl = TarArchiveEntry("usr/bin/perl").apply {
                    size = perlBytes.size.toLong()
                    mode = 0x1ed // 0755
                }
                tar.putArchiveEntry(perl)
                tar.write(perlBytes)
                tar.closeArchiveEntry()

                val versionedPerl = TarArchiveEntry("usr/bin/perl5.38.2", TarConstants.LF_LINK).apply {
                    linkName = "usr/bin/perl"
                }
                tar.putArchiveEntry(versionedPerl)
                tar.closeArchiveEntry()
            }

            val result = SafeTarExtractor.extract(archive, root)
            val perl = File(root, "usr/bin/perl").toPath()
            val versionedPerl = File(root, "usr/bin/perl5.38.2").toPath()

            assertTrue(Files.isRegularFile(perl))
            assertTrue(Files.isRegularFile(versionedPerl))
            assertFalse(Files.isSymbolicLink(versionedPerl))
            assertFalse(Files.isSameFile(perl, versionedPerl))
            assertTrue(Files.readAllBytes(perl).contentEquals(Files.readAllBytes(versionedPerl)))
            assertTrue(versionedPerl.toFile().canExecute())
            assertEquals((perlBytes.size * 2).toLong(), result.regularFileBytes)
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun rootfsAbsoluteSymlinkIsRewrittenInsideExtractionRoot() {
        val workspace = Files.createTempDirectory("anvildesk-extract-test").toFile()
        try {
            val archive = File(workspace, "rootfs.tar.gz")
            val root = File(workspace, "root")
            createArchive(archive) { tar ->
                val entry = TarArchiveEntry("etc/mtab", TarConstants.LF_SYMLINK).apply {
                    linkName = "/proc/mounts"
                }
                tar.putArchiveEntry(entry)
                tar.closeArchiveEntry()
            }

            SafeTarExtractor.extract(archive, root)

            val link = File(root, "etc/mtab").toPath()
            assertTrue(Files.isSymbolicLink(link))
            assertEquals("../proc/mounts", Files.readSymbolicLink(link).toString())
            assertEquals(
                File(root, "proc/mounts").toPath().toAbsolutePath().normalize(),
                link.parent.resolve(Files.readSymbolicLink(link)).normalize(),
            )
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRelativeSymlinkTargetThatEscapesRoot() {
        val workspace = Files.createTempDirectory("anvildesk-extract-test").toFile()
        try {
            val archive = File(workspace, "rootfs.tar.gz")
            val root = File(workspace, "root")
            createArchive(archive) { tar ->
                val entry = TarArchiveEntry("usr/bin/escape", TarConstants.LF_SYMLINK).apply {
                    linkName = "../../../outside"
                }
                tar.putArchiveEntry(entry)
                tar.closeArchiveEntry()
            }

            SafeTarExtractor.extract(archive, root)
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDoubleSlashAbsoluteSymlinkTarget() {
        val workspace = Files.createTempDirectory("anvildesk-extract-test").toFile()
        try {
            val archive = File(workspace, "rootfs.tar.gz")
            val root = File(workspace, "root")
            createArchive(archive) { tar ->
                val entry = TarArchiveEntry("etc/escape", TarConstants.LF_SYMLINK).apply {
                    linkName = "//outside/path"
                }
                tar.putArchiveEntry(entry)
                tar.closeArchiveEntry()
            }

            SafeTarExtractor.extract(archive, root)
        } finally {
            workspace.deleteRecursively()
        }
    }

    private fun createArchive(file: File, body: (TarArchiveOutputStream) -> Unit) {
        FileOutputStream(file).use { fileOutput ->
            GZIPOutputStream(fileOutput).use { gzipOutput ->
                TarArchiveOutputStream(gzipOutput).use { tarOutput ->
                    tarOutput.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    body(tarOutput)
                    tarOutput.finish()
                }
            }
        }
    }
}
