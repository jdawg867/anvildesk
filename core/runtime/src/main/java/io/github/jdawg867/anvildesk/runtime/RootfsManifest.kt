package io.github.jdawg867.anvildesk.runtime

import java.net.URI

data class RootfsSignature(
    val scheme: String,
    val url: String,
    val keyFingerprint: String? = null,
)

data class RootfsManifest(
    val schemaVersion: Int,
    val id: String,
    val distribution: String,
    val version: String,
    val architecture: String,
    val archiveFormat: String,
    val url: String,
    val sha256: String,
    val sourceUrl: String,
    val checksumUrl: String,
    val sizeBytes: Long? = null,
    val signature: RootfsSignature? = null,
)

object RootfsManifestValidator {
    private val idPattern = Regex("^[a-z0-9][a-z0-9._-]+$")
    private val sha256Pattern = Regex("^[a-f0-9]{64}$")

    fun validate(manifest: RootfsManifest): List<String> = buildList {
        if (manifest.schemaVersion != 1) add("unsupported schemaVersion")
        if (!idPattern.matches(manifest.id)) add("invalid id")
        if (manifest.distribution.isBlank()) add("distribution must not be blank")
        if (manifest.version.isBlank()) add("version must not be blank")
        if (manifest.architecture != "aarch64") add("architecture must be aarch64")
        if (manifest.archiveFormat != "tar.gz") add("archiveFormat must be tar.gz")
        if (!sha256Pattern.matches(manifest.sha256)) add("sha256 must be 64 lowercase hex characters")
        if (manifest.sizeBytes != null && manifest.sizeBytes <= 0L) add("sizeBytes must be positive")

        validateHttps("url", manifest.url)?.let(::add)
        validateHttps("sourceUrl", manifest.sourceUrl)?.let(::add)
        validateHttps("checksumUrl", manifest.checksumUrl)?.let(::add)

        manifest.signature?.let { signature ->
            if (signature.scheme !in setOf("gpg", "minisign")) {
                add("unsupported signature scheme")
            }
            validateHttps("signature.url", signature.url)?.let(::add)
            if (signature.keyFingerprint != null && signature.keyFingerprint.length < 8) {
                add("signature keyFingerprint is too short")
            }
        }
    }

    fun requireValid(manifest: RootfsManifest) {
        val errors = validate(manifest)
        require(errors.isEmpty()) { "Invalid rootfs manifest: ${errors.joinToString("; ")}" }
    }

    private fun validateHttps(label: String, value: String): String? {
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            return "$label is not a valid URI"
        }

        if (!uri.scheme.equals("https", ignoreCase = true)) return "$label must use HTTPS"
        if (uri.host.isNullOrBlank()) return "$label must include a host"
        if (uri.userInfo != null) return "$label must not include user information"
        if (uri.fragment != null) return "$label must not include a fragment"
        return null
    }
}

object RootfsCatalog {
    val Ubuntu24045Arm64 = RootfsManifest(
        schemaVersion = 1,
        id = "ubuntu-24.04.5-arm64",
        distribution = "Ubuntu Base",
        version = "24.04.5",
        architecture = "aarch64",
        archiveFormat = "tar.gz",
        url = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
        sha256 = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2",
        sourceUrl = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/",
        checksumUrl = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS",
        signature = RootfsSignature(
            scheme = "gpg",
            url = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS.gpg",
        ),
    )
}
