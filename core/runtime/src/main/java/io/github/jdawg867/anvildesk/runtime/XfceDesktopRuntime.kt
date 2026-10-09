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

object XfceDesktopPackageSet {
    const val ID = "xfce-desktop-core-v1"
    const val REFRESH_TIMEOUT_MILLIS = 300_000L
    const val INSTALL_TIMEOUT_MILLIS = 600_000L

    val PACKAGES: List<String> = listOf(
        "dbus-x11",
        "xfce4-session",
        "xfce4-panel",
        "xfwm4",
        "xfdesktop4",
        "xfce4-settings",
        "xfce4-appfinder",
        "thunar",
        "xfce4-terminal",
    )

    val REQUIRED_GUEST_BINARIES: List<String> = listOf(
        "/usr/bin/dbus-launch",
        "/usr/bin/startxfce4",
        "/usr/bin/xfce4-session",
        "/usr/bin/xfce4-panel",
        "/usr/bin/xfwm4",
        "/usr/bin/xfdesktop",
        "/usr/bin/xfsettingsd",
        "/usr/bin/xfce4-appfinder",
        "/usr/bin/thunar",
        "/usr/bin/xfce4-terminal",
    )
}

object XfceDesktopPlanner {
    const val GUEST_DPKG_QUERY = "/usr/bin/dpkg-query"
    const val GUEST_DBUS_LAUNCH = "/usr/bin/dbus-launch"
    const val GUEST_START_XFCE4 = "/usr/bin/startxfce4"
    const val GUEST_XDG_RUNTIME = "/tmp/anvildesk-xdg-runtime"

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
            command = baseCommand(runtime, mutableRootfs, link2symlink = false) +
                networkBinds(sessionHomeDirectory, sessionTempDirectory, managedResolvConf) +
                listOf(
                    RootlessRuntimePlanner.GUEST_APT_GET,
                    "-o",
                    "Acquire::Retries=2",
                    "-o",
                    "APT::Color=0",
                    "update",
                ),
            environment = packageEnvironment(
                runtime.loader,
                hostTempDirectory,
                mutableRootfs,
                link2symlink = false,
            ),
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
        requirePlainDirectory(File(mutableRootfs, ".l2s"), "PRoot link2symlink state")

