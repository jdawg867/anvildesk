package io.github.jdawg867.anvildesk.runtime

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

class RootfsDownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class RootfsDownloadResult(
    val file: File,
    val bytesDownloaded: Long,
    val sha256: String,
)

object VerifiedRootfsDownloader {
    const val DEFAULT_MAX_BYTES: Long = 256L * 1024L * 1024L
    private const val MAX_REDIRECTS = 5
    private val redirectCodes = setOf(301, 302, 303, 307, 308)

    fun download(
        manifest: RootfsManifest,
        destination: File,
        maxBytes: Long = DEFAULT_MAX_BYTES,
        onProgress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
    ): RootfsDownloadResult {
        RootfsManifestValidator.requireValid(manifest)
        require(maxBytes > 0L) { "maxBytes must be positive" }

        val parent = destination.absoluteFile.parentFile
            ?: throw RootfsDownloadException("Destination must have a parent directory")
        if (!parent.exists() && !parent.mkdirs()) {
            throw RootfsDownloadException("Could not create destination directory")
        }

        val temporary = File(parent, ".${destination.name}.part")
        if (temporary.exists() && !temporary.delete()) {
            throw RootfsDownloadException("Could not remove stale temporary download")
        }

        var connection: HttpsURLConnection? = null
        try {
            connection = openHttpsConnection(URI(manifest.url))
            val declaredLength = connection.contentLengthLong.takeIf { it >= 0L }
            if (declaredLength != null && declaredLength > maxBytes) {
                throw RootfsDownloadException("Rootfs exceeds configured download size limit")
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L

            connection.inputStream.buffered().use { input ->
                FileOutputStream(temporary).buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue

                        downloaded += read.toLong()
                        if (downloaded > maxBytes) {
                            throw RootfsDownloadException("Rootfs exceeds configured download size limit")
                        }

                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        onProgress(downloaded, declaredLength)
                    }
                    output.flush()
                }
            }

            val actualSha256 = Sha256.hex(digest.digest())
            if (actualSha256 != manifest.sha256) {
                throw SecurityException(
                    "Rootfs SHA-256 mismatch: expected ${manifest.sha256}, got $actualSha256",
                )
            }

            moveVerifiedFile(temporary, destination)
            return RootfsDownloadResult(destination, downloaded, actualSha256)
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        } finally {
            connection?.disconnect()
        }
    }

    internal fun resolveHttpsRedirect(base: URI, location: String): URI {
        val next = base.resolve(location)
        require(next.scheme.equals("https", ignoreCase = true)) { "Redirect must use HTTPS" }
        require(!next.host.isNullOrBlank()) { "Redirect must include a host" }
        require(next.userInfo == null) { "Redirect must not include user information" }
        require(next.fragment == null) { "Redirect must not include a fragment" }
        return next
    }

    private fun openHttpsConnection(initial: URI): HttpsURLConnection {
        var current = initial

        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            if (!current.scheme.equals("https", ignoreCase = true)) {
                throw RootfsDownloadException("Rootfs URL must use HTTPS")
            }

            val rawConnection = current.toURL().openConnection()
            val connection = rawConnection as? HttpsURLConnection
                ?: throw RootfsDownloadException("Rootfs connection is not HTTPS")

            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "AnvilDesk/0.1")

            val status = connection.responseCode
            if (status in redirectCodes) {
                if (redirectCount >= MAX_REDIRECTS) {
                    connection.disconnect()
                    throw RootfsDownloadException("Too many HTTPS redirects")
                }
                val location = connection.getHeaderField("Location")
                    ?: run {
                        connection.disconnect()
                        throw RootfsDownloadException("Redirect missing Location header")
                    }
                current = try {
                    resolveHttpsRedirect(current, location)
                } catch (error: IllegalArgumentException) {
                    connection.disconnect()
                    throw RootfsDownloadException("Unsafe redirect rejected", error)
                }
                connection.disconnect()
                return@repeat
            }

            if (status !in 200..299) {
                connection.errorStream?.close()
                connection.disconnect()
                throw RootfsDownloadException("Rootfs server returned HTTP $status")
            }

            return connection
        }

        throw RootfsDownloadException("Unable to establish HTTPS rootfs connection")
    }

    private fun moveVerifiedFile(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}
