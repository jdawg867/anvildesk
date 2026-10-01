package io.github.jdawg867.anvildesk.runtime

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
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
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
)

internal data class CapturedRootlessOutput(
    val text: String,
    val truncated: Boolean,
)

internal object RootlessOutputCapture {
    const val DEFAULT_LIMIT_BYTES = 64 * 1024

    fun read(
        input: InputStream,
        maxBytes: Int = DEFAULT_LIMIT_BYTES,
    ): CapturedRootlessOutput {
        require(maxBytes in 1..(1024 * 1024)) { "Rootless output limit must be between 1 byte and 1 MiB" }

        val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
        val buffer = ByteArray(8 * 1024)
        var kept = 0
        var truncated = false

        while (true) {
            val count = input.read(buffer)
            if (count < 0) break

            val remaining = maxBytes - kept
            val toKeep = minOf(count, remaining.coerceAtLeast(0))
            if (toKeep > 0) {
                output.write(buffer, 0, toKeep)
                kept += toKeep
            }
            if (toKeep < count) {
                truncated = true
            }
        }

        return CapturedRootlessOutput(
            text = output.toString(Charsets.UTF_8.name()),
            truncated = truncated,
        )
    }
}

object RootlessRuntimePlanner {
    const val PROOT_LIBRARY = "libanvildesk-proot.so"
    const val LOADER_LIBRARY = "libanvildesk-proot-loader.so"
    const val GUEST_UNAME = "/usr/bin/uname"
    const val GUEST_SHELL = "/bin/sh"
    const val GUEST_APT_GET = "/usr/bin/apt-get"

    private const val GUEST_ID = "/usr/bin/id"
    private const val GUEST_CAT = "/usr/bin/cat"
    private const val GUEST_PATH =
        "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    private const val SESSION_DIAGNOSTIC_SCRIPT =
        "set -eu\n" +
            "printf 'ANVILDESK_SESSION=managed-v1\\n'\n" +
            "printf 'PWD='; pwd\n" +
            "printf 'HOME=%s\\n' \"\$HOME\"\n" +
            "printf 'TMPDIR=%s\\n' \"\$TMPDIR\"\n" +
            "printf 'USER=%s\\n' \"\$USER\"\n" +
            "printf '%s\\n' '--- identity ---'\n" +
            "$GUEST_ID\n" +
            "printf '%s\\n' '--- kernel ---'\n" +
            "$GUEST_UNAME -a\n" +
            "printf '%s\\n' '--- os-release ---'\n" +
            "$GUEST_CAT /etc/os-release\n"

    fun smokeTest(
        rootfs: File,
        nativeLibraryDirectory: File,
        hostTempDirectory: File,
    ): RootlessRuntimeInvocation {
        val runtime = requireRuntime(rootfs, nativeLibraryDirectory, hostTempDirectory, "Verified rootfs")
        val guestUname = File(rootfs, GUEST_UNAME.removePrefix("/"))
        require(guestUname.isFile) { "Ubuntu uname binary is missing" }

        return RootlessRuntimeInvocation(
            command = baseCommand(runtime, rootfs, "/") + listOf(GUEST_UNAME, "-a"),
            environment = guestEnvironment(runtime.loader, hostTempDirectory),
        )
    }

    fun managedSessionVerification(
        rootfs: File,
        nativeLibraryDirectory: File,
        hostTempDirectory: File,
        sessionHomeDirectory: File,
        sessionTempDirectory: File,
    ): RootlessRuntimeInvocation {
        val runtime = requireRuntime(rootfs, nativeLibraryDirectory, hostTempDirectory, "Verified rootfs")
        requirePlainDirectory(sessionHomeDirectory, "Managed session home")
        requirePlainDirectory(sessionTempDirectory, "Managed session temp")

        listOf(GUEST_SHELL, GUEST_ID, GUEST_UNAME, GUEST_CAT).forEach { guestPath ->
            require(File(rootfs, guestPath.removePrefix("/")).isFile) {
                "Ubuntu guest binary is missing: $guestPath"
            }
        }
        require(File(rootfs, "etc/os-release").isFile) { "Ubuntu os-release metadata is missing" }

        val binds = listOf(
            "--bind=${sessionHomeDirectory.absolutePath}:/root!",
            "--bind=${sessionTempDirectory.absolutePath}:/tmp!",
        )

        return RootlessRuntimeInvocation(
            command = baseCommand(runtime, rootfs, "/root") + binds + listOf(
                GUEST_SHELL,
                "-c",
                SESSION_DIAGNOSTIC_SCRIPT,
            ),
            environment = guestEnvironment(runtime.loader, hostTempDirectory),
        )
    }