        return RootlessRuntimeInvocation(
            command = baseCommand(runtime, mutableRootfs, link2symlink = true) +
                networkBinds(sessionHomeDirectory, sessionTempDirectory, managedResolvConf) +
                listOf(
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
                ) + XfceDesktopPackageSet.PACKAGES,
            environment = packageEnvironment(
                runtime.loader,
                hostTempDirectory,
                mutableRootfs,
                link2symlink = true,
            ),
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
            command = baseCommand(runtime, mutableRootfs, link2symlink = false) + listOf(
                "--bind=${sessionHomeDirectory.absolutePath}:/root!",
                "--bind=${sessionTempDirectory.absolutePath}:/tmp!",
                GUEST_DPKG_QUERY,
                "--show",
                "--showformat=$format",
            ) + XfceDesktopPackageSet.PACKAGES,
            environment = guestEnvironment(
                runtime.loader,
                hostTempDirectory,
                mutableRootfs,
                link2symlink = false,
            ),
        )
    }

    fun managedSession(
        mutableRootfs: File,
        nativeLibraryDirectory: File,
        hostTempDirectory: File,
        sessionHomeDirectory: File,
        sessionTempDirectory: File,
        displayNumber: Int,
    ): RootlessRuntimeInvocation {
        require(displayNumber in 0..99) { "Managed XFCE display number must be between 0 and 99" }
        val runtime = requireRuntime(mutableRootfs, nativeLibraryDirectory, hostTempDirectory)
        requirePlainDirectory(sessionHomeDirectory, "Managed session home")
        requirePlainDirectory(sessionTempDirectory, "Managed session temp")
        requirePlainDirectory(
            File(sessionTempDirectory, GUEST_XDG_RUNTIME.removePrefix("/tmp/")),
            "Managed XDG runtime",
        )
        XfceDesktopPackageSet.REQUIRED_GUEST_BINARIES.forEach { guestPath ->
            require(File(mutableRootfs, guestPath.removePrefix("/")).isFile) {
                "XFCE guest binary is missing: $guestPath"
            }
        }

        val environment = LinkedHashMap(
            guestEnvironment(runtime.loader, hostTempDirectory, mutableRootfs, link2symlink = false),
        ).apply {
            put("DISPLAY", ":$displayNumber")
            put("XDG_RUNTIME_DIR", GUEST_XDG_RUNTIME)
            put("XDG_CONFIG_HOME", "/root/.config")
            put("XDG_CACHE_HOME", "/root/.cache")
            put("XDG_DATA_HOME", "/root/.local/share")
            put("XDG_CURRENT_DESKTOP", "XFCE")
            put("XDG_SESSION_DESKTOP", "xfce")
            put("XDG_SESSION_TYPE", "x11")
            put("GDK_BACKEND", "x11")
        }

        return RootlessRuntimeInvocation(
            command = baseCommand(runtime, mutableRootfs, link2symlink = false) + listOf(
                "--bind=${sessionHomeDirectory.absolutePath}:/root!",
                "--bind=${sessionTempDirectory.absolutePath}:/tmp!",
                GUEST_DBUS_LAUNCH,
                "--exit-with-session",
                GUEST_START_XFCE4,
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

    private fun baseCommand(
        runtime: RuntimeFiles,
        rootfs: File,
        link2symlink: Boolean,
    ): List<String> = buildList {
        add(runtime.proot.absolutePath)
        add("-L")
        if (link2symlink) add("--link2symlink")
        add("--kill-on-exit")
        add("--change-id=0:0")
        add("--rootfs=${rootfs.absolutePath}")
        add("--cwd=/root")
    }

    private fun guestEnvironment(
        loader: File,
        hostTempDirectory: File,
        mutableRootfs: File,
        link2symlink: Boolean,
    ): Map<String, String> = linkedMapOf<String, String>().apply {
        put("PROOT_NO_SECCOMP", "1")
        put("PROOT_TMP_DIR", hostTempDirectory.absolutePath)
        if (link2symlink) {
            put("PROOT_L2S_DIR", File(mutableRootfs, ".l2s").absolutePath)
        }
        put("PROOT_LOADER", loader.absolutePath)
        put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        put("HOME", "/root")
        put("USER", "root")
        put("LOGNAME", "root")
        put("SHELL", RootlessRuntimePlanner.GUEST_SHELL)
        put("LANG", "C")
        put("LC_ALL", "C")
        put("TMPDIR", "/tmp")
    }

    private fun packageEnvironment(
        loader: File,
        hostTempDirectory: File,
        mutableRootfs: File,
        link2symlink: Boolean,
    ): Map<String, String> =
        LinkedHashMap(guestEnvironment(loader, hostTempDirectory, mutableRootfs, link2symlink)).apply {
            put("DEBIAN_FRONTEND", "noninteractive")
            put("APT_LISTCHANGES_FRONTEND", "none")
        }
}

class XfceDesktopLauncher(
    private val store: RootfsInstallStore,
    private val mutableRuntimeStore: MutableRuntimeStore,
    private val nativeLibraryDirectory: File,
    private val appCacheDirectory: File,
    private val sessionDataDirectory: File,
) {
    fun refreshIndexes(
        manifest: RootfsManifest,
        dnsServers: List<String>,
        timeoutMillis: Long = XfceDesktopPackageSet.REFRESH_TIMEOUT_MILLIS,
    ): RootlessRuntimeResult {
        validatePackageTimeout(timeoutMillis)
        val context = packageContext(manifest, dnsServers)
        return execute(
            XfceDesktopPlanner.refreshIndexes(
                mutableRootfs = context.mutableRoot,
                nativeLibraryDirectory = nativeLibraryDirectory,
                hostTempDirectory = context.runtimeTemp,
                sessionHomeDirectory = context.home,
                sessionTempDirectory = context.temp,
                managedResolvConf = context.resolvConf,
            ),
            timeoutMillis,
        )
    }

    fun install(
        manifest: RootfsManifest,
        dnsServers: List<String>,
        timeoutMillis: Long = XfceDesktopPackageSet.INSTALL_TIMEOUT_MILLIS,
    ): RootlessRuntimeResult {
        validatePackageTimeout(timeoutMillis)
        val context = packageContext(manifest, dnsServers)
        return execute(
            XfceDesktopPlanner.install(
                mutableRootfs = context.mutableRoot,
                nativeLibraryDirectory = nativeLibraryDirectory,
                hostTempDirectory = context.runtimeTemp,
                sessionHomeDirectory = context.home,
                sessionTempDirectory = context.temp,
                managedResolvConf = context.resolvConf,
            ),
            timeoutMillis,
        )
    }

    fun verify(
        manifest: RootfsManifest,
        timeoutMillis: Long = 60_000L,
    ): RootlessRuntimeResult {
        require(timeoutMillis in 1_000L..60_000L) {
            "XFCE desktop verification timeout must be between 1 and 60 seconds"
        }
        val mutableRoot = mutableRootfs(manifest)
        requireBinaries(mutableRoot)
        val runtimeTemp = ensurePlainDirectory(File(appCacheDirectory, "rootless-runtime"))
        val home = ensurePlainDirectory(File(sessionDataDirectory, "home"))
        val temp = ensurePlainDirectory(File(sessionDataDirectory, "tmp"))
        return execute(
            XfceDesktopPlanner.verify(
                mutableRootfs = mutableRoot,
                nativeLibraryDirectory = nativeLibraryDirectory,
                hostTempDirectory = runtimeTemp,
                sessionHomeDirectory = home,
                sessionTempDirectory = temp,
            ),
            timeoutMillis,
        )
    }

    fun binariesPresent(manifest: RootfsManifest): Boolean {
        val root = mutableRootfs(manifest)
        return XfceDesktopPackageSet.REQUIRED_GUEST_BINARIES.all { guestPath ->
            File(root, guestPath.removePrefix("/")).isFile
        }
    }

    fun sessionPreview(
        manifest: RootfsManifest,
        displayNumber: Int = 0,
    ): RootlessRuntimeInvocation {
        val mutableRoot = mutableRootfs(manifest)
        requireBinaries(mutableRoot)
        val runtimeTemp = ensurePlainDirectory(File(appCacheDirectory, "rootless-runtime"))
        val home = ensurePlainDirectory(File(sessionDataDirectory, "home"))
        ensurePlainDirectory(File(home, ".config"))
        ensurePlainDirectory(File(home, ".cache"))
        ensurePlainDirectory(File(home, ".local/share"))
        val temp = ensurePlainDirectory(File(sessionDataDirectory, "tmp"))
        ensurePlainDirectory(File(temp, "anvildesk-xdg-runtime"))

        return XfceDesktopPlanner.managedSession(
            mutableRootfs = mutableRoot,
            nativeLibraryDirectory = nativeLibraryDirectory,
            hostTempDirectory = runtimeTemp,
            sessionHomeDirectory = home,
            sessionTempDirectory = temp,
            displayNumber = displayNumber,
        )
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
        val root = mutableRuntimeStore.runtimeRoot(manifest.id)
        ensurePlainDirectory(File(root, ".l2s"))
        return root
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

    private fun requireBinaries(rootfs: File) {
        XfceDesktopPackageSet.REQUIRED_GUEST_BINARIES.forEach { guestPath ->
            require(File(rootfs, guestPath.removePrefix("/")).isFile) {
                "XFCE desktop binary is missing after package install: $guestPath"
            }
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
        require(timeoutMillis in 10_000L..XfceDesktopPackageSet.INSTALL_TIMEOUT_MILLIS) {
            "XFCE package runtime timeout must be between 10 and 600 seconds"
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
