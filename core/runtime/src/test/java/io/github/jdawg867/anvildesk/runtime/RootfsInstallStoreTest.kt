package io.github.jdawg867.anvildesk.runtime

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootfsInstallStoreTest {
    @Test
    fun verifiedArchiveIsPromotedWithMetadataAtomically() {
        val workspace = Files.createTempDirectory("anvildesk-install-test").toFile()
        try {
            val filesDirectory = File(workspace, "files").apply { mkdir() }
            val archive = File(workspace, "rootfs.tar.gz")
            createArchive(archive, "etc/os-release", "NAME=Test Linux\n".toByteArray())

            val manifest = RootfsCatalog.Ubuntu24045Arm64.copy(
                id = "test-rootfs-arm64",
                distribution = "Test Linux",
                version = "1",
                sha256 = Sha256.digest(archive),
            )
            val store = RootfsInstallStore(filesDirectory) { 123456789L }

            val record = store.installVerifiedArchive(manifest, archive)
            val installed = store.installedRoot(manifest.id)

            assertEquals(manifest.id, record.manifestId)
            assertEquals(123456789L, record.installedAtEpochMillis)
            assertEquals("NAME=Test Linux\n", File(installed, "etc/os-release").readText())
            assertTrue(File(installed, ".anvildesk/install.properties").isFile)
            assertEquals(record, store.currentRecord(manifest.id))
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test(expected = SecurityException::class)
    fun digestMismatchNeverCreatesInstalledRootfs() {
        val workspace = Files.createTempDirectory("anvildesk-install-test").toFile()
        try {
            val filesDirectory = File(workspace, "files").apply { mkdir() }
            val archive = File(workspace, "rootfs.tar.gz")
            createArchive(archive, "etc/os-release", "bad digest\n".toByteArray())
            val manifest = RootfsCatalog.Ubuntu24045Arm64.copy(id = "digest-mismatch-arm64")
            val store = RootfsInstallStore(filesDirectory)

            try {
                store.installVerifiedArchive(manifest, archive)
            } finally {
                assertFalse(store.installedRoot(manifest.id).exists())
            }
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun abandonedStagingIsRemovedWithoutTouchingInstalledRoot() {
        val workspace = Files.createTempDirectory("anvildesk-install-test").toFile()
        try {
            val filesDirectory = File(workspace, "files").apply { mkdir() }
            val store = RootfsInstallStore(filesDirectory)
            val manifest = RootfsCatalog.Ubuntu24045Arm64.copy(id = "cleanup-test-arm64")
            store.downloadFile(manifest)

            val stagingParent = File(filesDirectory, "rootfs/staging")
            val stale = File(stagingParent, "${manifest.id}.staging-stale").apply { mkdir() }
            File(stale, "partial").writeText("partial")
            val unrelated = File(stagingParent, "other-rootfs.staging-keep").apply { mkdir() }

            store.cleanupAbandonedStaging(manifest.id)

            assertFalse(stale.exists())
            assertTrue(unrelated.isDirectory)
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun currentProvisioningStateRecognizesMatchingInstalledRecord() {
        val workspace = Files.createTempDirectory("anvildesk-install-test").toFile()
        try {
            val filesDirectory = File(workspace, "files").apply { mkdir() }
            val archive = File(workspace, "rootfs.tar.gz")
            createArchive(archive, "usr/share/test", "hello".toByteArray())
            val manifest = RootfsCatalog.Ubuntu24045Arm64.copy(
                id = "state-test-arm64",
                sha256 = Sha256.digest(archive),
            )
            val store = RootfsInstallStore(filesDirectory) { 42L }
            val expected = store.installVerifiedArchive(manifest, archive)

            val state = RootfsProvisioner(store).currentState(manifest)

            assertTrue(state is RootfsProvisioningState.Ready)
            assertEquals(expected, (state as RootfsProvisioningState.Ready).record)
            assertNotNull(store.currentRecord(manifest.id))
        } finally {
            workspace.deleteRecursively()
        }
    }

    private fun createArchive(file: File, path: String, bytes: ByteArray) {
        FileOutputStream(file).use { fileOutput ->
            GZIPOutputStream(fileOutput).use { gzipOutput ->
                TarArchiveOutputStream(gzipOutput).use { tarOutput ->
                    val entry = TarArchiveEntry(path).apply {
                        size = bytes.size.toLong()
                        mode = 0x1a4 // 0644
                    }
                    tarOutput.putArchiveEntry(entry)
                    tarOutput.write(bytes)
                    tarOutput.closeArchiveEntry()
                    tarOutput.finish()
                }
            }
        }
    }
}