    fun packageIndexRefresh(
        mutableRootfs: File,
        nativeLibraryDirectory: File,
        hostTempDirectory: File,
        sessionHomeDirectory: File,
        sessionTempDirectory: File,
        managedResolvConf: File,
    ): RootlessRuntimeInvocation {
        val runtime = requireRuntime(
            mutableRootfs,
            nativeLibraryDirectory,
            hostTempDirectory,
            "Mutable runtime rootfs",
        )
        requirePlainDirectory(sessionHomeDirectory, "Managed session home")
        requirePlainDirectory(sessionTempDirectory, "Managed session temp")
        requirePlainFile(managedResolvConf, "Managed resolv.conf")
        require(File(mutableRootfs, GUEST_APT_GET.removePrefix("/")).isFile) {
            "Ubuntu apt-get binary is missing from mutable runtime"
        }

        val binds = listOf(
            "--bind=${sessionHomeDirectory.absolutePath}:/root!",
            "--bind=${sessionTempDirectory.absolutePath}:/tmp!",
            "--bind=${managedResolvConf.absolutePath}:/etc/resolv.conf!",
            "--bind=/dev/null:/dev/null!",
            "--bind=/dev/urandom:/dev/urandom!",
            "--bind=/dev/random:/dev/random!",
        )

        val environment = LinkedHashMap(guestEnvironment(runtime.loader, hostTempDirectory)).apply {
            put("DEBIAN_FRONTEND", "noninteractive")
            put("APT_LISTCHANGES_FRONTEND", "none")
        }

        return RootlessRuntimeInvocation(
            command = baseCommand(runtime, mutableRootfs, "/root") + binds + listOf(
                GUEST_APT_GET,
                "-o",
                "Acquire::Retries=2",
                "-o",
                "APT::Color=0",
                "update",
            ),
            environment = environment,
        )
    }

    private data class RuntimeFiles(
        val proot: File,
        val loader: File,
    )

    private fun requireRuntime(
        rootfs: File,
        nativeLibraryDirectory: File,
        hostTempDirectory: File,
        rootfsLabel: String,
    ): RuntimeFiles {
        requirePlainDirectory(rootfs, rootfsLabel)
        requirePlainDirectory(nativeLibraryDirectory, "Native library")
        requirePlainDirectory(hostTempDirectory, "Rootless runtime temp")

        val proot = File(nativeLibraryDirectory, PROOT_LIBRARY)
        val loader = File(nativeLibraryDirectory, LOADER_LIBRARY)
        require(proot.isFile) { "Packaged PRoot runtime is missing" }
        require(loader.isFile) { "Packaged PRoot loader is missing" }
        return RuntimeFiles(proot, loader)
    }

    private fun requirePlainDirectory(directory: File, label: String) {
        require(directory.isDirectory) { "$label directory is missing" }
        require(!Files.isSymbolicLink(directory.toPath())) { "$label directory must not be a symlink" }
    }

    private fun requirePlainFile(file: File, label: String) {
        require(file.isFile) { "$label file is missing" }
        require(!Files.isSymbolicLink(file.toPath())) { "$label file must not be a symlink" }
    }

    private fun baseCommand(
        runtime: RuntimeFiles,
        rootfs: File,
        guestWorkingDirectory: String,
    ): List<String> = listOf(
        runtime.proot.absolutePath,
        "-L",
        "--kill-on-exit",
        "--change-id=0:0",
        "--rootfs=${rootfs.absolutePath}",
        "--cwd=$guestWorkingDirectory",
    )

