package io.github.jdawg867.anvildesk.runtime

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Properties
import java.util.UUID

class RootfsInstallException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class RootfsInstallRecord(
    val manifestId: String,
    val distribution: String,
    val version: String,
    val architecture: String,
    val sha256: String,
    val installedAtEpochMillis: Long,
    val entriesExtracted: Int,
    val regularFileBytes: Long,
    val specialEntriesSkipped: Int,
)

object RootfsInstallMetadata {
    private const val METADATA_DIRECTORY = ".anvildesk"
    private const val METADATA_FILE = "install.properties"

    fun write(stagingRoot: File, record: RootfsInstallRecord) {
        val metadataDirectory = File(stagingRoot, METADATA_DIRECTORY)
        require(!metadataDirectory.exists()) {
            "Rootfs archive contains reserved AnvilDesk metadata path"
        }
        require(metadataDirectory.mkdir()) { "Could not create rootfs metadata directory" }

        val destination = File(metadataDirectory, METADATA_FILE)
        val temporary = File(metadataDirectory, ".$METADATA_FILE.part")
        val properties = Properties().apply {
            setProperty("manifestId", record.manifestId)
            setProperty("distribution", record.distribution)
            setProperty("version", record.version)
            setProperty("architecture", record.architecture)
            setProperty("sha256", record.sha256)
            setProperty("installedAtEpochMillis", record.installedAtEpochMillis.toString())
            setProperty("entriesExtracted", record.entriesExtracted.toString())
            setProperty("regularFileBytes", record.regularFileBytes.toString())
            setProperty("specialEntriesSkipped", record.specialEntriesSkipped.toString())
        }

        FileOutputStream(temporary).use { output ->
            properties.store(output, "AnvilDesk verified rootfs install record")
            output.fd.sync()
        }

        try {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (error: AtomicMoveNotSupportedException) {
            temporary.delete()
            throw RootfsInstallException("Atomic metadata write is not supported", error)
        }
    }

    fun read(installedRoot: File): RootfsInstallRecord? {
        val file = File(File(installedRoot, METADATA_DIRECTORY), METADATA_FILE)
        if (!file.isFile) return null

        val properties = Properties()
        FileInputStream(file).use(properties::load)

        return try {
            RootfsInstallRecord(
                manifestId = properties.requireValue("manifestId"),
                distribution = properties.requireValue("distribution"),
                version = properties.requireValue("version"),
                architecture = properties.requireValue("architecture"),
                sha256 = properties.requireValue("sha256"),
                installedAtEpochMillis = properties.requireValue("installedAtEpochMillis").toLong(),
                entriesExtracted = properties.requireValue("entriesExtracted").toInt(),
                regularFileBytes = properties.requireValue("regularFileBytes").toLong(),
                specialEntriesSkipped = properties.requireValue("specialEntriesSkipped").toInt(),
            )
        } catch (error: RuntimeException) {
            throw RootfsInstallException("Installed rootfs metadata is invalid", error)
        }
    }

    private fun Properties.requireValue(key: String): String =
        getProperty(key)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing metadata field: $key")
}

class RootfsInstallStore(
    private val appFilesDirectory: File,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val idPattern = Regex("^[a-z0-9][a-z0-9._-]+$")
    private val rootfsDirectory = File(appFilesDirectory, "rootfs")
    private val downloadsDirectory = File(rootfsDirectory, "downloads")
    private val stagingDirectory = File(rootfsDirectory, "staging")
    private val installedDirectory = File(rootfsDirectory, "installed")

    fun downloadFile(manifest: RootfsManifest): File {
        RootfsManifestValidator.requireValid(manifest)
        ensureDirectories()
        return File(downloadsDirectory, "${manifest.id}.tar.gz")
    }

    fun installedRoot(manifestId: String): File {
        requireValidId(manifestId)
        return File(installedDirectory, manifestId)
    }

    fun currentRecord(manifestId: String): RootfsInstallRecord? {
        val root = installedRoot(manifestId)
        if (!root.isDirectory) return null
        return RootfsInstallMetadata.read(root)
    }

    fun installVerifiedArchive(
        manifest: RootfsManifest,
        verifiedArchive: File,
    ): RootfsInstallRecord {
        RootfsManifestValidator.requireValid(manifest)
        require(verifiedArchive.isFile) { "Verified rootfs archive does not exist" }
        if (!Sha256.matches(verifiedArchive, manifest.sha256)) {
            throw SecurityException("Rootfs archive no longer matches the pinned SHA-256")
        }

        ensureDirectories()
        val installed = installedRoot(manifest.id)
        if (Files.exists(installed.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw RootfsInstallException("Rootfs is already installed: ${manifest.id}")
        }

        cleanupAbandonedStaging(manifest.id)
        val staging = File(stagingDirectory, "${manifest.id}.staging-${UUID.randomUUID()}")
        if (!staging.mkdir()) {
            throw RootfsInstallException("Could not create rootfs staging directory")
        }

        try {
            val extraction = SafeTarExtractor.extract(verifiedArchive, staging)
            val record = RootfsInstallRecord(
                manifestId = manifest.id,
                distribution = manifest.distribution,
                version = manifest.version,
                architecture = manifest.architecture,
                sha256 = manifest.sha256,
                installedAtEpochMillis = clockMillis(),
                entriesExtracted = extraction.entriesExtracted,
                regularFileBytes = extraction.regularFileBytes,
                specialEntriesSkipped = extraction.specialEntriesSkipped,
            )

            RootfsInstallMetadata.write(staging, record)
            val roundTrip = RootfsInstallMetadata.read(staging)
            require(roundTrip == record) { "Rootfs install metadata failed verification" }

            try {
                Files.move(
                    staging.toPath(),
                    installed.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (error: AtomicMoveNotSupportedException) {
                throw RootfsInstallException("Atomic rootfs promotion is not supported", error)
            }

            return RootfsInstallMetadata.read(installed)
                ?: throw RootfsInstallException("Installed rootfs metadata disappeared after promotion")
        } catch (error: Throwable) {
            deleteTreeWithoutFollowingLinks(staging.toPath())
            throw error
        }
    }

    fun cleanupAbandonedStaging(manifestId: String) {
        requireValidId(manifestId)
        ensureDirectories()
        val prefix = "$manifestId.staging-"
        stagingDirectory.listFiles()
            ?.filter { it.name.startsWith(prefix) }
            ?.forEach { deleteTreeWithoutFollowingLinks(it.toPath()) }
    }

    private fun ensureDirectories() {
        listOf(rootfsDirectory, downloadsDirectory, stagingDirectory, installedDirectory).forEach { directory ->
            val path = directory.toPath()
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(path)) { "Rootfs workspace must not contain symbolic-link directories" }
                require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { "Rootfs workspace path is not a directory" }
            } else {
                Files.createDirectory(path)
            }
        }
    }

    private fun requireValidId(manifestId: String) {
        require(idPattern.matches(manifestId)) { "Invalid rootfs manifest id" }
    }

    private fun deleteTreeWithoutFollowingLinks(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
