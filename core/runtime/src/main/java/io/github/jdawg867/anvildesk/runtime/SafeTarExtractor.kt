package io.github.jdawg867.anvildesk.runtime

import java.io.File
import java.io.FileInputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

data class RootfsExtractionResult(
    val entriesExtracted: Int,
    val regularFileBytes: Long,
    val specialEntriesSkipped: Int,
)

object SafeTarExtractor {
    const val DEFAULT_MAX_ENTRIES = 100_000
    const val DEFAULT_MAX_BYTES: Long = 2L * 1024L * 1024L * 1024L

    fun extract(
        archive: File,
        extractionRoot: File,
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
        maxBytes: Long = DEFAULT_MAX_BYTES,
    ): RootfsExtractionResult {
        require(archive.isFile) { "Rootfs archive does not exist" }
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(maxBytes > 0L) { "maxBytes must be positive" }

        val root = extractionRoot.toPath().toAbsolutePath().normalize()
        requireFreshDirectory(root)

        var entries = 0
        var regularBytes = 0L
        var skippedSpecial = 0
        val pendingHardLinks = mutableListOf<Pair<Path, Path>>()

        FileInputStream(archive).buffered().use { fileInput ->
            GZIPInputStream(fileInput).use { gzipInput ->
                TarArchiveInputStream(gzipInput).use { tarInput ->
                    while (true) {
                        val entry = tarInput.nextEntry as? TarArchiveEntry ?: break
                        entries += 1
                        require(entries <= maxEntries) { "Rootfs archive has too many entries" }

                        val destination = ArchivePathPolicy.resolveEntry(root, entry.name)
                        ensureSafeParents(root, destination.parent)

                        when {
                            entry.isDirectory -> createDirectory(destination, entry)
                            entry.isSymbolicLink -> createSymbolicLink(root, destination, entry)
                            entry.isLink -> {
                                val target = ArchivePathPolicy.resolveEntry(root, entry.linkName)
                                pendingHardLinks += destination to target
                            }
                            entry.isFile -> {
                                regularBytes = extractRegularFile(
                                    tarInput = tarInput,
                                    destination = destination,
                                    entry = entry,
                                    bytesBeforeEntry = regularBytes,
                                    maxBytes = maxBytes,
                                )
                            }
                            else -> skippedSpecial += 1
                        }
                    }
                }
            }
        }

        pendingHardLinks.forEach { (destination, target) ->
            ensureSafeParents(root, destination.parent)
            require(target.startsWith(root)) { "Hard-link target escapes extraction root" }
            require(Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "Hard-link target does not exist" }
            require(!Files.isSymbolicLink(target)) { "Hard-link target must not be a symbolic link" }
            require(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) { "Duplicate archive entry" }
            Files.createLink(destination, target)
        }

        return RootfsExtractionResult(entries, regularBytes, skippedSpecial)
    }

    private fun requireFreshDirectory(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(root)
            return
        }

        require(!Files.isSymbolicLink(root)) { "Extraction root must not be a symbolic link" }
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) { "Extraction root must be a directory" }
        Files.list(root).use { children ->
            require(!children.findAny().isPresent) { "Extraction root must be empty" }
        }
    }

    private fun ensureSafeParents(root: Path, parent: Path?) {
        if (parent == null) return
        require(parent.startsWith(root)) { "Archive entry parent escapes extraction root" }

        var current = root
        val relative = root.relativize(parent)
        for (component in relative) {
            current = current.resolve(component)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(current)) { "Archive entry traverses a symbolic-link parent" }
                require(Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    "Archive entry parent is not a directory"
                }
            } else {
                Files.createDirectory(current)
            }
        }
    }

    private fun createDirectory(destination: Path, entry: TarArchiveEntry) {
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(destination)) { "Directory entry collides with symbolic link" }
            require(Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
                "Directory entry collides with non-directory"
            }
        } else {
            Files.createDirectory(destination)
        }
        applyMode(destination, entry.mode)
    }

    private fun createSymbolicLink(root: Path, destination: Path, entry: TarArchiveEntry) {
        require(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) { "Duplicate archive entry" }
        val linkName = entry.linkName
        require(linkName.isNotBlank()) { "Symbolic-link target must not be blank" }
        require('\u0000' !in linkName) { "Symbolic-link target contains NUL" }
        require('\\' !in linkName) { "Symbolic-link target contains a backslash" }
        require(!linkName.startsWith('/')) { "Absolute symbolic-link targets are not allowed" }

        val target = Paths.get(linkName).normalize()
        require(!target.isAbsolute) { "Absolute symbolic-link targets are not allowed" }
        val resolvedTarget = destination.parent.resolve(target).normalize()
        require(resolvedTarget.startsWith(root)) { "Symbolic-link target escapes extraction root" }

        Files.createSymbolicLink(destination, target)
    }

    private fun extractRegularFile(
        tarInput: TarArchiveInputStream,
        destination: Path,
        entry: TarArchiveEntry,
        bytesBeforeEntry: Long,
        maxBytes: Long,
    ): Long {
        require(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) { "Duplicate archive entry" }
        if (entry.size >= 0L) {
            require(bytesBeforeEntry <= maxBytes - entry.size) { "Rootfs exceeds extraction size limit" }
        }

        var totalBytes = bytesBeforeEntry
        try {
            Files.newOutputStream(destination).buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = tarInput.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    totalBytes += read.toLong()
                    require(totalBytes <= maxBytes) { "Rootfs exceeds extraction size limit" }
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
        } catch (error: FileAlreadyExistsException) {
            throw IllegalArgumentException("Duplicate archive entry", error)
        }

        applyMode(destination, entry.mode)
        return totalBytes
    }

    private fun applyMode(path: Path, mode: Int) {
        val permissions = EnumSet.noneOf(PosixFilePermission::class.java)
        if (mode and 0b100_000_000 != 0) permissions += PosixFilePermission.OWNER_READ
        if (mode and 0b010_000_000 != 0) permissions += PosixFilePermission.OWNER_WRITE
        if (mode and 0b001_000_000 != 0) permissions += PosixFilePermission.OWNER_EXECUTE
        if (mode and 0b000_100_000 != 0) permissions += PosixFilePermission.GROUP_READ
        if (mode and 0b000_010_000 != 0) permissions += PosixFilePermission.GROUP_WRITE
        if (mode and 0b000_001_000 != 0) permissions += PosixFilePermission.GROUP_EXECUTE
        if (mode and 0b000_000_100 != 0) permissions += PosixFilePermission.OTHERS_READ
        if (mode and 0b000_000_010 != 0) permissions += PosixFilePermission.OTHERS_WRITE
        if (mode and 0b000_000_001 != 0) permissions += PosixFilePermission.OTHERS_EXECUTE
        Files.setPosixFilePermissions(path, permissions)
    }
}
