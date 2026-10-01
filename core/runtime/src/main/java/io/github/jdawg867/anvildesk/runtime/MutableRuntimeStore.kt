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

class MutableRuntimeException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class MutableRuntimeRecord(
    val baseManifestId: String,
    val baseSha256: String,
    val baseVersion: String,
    val baseArchitecture: String,
    val createdAtEpochMillis: Long,
    val clonedEntries: Int,
    val clonedRegularFileBytes: Long,
)

private data class MutableCloneStats(
    val entries: Int,
    val regularFileBytes: Long,
)

object MutableRuntimeMetadata {
    private const val SCHEMA_VERSION = 1
    private const val METADATA_FILE = "runtime.properties"

    fun write(container: File, record: MutableRuntimeRecord) {
        require(container.isDirectory) { "Mutable runtime container directory is missing" }
        val destination = File(container, METADATA_FILE)
        val temporary = File(container, ".$METADATA_FILE.part")
        val properties = Properties().apply {
            setProperty("schemaVersion", SCHEMA_VERSION.toString())
            setProperty("baseManifestId", record.baseManifestId)
            setProperty("baseSha256", record.baseSha256)
            setProperty("baseVersion", record.baseVersion)
            setProperty("baseArchitecture", record.baseArchitecture)
            setProperty("createdAtEpochMillis", record.createdAtEpochMillis.toString())
            setProperty("clonedEntries", record.clonedEntries.toString())
            setProperty("clonedRegularFileBytes", record.clonedRegularFileBytes.toString())
        }

        FileOutputStream(temporary).use { output ->
            properties.store(output, "AnvilDesk mutable runtime provenance record")
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
            throw MutableRuntimeException("Atomic mutable-runtime metadata write is not supported", error)
        }
    }

    fun read(container: File): MutableRuntimeRecord? {
        val file = File(container, METADATA_FILE)
        if (!file.isFile) return null

        val properties = Properties()
        FileInputStream(file).use(properties::load)

        return try {
            val schema = properties.requireValue("schemaVersion").toInt()
            require(schema == SCHEMA_VERSION) { "Unsupported mutable-runtime metadata schema: $schema" }
            MutableRuntimeRecord(
                baseManifestId = properties.requireValue("baseManifestId"),
                baseSha256 = properties.requireValue("baseSha256"),
                baseVersion = properties.requireValue("baseVersion"),
                baseArchitecture = properties.requireValue("baseArchitecture"),
                createdAtEpochMillis = properties.requireValue("createdAtEpochMillis").toLong(),
                clonedEntries = properties.requireValue("clonedEntries").toInt(),
                clonedRegularFileBytes = properties.requireValue("clonedRegularFileBytes").toLong(),
            )
        } catch (error: RuntimeException) {
            throw MutableRuntimeException("Mutable runtime metadata is invalid", error)
        }
    }

    private fun Properties.requireValue(key: String): String =
        getProperty(key)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing mutable-runtime metadata field: $key")
}

