package io.github.jdawg867.anvildesk.runtime

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object X11BootstrapPackageSet {
    const val ID = "x11-bootstrap-v1"

    val PACKAGES: List<String> = listOf(
        "dbus-x11",
        "xauth",
        "x11-utils",
        "x11-xserver-utils",
    )

    val REQUIRED_GUEST_BINARIES: List<String> = listOf(
        "/usr/bin/dbus-launch",
        "/usr/bin/xauth",
        "/usr/bin/xdpyinfo",
        "/usr/bin/xset",
    )
}

object X11BootstrapPlanner {
    const val GUEST_DPKG_QUERY = "/usr/bin/dpkg-query"

    fun refreshIndexes(
        mutableRootfs: File,
        nativeLibraryDirectory: File,
        hostTempDirectory: File,
        sessionHomeDirectory: File,
        sessionTempDirectory: File,
        managedResolvConf: File,
    ): RootlessRuntimeInvocation {
        val runtime = requireRuntime(mutableRootfs, nativeLibraryDirectory, hostTempDirectory)
        requirePackageInputs(mutableRootfs, sessionHomeDirectory, sessionTempDirectory, managedResolvConf)

        return RootlessRuntimeInvocation(
            command = baseCommand(runtime, mutableRootfs) + networkBinds(
                sessionHomeDirectory,
                sessionTempDirectory,
                managedResolvConf,
            ) + listOf(
                RootlessRuntimePlanner.GUEST_APT_GET,
                "-o",
                "Acquire::Retries=2",
                "-o",
                "APT::Color=0",
                "update",
            ),
            environment = packageEnvironment(runtime.loader, hostTempDirectory),
        )
    }

    fun install(
        mutableRootfs: File,
        nativeLibraryDirectory: File,
        hostTempDirectory: File,
        sessionHomeDirectory: File,
        sessionTempDirectory: File,
        managedResolvConf: File,
    ): RootlessRuntimeInvocation {
        val runtime = requireRuntime(mutableRootfs, nativeLibraryDirectory, hostTempDirectory)
        requirePackageInputs(mutableRootfs, sessionHomeDirectory, sessionTempDirectory, managedResolvConf)

        return RootlessRuntimeInvocation(
            command = baseCommand(runtime, mutableRootfs) + networkBinds(
                sessionHomeDirectory,
                sessionTempDirectory,
                managedResolvConf,
            ) + listOf(
                RootlessRuntimePlanner.GUEST_APT_GET,
                "-o",
                "Acquire::Retries=2",
                "-o",
                "APT::Color=0",
                "-o",
                "Dpkg::Use-Pty=0",
                "--yes",
                "--no-install-recommends",
                "install",
            ) + X11BootstrapPackageSet.PACKAGES,
            environment = packageEnvironment(runtime.loader, hostTempDirectory),
        )
    }

