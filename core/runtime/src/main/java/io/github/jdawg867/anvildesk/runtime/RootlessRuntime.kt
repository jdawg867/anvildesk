package io.github.jdawg867.anvildesk.runtime

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class RootlessRuntimeInvocation(
    val command: List<String>,
    val environment: Map<String, String>,
)

data class RootlessRuntimeResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    val timedOut: Boolean,
)

object RootlessRuntimePlanner {
    const val PROOT_LIBRARY = "libanvildesk-proot.so"
    const val LOADER_LIBRARY = "libanvildesk-proot-loader.so"
    const val GUEST_UNAME = "/usr/bin/uname"

    private const val GUEST_PATH =
        "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    fun smokeTest(
        rootfs: File,
        nativeLibraryDirectory: File,
        hostTempDirectory: File,
    ): RootlessRuntimeInvocation {
        require(rootfs.isDirectory) { "Verified rootfs directory is missing" }
        require(nativeLibraryDirectory.isDirectory) { "Native library directory is missing" }
        require(hostTempDirectory.isDirectory) { "Rootless runtime temp directory is missing" }

        val proot = File(nativeLibraryDirectory, PROOT_LIBRARY)
        val loader = File(nativeLibraryDirectory, LOADER_LIBRARY)
        val guestUname = File(rootfs, GUEST_UNAME.removePrefix("/"))

        require(proot.isFile) { "Packaged PRoot runtime is missing" }
        require(loader.isFile) { "Packaged PRoot loader is missing" }
        require(guestUname.isFile) { "Ubuntu uname binary is missing" }

        return RootlessRuntimeInvocation(
            command = listOf(
                proot.absolutePath,
                "-L",
                "--kill-on-exit",
                "--change-id=0:0",
                "--rootfs=${rootfs.absolutePath}",
                "--cwd=/",
                GUEST_UNAME,
                "-a",
            ),
            environment = linkedMapOf(
                "PROOT_NO_SECCOMP" to "1",
                "PROOT_TMP_DIR" to hostTempDirectory.absolutePath,
                "PROOT_LOADER" to loader.absolutePath,
                "PATH" to GUEST_PATH,
                "HOME" to "/root",
                "USER" to "root",
                "LANG" to "C",
                "LC_ALL" to "C",
                "TMPDIR" to "/tmp",
            ),
        )
    }
}

class RootlessRuntimeLauncher(
    private val store: RootfsInstallStore,
    private val nativeLibraryDirectory: File,
    private val appCacheDirectory: File,
) {
    fun runUbuntuSmokeTest(
        manifest: RootfsManifest,
        timeoutMillis: Long = 10_000L,
    ): RootlessRuntimeResult {
        require(timeoutMillis in 1_000L..60_000L) {
            "Rootless runtime timeout must be between 1 and 60 seconds"
        }

        val record = store.currentRecord(manifest.id)
            ?: throw IllegalStateException("Verified Ubuntu rootfs is not installed")
        require(record.sha256 == manifest.sha256) { "Installed rootfs digest does not match manifest" }
        require(record.version == manifest.version) { "Installed rootfs version does not match manifest" }
        require(record.architecture == manifest.architecture) {
            "Installed rootfs architecture does not match manifest"
        }

        val runtimeTemp = File(appCacheDirectory, "rootless-runtime")
        if (runtimeTemp.exists()) {
            require(runtimeTemp.isDirectory) { "Rootless runtime temp path is not a directory" }
        } else {
            require(runtimeTemp.mkdirs()) { "Could not create rootless runtime temp directory" }
        }

        val invocation = RootlessRuntimePlanner.smokeTest(
            rootfs = store.installedRoot(manifest.id),
            nativeLibraryDirectory = nativeLibraryDirectory,
            hostTempDirectory = runtimeTemp,
        )

        val processBuilder = ProcessBuilder(invocation.command)
        processBuilder.environment().apply {
            clear()
            putAll(invocation.environment)
        }

        val process = processBuilder.start()
        val readers = Executors.newFixedThreadPool(2)
        val stdoutFuture = readers.submit<String> {
            process.inputStream.bufferedReader().use { it.readText() }
        }
        val stderrFuture = readers.submit<String> {
            process.errorStream.bufferedReader().use { it.readText() }
        }

        var timedOut = false
        var exitCode: Int? = null
        try {
            if (process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                exitCode = process.exitValue()
            } else {
                timedOut = true
                process.destroy()
                if (!process.waitFor(500L, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(2L, TimeUnit.SECONDS)
                }
            }

            val stdout = readCompletedOutput(stdoutFuture, "stdout")
            val stderr = readCompletedOutput(stderrFuture, "stderr")
            return RootlessRuntimeResult(
                stdout = stdout,
                stderr = stderr,
                exitCode = exitCode,
                timedOut = timedOut,
            )
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
            }
            readers.shutdownNow()
        }
    }

    private fun readCompletedOutput(
        future: java.util.concurrent.Future<String>,
        streamName: String,
    ): String = try {
        future.get(2L, TimeUnit.SECONDS)
    } catch (error: TimeoutException) {
        future.cancel(true)
        "<$streamName capture timed out>"
    }
}