class MutableRuntimeStore(
    private val appFilesDirectory: File,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val idPattern = Regex("^[a-z0-9][a-z0-9._-]+$")
    private val runtimeDirectory = File(appFilesDirectory, "runtime")
    private val stagingDirectory = File(runtimeDirectory, "staging")
    private val mutableDirectory = File(runtimeDirectory, "mutable")

    fun runtimeRoot(manifestId: String): File = File(container(manifestId), "rootfs")

    fun currentRecord(manifestId: String): MutableRuntimeRecord? {
        requireValidId(manifestId)
        val container = container(manifestId)
        if (!Files.exists(container.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        requirePlainDirectory(container.toPath(), "Mutable runtime container")
        val root = runtimeRoot(manifestId)
        requirePlainDirectory(root.toPath(), "Mutable runtime rootfs")
        return MutableRuntimeMetadata.read(container)
            ?: throw MutableRuntimeException("Mutable runtime provenance metadata is missing")
    }

    fun ensureFromVerified(
        manifest: RootfsManifest,
        verifiedRoot: File,
    ): MutableRuntimeRecord {
        RootfsManifestValidator.requireValid(manifest)
        requirePlainDirectory(verifiedRoot.toPath(), "Verified rootfs")
        ensureDirectories()

        val existing = currentRecord(manifest.id)
        if (existing != null) {
            requireMatchesManifest(existing, manifest)
            return existing
        }

        cleanupAbandonedStaging(manifest.id)
        val staging = File(stagingDirectory, "${manifest.id}.staging-${UUID.randomUUID()}")
        val stagingRoot = File(staging, "rootfs")
        if (!staging.mkdir() || !stagingRoot.mkdir()) {
            deleteTreeWithoutFollowingLinks(staging.toPath())
            throw MutableRuntimeException("Could not create mutable runtime staging directories")
        }

        try {
            val stats = cloneVerifiedRootfs(
                sourceRoot = verifiedRoot.toPath(),
                destinationRoot = stagingRoot.toPath(),
            )
            val record = MutableRuntimeRecord(
                baseManifestId = manifest.id,
                baseSha256 = manifest.sha256,
                baseVersion = manifest.version,
                baseArchitecture = manifest.architecture,
                createdAtEpochMillis = clockMillis(),
                clonedEntries = stats.entries,
                clonedRegularFileBytes = stats.regularFileBytes,
            )
            MutableRuntimeMetadata.write(staging, record)
            require(MutableRuntimeMetadata.read(staging) == record) {
                "Mutable runtime provenance metadata failed verification"
            }

            val installedContainer = container(manifest.id)
            try {
                Files.move(
                    staging.toPath(),
                    installedContainer.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (error: AtomicMoveNotSupportedException) {
                throw MutableRuntimeException("Atomic mutable runtime promotion is not supported", error)
            }

            return currentRecord(manifest.id)
                ?: throw MutableRuntimeException("Mutable runtime metadata disappeared after promotion")
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

    private fun cloneVerifiedRootfs(
        sourceRoot: Path,
        destinationRoot: Path,
    ): MutableCloneStats {
        var entries = 0
        var regularFileBytes = 0L

        Files.walkFileTree(sourceRoot, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relative = sourceRoot.relativize(dir)
                if (relative.toString().isEmpty()) {
                    return FileVisitResult.CONTINUE
                }
                if (relative.getName(0).toString() == ".anvildesk") {
                    return FileVisitResult.SKIP_SUBTREE
                }

                val destination = destinationRoot.resolve(relative)
                Files.copy(
                    dir,
                    destination,
                    StandardCopyOption.COPY_ATTRIBUTES,
                    LinkOption.NOFOLLOW_LINKS,
                )
                entries += 1
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relative = sourceRoot.relativize(file)
                if (relative.getName(0).toString() == ".anvildesk") {
                    return FileVisitResult.CONTINUE
                }

                val destination = destinationRoot.resolve(relative)
                when {
                    Files.isSymbolicLink(file) -> {
                        Files.createSymbolicLink(destination, Files.readSymbolicLink(file))
                    }
                    attrs.isRegularFile -> {
                        Files.copy(
                            file,
                            destination,
                            StandardCopyOption.COPY_ATTRIBUTES,
                            LinkOption.NOFOLLOW_LINKS,
                        )
                        regularFileBytes += attrs.size()
                    }
                    else -> throw MutableRuntimeException(
                        "Verified rootfs contains unsupported entry during mutable clone: $relative",
                    )
                }
                entries += 1
                return FileVisitResult.CONTINUE
            }
        })

        return MutableCloneStats(entries, regularFileBytes)
    }

    private fun requireMatchesManifest(record: MutableRuntimeRecord, manifest: RootfsManifest) {
        require(record.baseManifestId == manifest.id) { "Mutable runtime base manifest id does not match" }
        require(record.baseSha256 == manifest.sha256) { "Mutable runtime base digest does not match" }
        require(record.baseVersion == manifest.version) { "Mutable runtime base version does not match" }
        require(record.baseArchitecture == manifest.architecture) {
            "Mutable runtime base architecture does not match"
        }
    }

    private fun container(manifestId: String): File {
        requireValidId(manifestId)
        return File(mutableDirectory, manifestId)
    }

    private fun ensureDirectories() {
        listOf(runtimeDirectory, stagingDirectory, mutableDirectory).forEach { directory ->
            val path = directory.toPath()
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                requirePlainDirectory(path, "Mutable runtime workspace")
            } else {
                Files.createDirectory(path)
            }
        }
    }

    private fun requirePlainDirectory(path: Path, label: String) {
        require(Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { "$label directory is missing" }
        require(!Files.isSymbolicLink(path)) { "$label directory must not be a symlink" }
        require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { "$label path is not a directory" }
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
