package io.github.jdawg867.anvildesk.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MutableRuntimeStoreTest {
    private val manifest = RootfsCatalog.Ubuntu24045Arm64

    @Test
    fun clonePreservesGuestTreeButDropsVerifiedInstallMetadata() {
        val workspace = Files.createTempDirectory("anvildesk-mutable-runtime").toFile()
        try {
            val appFiles = File(workspace, "files").apply { mkdirs() }
            val verified = File(workspace, "verified").apply { mkdirs() }
            File(verified, "etc").mkdirs()
            File(verified, "etc/os-release").writeText("ID=ubuntu\n")
            File(verified, "usr/bin").mkdirs()
            File(verified, "usr/bin/apt-get").writeText("apt")
            File(verified, "lib").mkdirs()
            Files.createSymbolicLink(
                File(verified, "lib/os-release-link").toPath(),
                java.nio.file.Paths.get("../etc/os-release"),
            )
            File(verified, ".anvildesk").mkdirs()
            File(verified, ".anvildesk/install.properties").writeText("verified-metadata")

            val store = MutableRuntimeStore(appFiles) { 12345L }
            val record = store.ensureFromVerified(manifest, verified)
            val root = store.runtimeRoot(manifest.id)

            assertEquals(manifest.id, record.baseManifestId)
            assertEquals(manifest.sha256, record.baseSha256)
            assertEquals(manifest.version, record.baseVersion)
            assertEquals(manifest.architecture, record.baseArchitecture)
            assertEquals(12345L, record.createdAtEpochMillis)
            assertTrue(File(root, "etc/os-release").isFile)
            assertTrue(File(root, "usr/bin/apt-get").isFile)
            assertTrue(Files.isSymbolicLink(File(root, "lib/os-release-link").toPath()))
            assertFalse(File(root, ".anvildesk").exists())
            assertEquals(record, store.currentRecord(manifest.id))
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun existingRuntimeIsReusedWithoutRecloning() {
        val workspace = Files.createTempDirectory("anvildesk-mutable-reuse").toFile()
        try {
            val appFiles = File(workspace, "files").apply { mkdirs() }
            val verified = File(workspace, "verified").apply { mkdirs() }
            File(verified, "etc").mkdirs()
            File(verified, "etc/os-release").writeText("ID=ubuntu\n")

            val store = MutableRuntimeStore(appFiles) { 12345L }
            val first = store.ensureFromVerified(manifest, verified)
            val marker = File(store.runtimeRoot(manifest.id), "package-state-marker")
            marker.writeText("preserve me")

            File(verified, "new-source-file").writeText("must not be recloned")
            val second = store.ensureFromVerified(manifest, verified)

            assertEquals(first, second)
            assertEquals("preserve me", marker.readText())
            assertFalse(File(store.runtimeRoot(manifest.id), "new-source-file").exists())
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun existingRuntimeRejectsDifferentBaseDigest() {
        val workspace = Files.createTempDirectory("anvildesk-mutable-mismatch").toFile()
        try {
            val appFiles = File(workspace, "files").apply { mkdirs() }
            val verified = File(workspace, "verified").apply { mkdirs() }
            File(verified, "etc").mkdirs()
            File(verified, "etc/os-release").writeText("ID=ubuntu\n")

            val store = MutableRuntimeStore(appFiles)
            store.ensureFromVerified(manifest, verified)
            store.ensureFromVerified(manifest.copy(sha256 = "0".repeat(64)), verified)
        } finally {
            workspace.deleteRecursively()
        }
    }
}