    fun verify(
        mutableRootfs: File,
        nativeLibraryDirectory: File,
        hostTempDirectory: File,
        sessionHomeDirectory: File,
        sessionTempDirectory: File,
    ): RootlessRuntimeInvocation {
        val runtime = requireRuntime(mutableRootfs, nativeLibraryDirectory, hostTempDirectory)
        requirePlainDirectory(sessionHomeDirectory, "Managed session home")
        requirePlainDirectory(sessionTempDirectory, "Managed session temp")
        require(File(mutableRootfs, GUEST_DPKG_QUERY.removePrefix("/")).isFile) {
            "Ubuntu dpkg-query binary is missing from mutable runtime"
        }

        val format = "\${binary:Package}\\t\${Version}\\t\${db:Status-Abbrev}\\n"
        return RootlessRuntimeInvocation(
            command = baseCommand(runtime, mutableRootfs) + listOf(
                "--bind=${sessionHomeDirectory.absolutePath}:/root!",
                "--bind=${sessionTempDirectory.absolutePath}:/tmp!",
                GUEST_DPKG_QUERY,
                "--show",
                "--showformat=$format",
            ) + X11BootstrapPackageSet.PACKAGES,
            environment = guestEnvironment(runtime.loader, hostTempDirectory),
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
    ): RuntimeFiles {
        requirePlainDirectory(rootfs, "Mutable runtime rootfs")
        requirePlainDirectory(nativeLibraryDirectory, "Native library")
        requirePlainDirectory(hostTempDirectory, "Rootless runtime temp")
        val proot = File(nativeLibraryDirectory, RootlessRuntimePlanner.PROOT_LIBRARY)
        val loader = File(nativeLibraryDirectory, RootlessRuntimePlanner.LOADER_LIBRARY)
        require(proot.isFile) { "Packaged PRoot runtime is missing" }
        require(loader.isFile) { "Packaged PRoot loader is missing" }
        return RuntimeFiles(proot, loader)
    }

    private fun requirePackageInputs(
        mutableRootfs: File,
        sessionHomeDirectory: File,
        sessionTempDirectory: File,
        managedResolvConf: File,
    ) {
        requirePlainDirectory(sessionHomeDirectory, "Managed session home")
        requirePlainDirectory(sessionTempDirectory, "Managed session temp")
        requirePlainFile(managedResolvConf, "Managed resolv.conf")
        require(File(mutableRootfs, RootlessRuntimePlanner.GUEST_APT_GET.removePrefix("/")).isFile) {
            "Ubuntu apt-get binary is missing from mutable runtime"
        }
    }

    private fun requirePlainDirectory(directory: File, label: String) {
        require(directory.isDirectory) { "$label directory is missing" }
        require(!Files.isSymbolicLink(directory.toPath())) { "$label directory must not be a symlink" }
    }

    private fun requirePlainFile(file: File, label: String) {
        require(file.isFile) { "$label file is missing" }
        require(!Files.isSymbolicLink(file.toPath())) { "$label file must not be a symlink" }
    }

    private fun networkBinds(
        home: File,
        temp: File,
        resolvConf: File,
    ): List<String> = listOf(
        "--bind=${home.absolutePath}:/root!",
        "--bind=${temp.absolutePath}:/tmp!",
        "--bind=${resolvConf.absolutePath}:/etc/resolv.conf!",
        "--bind=/dev/null:/dev/null!",
        "--bind=/dev/urandom:/dev/urandom!",
        "--bind=/dev/random:/dev/random!",
    )

    private fun baseCommand(runtime: RuntimeFiles, rootfs: File): List<String> = listOf(
        runtime.proot.absolutePath,
        "-L",
        "--kill-on-exit",
        "--change-id=0:0",
        "--rootfs=${rootfs.absolutePath}",
        "--cwd=/root",
    )

    private fun guestEnvironment(loader: File, hostTempDirectory: File): Map<String, String> = linkedMapOf(
        "PROOT_NO_SECCOMP" to "1",
        "PROOT_TMP_DIR" to hostTempDirectory.absolutePath,
        "PROOT_LOADER" to loader.absolutePath,
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "HOME" to "/root",
        "USER" to "root",
        "LOGNAME" to "root",
        "SHELL" to RootlessRuntimePlanner.GUEST_SHELL,
        "LANG" to "C",
        "LC_ALL" to "C",
        "TMPDIR" to "/tmp",
    )

    private fun packageEnvironment(loader: File, hostTempDirectory: File): Map<String, String> =
        LinkedHashMap(guestEnvironment(loader, hostTempDirectory)).apply {
            put("DEBIAN_FRONTEND", "noninteractive")
            put("APT_LISTCHANGES_FRONTEND", "none")
        }
}

class X11BootstrapLauncher(
    private val store: RootfsInstallStore,
    private val mutableRuntimeStore: MutableRuntimeStore,
    private val nativeLibraryDirectory: File,
    private val appCacheDirectory: File,
    private val sessionDataDirectory: File,
) {
    fun refreshIndexes(
        manifest: RootfsManifest,
        dnsServers: List<String>,
        timeoutMillis: Long = 180_000L,
    ): RootlessRuntimeResult {
        validatePackageTimeout(timeoutMillis)
        val context = packageContext(manifest, dnsServers)
        val invocation = X11BootstrapPlanner.refreshIndexes(
            mutableRootfs = context.mutableRoot,
            nativeLibraryDirectory = nativeLibraryDirectory,
            hostTempDirectory = context.runtimeTemp,
            sessionHomeDirectory = context.home,
            sessionTempDirectory = context.temp,
            managedResolvConf = context.resolvConf,
        )
        return execute(invocation, timeoutMillis)
    }

    fun install(
        manifest: RootfsManifest,
        dnsServers: List<String>,
        timeoutMillis: Long = 300_000L,
    ): RootlessRuntimeResult {
        validatePackageTimeout(timeoutMillis)
        val context = packageContext(manifest, dnsServers)
        val invocation = X11BootstrapPlanner.install(
            mutableRootfs = context.mutableRoot,
            nativeLibraryDirectory = nativeLibraryDirectory,
            hostTempDirectory = context.runtimeTemp,
            sessionHomeDirectory = context.home,
            sessionTempDirectory = context.temp,
            managedResolvConf = context.resolvConf,
        )
        return execute(invocation, timeoutMillis)
    }

    fun verify(
        manifest: RootfsManifest,
        timeoutMillis: Long = 30_000L,
    ): RootlessRuntimeResult {
        require(timeoutMillis in 1_000L..60_000L) {
            "X11 bootstrap verification timeout must be between 1 and 60 seconds"
        }
        val mutableRoot = mutableRootfs(manifest)
        X11BootstrapPackageSet.REQUIRED_GUEST_BINARIES.forEach { guestPath ->
            require(File(mutableRoot, guestPath.removePrefix("/")).isFile) {
                "X11 bootstrap binary is missing after package install: $guestPath"
            }
        }

        val runtimeTemp = ensurePlainDirectory(File(appCacheDirectory, "rootless-runtime"))
        val home = ensurePlainDirectory(File(sessionDataDirectory, "home"))
        val temp = ensurePlainDirectory(File(sessionDataDirectory, "tmp"))
        val invocation = X11BootstrapPlanner.verify(
            mutableRootfs = mutableRoot,
            nativeLibraryDirectory = nativeLibraryDirectory,
            hostTempDirectory = runtimeTemp,
            sessionHomeDirectory = home,
            sessionTempDirectory = temp,
        )
        return execute(invocation, timeoutMillis)
    }

    fun binariesPresent(manifest: RootfsManifest): Boolean {
        verifiedRootfs(manifest)
        val record = mutableRuntimeStore.currentRecord(manifest.id) ?: return false
        requireMutableRecordMatchesManifest(record, manifest)
        val root = mutableRuntimeStore.runtimeRoot(manifest.id)
        return X11BootstrapPackageSet.REQUIRED_GUEST_BINARIES.all { guestPath ->
            File(root, guestPath.removePrefix("/")).isFile
        }
    }

    private data class PackageContext(
        val mutableRoot: File,
        val runtimeTemp: File,
        val home: File,
        val temp: File,
        val resolvConf: File,
    )

    private fun packageContext(manifest: RootfsManifest, dnsServers: List<String>): PackageContext {
        val mutableRoot = mutableRootfs(manifest)
        preparePackageBindTargets(mutableRoot)
        val runtimeTemp = ensurePlainDirectory(File(appCacheDirectory, "rootless-runtime"))
        val home = ensurePlainDirectory(File(sessionDataDirectory, "home"))
        val temp = ensurePlainDirectory(File(sessionDataDirectory, "tmp"))
        val network = ensurePlainDirectory(File(sessionDataDirectory, "network"))
        val resolvConf = ManagedGuestDns.write(File(network, "resolv.conf"), dnsServers)
        return PackageContext(mutableRoot, runtimeTemp, home, temp, resolvConf)
    }

    private fun mutableRootfs(manifest: RootfsManifest): File {
        val verified = verifiedRootfs(manifest)
        val record = mutableRuntimeStore.ensureFromVerified(manifest, verified)
        requireMutableRecordMatchesManifest(record, manifest)
        return mutableRuntimeStore.runtimeRoot(manifest.id)
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

    private fun validatePackageTimeout(timeoutMillis: Long) {
        require(timeoutMillis in 10_000L..300_000L) {
            "Package runtime timeout must be between 10 and 300 seconds"
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
            if (process.isAlive) process.destroyForcibly()
            readers.shutdown()
            if (!readers.awaitTermination(250L, TimeUnit.MILLISECONDS)) {
                readers.shutdownNow()
            }
        }
    }

    private fun readCompletedOutput(
        future: java.util.concurrent.Future<CapturedRootlessOutput>,
        streamName: String,
    ): CapturedRootlessOutput = try {
        future.get(5L, TimeUnit.SECONDS)
    } catch (error: TimeoutException) {
        future.cancel(true)
        CapturedRootlessOutput("<$streamName capture timed out after guest process exit>", truncated = true)
    } catch (_: CancellationException) {
        CapturedRootlessOutput("<$streamName capture cancelled after guest process exit>", truncated = true)
    } catch (error: ExecutionException) {
        val cause = error.cause
        if (cause is IOException) {
            CapturedRootlessOutput(
                "<$streamName pipe closed after guest process exit: ${cause::class.java.simpleName}>",
                truncated = true,
            )
        } else {
            throw (cause ?: error)
        }
    }
}
