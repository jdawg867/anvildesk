package io.github.jdawg867.anvildesk.runtime

import java.nio.file.Path
import java.nio.file.Paths

object ArchivePathPolicy {
    fun resolveEntry(root: Path, entryName: String): Path {
        require(entryName.isNotBlank()) { "Archive entry name must not be blank" }
        require('\u0000' !in entryName) { "Archive entry name contains NUL" }
        require('\\' !in entryName) { "Archive entry name contains a backslash" }
        require(!entryName.startsWith('/')) { "Absolute archive paths are not allowed" }
        require(!Regex("^[A-Za-z]:").containsMatchIn(entryName)) { "Drive-qualified archive paths are not allowed" }

        val normalizedEntry = Paths.get(entryName).normalize()
        require(!normalizedEntry.isAbsolute) { "Absolute archive paths are not allowed" }
        require(normalizedEntry.toString().isNotBlank()) { "Archive entry resolves to an empty path" }
        require(!normalizedEntry.startsWith("..")) { "Archive entry escapes extraction root" }

        val normalizedRoot = root.toAbsolutePath().normalize()
        val resolved = normalizedRoot.resolve(normalizedEntry).normalize()
        require(resolved.startsWith(normalizedRoot)) { "Archive entry escapes extraction root" }
        return resolved
    }
}