    private fun guestEnvironment(
        loader: File,
        hostTempDirectory: File,
    ): Map<String, String> = linkedMapOf(
        "PROOT_NO_SECCOMP" to "1",
        "PROOT_TMP_DIR" to hostTempDirectory.absolutePath,
        "PROOT_LOADER" to loader.absolutePath,
        "PATH" to GUEST_PATH,
        "HOME" to "/root",
        "USER" to "root",
        "LOGNAME" to "root",
        "SHELL" to GUEST_SHELL,
        "LANG" to "C",
        "LC_ALL" to "C",
        "TMPDIR" to "/tmp",
    )
}

class RootlessRuntimeLauncher(
    private val store: RootfsInstallStore,
    private val nativeLibraryDirectory: File,
    private val appCacheDirectory: File,
    private val sessionDataDirectory: File? = null,
    private val mutableRuntimeStore: MutableRuntimeStore? = null,
) {
    fun runUbuntuSmokeTest(
        manifest: RootfsManifest,
        timeoutMillis: Long = 10_000L,
    ): RootlessRuntimeResult {
        validateInteractiveTimeout(timeoutMillis)
        val rootfs = verifiedRootfs(manifest)
        val runtimeTemp = ensurePlainDirectory(File(appCacheDirectory, "rootless-runtime"))
        val invocation = RootlessRuntimePlanner.smokeTest(
            rootfs = rootfs,
            nativeLibraryDirectory = nativeLibraryDirectory,
            hostTempDirectory = runtimeTemp,
        )
        return execute(invocation, timeoutMillis)
    }

    fun runManagedSessionVerification(
        manifest: RootfsManifest,
        timeoutMillis: Long = 15_000L,
    ): RootlessRuntimeResult {
        validateInteractiveTimeout(timeoutMillis)
        val rootfs = verifiedRootfs(manifest)
        val runtimeTemp = ensurePlainDirectory(File(appCacheDirectory, "rootless-runtime"))
        val sessionRoot = requireSessionDataDirectory()
        val home = ensurePlainDirectory(File(sessionRoot, "home"))
        val temp = ensurePlainDirectory(File(sessionRoot, "tmp"))

        val invocation = RootlessRuntimePlanner.managedSessionVerification(
            rootfs = rootfs,
            nativeLibraryDirectory = nativeLibraryDirectory,
            hostTempDirectory = runtimeTemp,
            sessionHomeDirectory = home,
            sessionTempDirectory = temp,
        )
        return execute(invocation, timeoutMillis)
    }

    fun currentMutableRuntimeRecord(manifest: RootfsManifest): MutableRuntimeRecord? {
        verifiedRootfs(manifest)
        return requireMutableRuntimeStore().currentRecord(manifest.id)?.also {
            requireMutableRecordMatchesManifest(it, manifest)
        }
    }

    fun ensureMutablePackageRuntime(manifest: RootfsManifest): MutableRuntimeRecord {
        val verifiedRoot = verifiedRootfs(manifest)
        return requireMutableRuntimeStore().ensureFromVerified(manifest, verifiedRoot)
    }

    fun runPackageIndexRefresh(
        manifest: RootfsManifest,
        dnsServers: List<String>,
        timeoutMillis: Long = 180_000L,
    ): RootlessRuntimeResult {
        require(timeoutMillis in 10_000L..300_000L) {
            "Package runtime timeout must be between 10 and 300 seconds"
        }
        val mutableStore = requireMutableRuntimeStore()
        val verifiedRoot = verifiedRootfs(manifest)
        val record = mutableStore.ensureFromVerified(manifest, verifiedRoot)
        requireMutableRecordMatchesManifest(record, manifest)
        val mutableRoot = mutableStore.runtimeRoot(manifest.id)
        preparePackageBindTargets(mutableRoot)

        val runtimeTemp = ensurePlainDirectory(File(appCacheDirectory, "rootless-runtime"))
        val sessionRoot = requireSessionDataDirectory()
        val home = ensurePlainDirectory(File(sessionRoot, "home"))
        val temp = ensurePlainDirectory(File(sessionRoot, "tmp"))
        val network = ensurePlainDirectory(File(sessionRoot, "network"))
        val resolvConf = ManagedGuestDns.write(File(network, "resolv.conf"), dnsServers)

        val invocation = RootlessRuntimePlanner.packageIndexRefresh(
            mutableRootfs = mutableRoot,
            nativeLibraryDirectory = nativeLibraryDirectory,
            hostTempDirectory = runtimeTemp,
            sessionHomeDirectory = home,
            sessionTempDirectory = temp,
            managedResolvConf = resolvConf,
        )
        return execute(invocation, timeoutMillis)
    }

    private fun verifiedRootfs(manifest: RootfsManifest): File {
        val record = store.currentRecord(manifest.id)
            ?: throw IllegalStateException("Verified Ubuntu rootfs is not installed")
        require(record.sha256 == manifest.sha256) { "Installed rootfs digest does not match manifest" }
        require(record.version == manifest.version) { "Installed rootfs version does not match manifest" }
        require(record.architecture == manifest.architecture) {
            "Installed rootfs architecture does not match manifest"
        }
        return store.installedRoot(manifest.id)
    }

    private fun requireMutableRecordMatchesManifest(record: MutableRuntimeRecord, manifest: RootfsManifest) {
        require(record.baseManifestId == manifest.id) { "Mutable runtime base manifest id does not match" }
        require(record.baseSha256 == manifest.sha256) { "Mutable runtime base digest does not match" }
        require(record.baseVersion == manifest.version) { "Mutable runtime base version does not match" }
        require(record.baseArchitecture == manifest.architecture) {
            "Mutable runtime base architecture does not match"
        }
    }

    private fun preparePackageBindTargets(rootfs: File) {
        val dev = ensurePlainDirectory(File(rootfs, "dev"))
        listOf("null", "urandom", "random").forEach { name ->
            val target = File(dev, name)
            val path = target.toPath()
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(path)) { "Mutable runtime /dev/$name target must not be a symlink" }
                require(target.isFile) { "Mutable runtime /dev/$name target is not a file" }
            } else {
                require(target.createNewFile()) { "Could not create mutable runtime /dev/$name bind target" }
            }
        }
    }

    private fun requireSessionDataDirectory(): File = sessionDataDirectory
        ?: throw IllegalStateException("Managed session data directory is not configured")

    private fun requireMutableRuntimeStore(): MutableRuntimeStore = mutableRuntimeStore
        ?: throw IllegalStateException("Mutable runtime store is not configured")

    private fun ensurePlainDirectory(directory: File): File {
        val path = directory.toPath()
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            require(directory.isDirectory) { "Managed runtime path is not a directory: ${directory.absolutePath}" }
            require(!Files.isSymbolicLink(path)) {
                "Managed runtime directory must not be a symlink: ${directory.absolutePath}"
            }
        } else {
            require(directory.mkdirs()) { "Could not create managed runtime directory: ${directory.absolutePath}" }
        }
        return directory
    }

    private fun validateInteractiveTimeout(timeoutMillis: Long) {
        require(timeoutMillis in 1_000L..60_000L) {
            "Rootless runtime timeout must be between 1 and 60 seconds"
        }
    }

    private fun execute(
        invocation: RootlessRuntimeInvocation,
        timeoutMillis: Long,
    ): RootlessRuntimeResult {
        val processBuilder = ProcessBuilder(invocation.command)
        processBuilder.environment().apply {
            clear()
            putAll(invocation.environment)
        }

        val process = processBuilder.start()
        val readers = Executors.newFixedThreadPool(2)
        val stdoutFuture = readers.submit<CapturedRootlessOutput> {
            process.inputStream.use { RootlessOutputCapture.read(it) }
        }
        val stderrFuture = readers.submit<CapturedRootlessOutput> {
            process.errorStream.use { RootlessOutputCapture.read(it) }
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
                stdout = stdout.text,
                stderr = stderr.text,
                exitCode = exitCode,
                timedOut = timedOut,
                stdoutTruncated = stdout.truncated,
                stderrTruncated = stderr.truncated,
            )
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
            }
            readers.shutdownNow()
        }
    }

    private fun readCompletedOutput(
        future: java.util.concurrent.Future<CapturedRootlessOutput>,
        streamName: String,
    ): CapturedRootlessOutput = try {
        future.get(2L, TimeUnit.SECONDS)
    } catch (error: TimeoutException) {
        future.cancel(true)
        CapturedRootlessOutput("<$streamName capture timed out>", truncated = true)
    }
}
