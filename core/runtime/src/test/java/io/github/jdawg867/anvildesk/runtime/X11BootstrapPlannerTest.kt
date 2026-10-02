package io.github.jdawg867.anvildesk.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class X11BootstrapPlannerTest {
    @Test
    fun packageSetIsFixedAndMinimal() {
        assertEquals("x11-bootstrap-v1", X11BootstrapPackageSet.ID)
        assertEquals(
            listOf("dbus-x11", "xauth", "x11-utils", "x11-xserver-utils"),
            X11BootstrapPackageSet.PACKAGES,
        )
        assertEquals(
            listOf("/usr/bin/dbus-launch", "/usr/bin/xauth", "/usr/bin/xdpyinfo", "/usr/bin/xset"),
            X11BootstrapPackageSet.REQUIRED_GUEST_BINARIES,
        )
    }

    @Test
    fun refreshUsesExactAptArgvAndRestrictedNetworkBinds() {
        val workspace = Files.createTempDirectory("anvildesk-x11-bootstrap-refresh").toFile()
        try {
            val rootfs = File(workspace, "mutable-rootfs").apply { mkdirs() }
            val l2s = File(rootfs, ".l2s").apply { mkdirs() }
            File(rootfs, "usr/bin/apt-get").apply {
                parentFile.mkdirs()
                writeText("apt")
            }
            val nativeDir = File(workspace, "native").apply { mkdirs() }
            val proot = File(nativeDir, RootlessRuntimePlanner.PROOT_LIBRARY).apply { writeText("proot") }
            val loader = File(nativeDir, RootlessRuntimePlanner.LOADER_LIBRARY).apply { writeText("loader") }
            val runtimeTemp = File(workspace, "runtime-tmp").apply { mkdirs() }
            val sessionHome = File(workspace, "session/home").apply { mkdirs() }
            val sessionTemp = File(workspace, "session/tmp").apply { mkdirs() }
            val resolvConf = File(workspace, "session/network/resolv.conf").apply {
                parentFile.mkdirs()
                writeText("nameserver 8.8.8.8\n")
            }

            val invocation = X11BootstrapPlanner.refreshIndexes(
                mutableRootfs = rootfs,
                nativeLibraryDirectory = nativeDir,
                hostTempDirectory = runtimeTemp,
                sessionHomeDirectory = sessionHome,
                sessionTempDirectory = sessionTemp,
                managedResolvConf = resolvConf,
            )

            assertEquals(proot.absolutePath, invocation.command.first())
            assertTrue(invocation.command.contains("-L"))
            assertTrue(invocation.command.contains("--link2symlink"))
            assertTrue(invocation.command.contains("--rootfs=${rootfs.absolutePath}"))
            assertTrue(invocation.command.contains("--bind=${sessionHome.absolutePath}:/root!"))
            assertTrue(invocation.command.contains("--bind=${sessionTemp.absolutePath}:/tmp!"))
            assertTrue(invocation.command.contains("--bind=${resolvConf.absolutePath}:/etc/resolv.conf!"))
            assertTrue(invocation.command.contains("--bind=/dev/null:/dev/null!"))
            assertTrue(invocation.command.contains("--bind=/dev/urandom:/dev/urandom!"))
            assertTrue(invocation.command.contains("--bind=/dev/random:/dev/random!"))
            assertFalse(invocation.command.contains("--bind=/dev"))
            assertFalse(invocation.command.contains("--bind=/proc"))
            assertFalse(invocation.command.contains("--bind=/sys"))

            val expectedTail = listOf(
                RootlessRuntimePlanner.GUEST_APT_GET,
                "-o",
                "Acquire::Retries=2",
                "-o",
                "APT::Color=0",
                "update",
            )
            assertEquals(expectedTail, invocation.command.takeLast(expectedTail.size))
            assertFalse(invocation.command.contains(RootlessRuntimePlanner.GUEST_SHELL))
            assertFalse(invocation.command.contains("-c"))

            val fullCommand = invocation.command.joinToString(" ")
            listOf("/sdcard", "/storage", "/system", "/vendor").forEach {
                assertFalse("Unexpected host exposure: $it", fullCommand.contains(it))
            }
            assertEquals("noninteractive", invocation.environment["DEBIAN_FRONTEND"])
            assertEquals("none", invocation.environment["APT_LISTCHANGES_FRONTEND"])
            assertEquals(l2s.absolutePath, invocation.environment["PROOT_L2S_DIR"])
            assertEquals(loader.absolutePath, invocation.environment["PROOT_LOADER"])
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun installUsesExactAptArgvAndExistingRestrictedNetworkBinds() {
        val workspace = Files.createTempDirectory("anvildesk-x11-bootstrap-install").toFile()
        try {
            val rootfs = File(workspace, "mutable-rootfs").apply { mkdirs() }
            val l2s = File(rootfs, ".l2s").apply { mkdirs() }
            File(rootfs, "usr/bin/apt-get").apply {
                parentFile.mkdirs()
                writeText("apt")
            }
            val nativeDir = File(workspace, "native").apply { mkdirs() }
            val proot = File(nativeDir, RootlessRuntimePlanner.PROOT_LIBRARY).apply { writeText("proot") }
            val loader = File(nativeDir, RootlessRuntimePlanner.LOADER_LIBRARY).apply { writeText("loader") }
            val runtimeTemp = File(workspace, "runtime-tmp").apply { mkdirs() }
            val sessionHome = File(workspace, "session/home").apply { mkdirs() }
            val sessionTemp = File(workspace, "session/tmp").apply { mkdirs() }
            val resolvConf = File(workspace, "session/network/resolv.conf").apply {
                parentFile.mkdirs()
                writeText("nameserver 8.8.8.8\n")
            }

            val invocation = X11BootstrapPlanner.install(
                mutableRootfs = rootfs,
                nativeLibraryDirectory = nativeDir,
                hostTempDirectory = runtimeTemp,
                sessionHomeDirectory = sessionHome,
                sessionTempDirectory = sessionTemp,
                managedResolvConf = resolvConf,
            )

            assertEquals(proot.absolutePath, invocation.command.first())
            assertTrue(invocation.command.contains("-L"))
            assertTrue(invocation.command.contains("--link2symlink"))
            assertTrue(invocation.command.contains("--rootfs=${rootfs.absolutePath}"))
            assertTrue(invocation.command.contains("--bind=${sessionHome.absolutePath}:/root!"))
            assertTrue(invocation.command.contains("--bind=${sessionTemp.absolutePath}:/tmp!"))
            assertTrue(invocation.command.contains("--bind=${resolvConf.absolutePath}:/etc/resolv.conf!"))
            assertTrue(invocation.command.contains("--bind=/dev/null:/dev/null!"))
            assertTrue(invocation.command.contains("--bind=/dev/urandom:/dev/urandom!"))
            assertTrue(invocation.command.contains("--bind=/dev/random:/dev/random!"))
            assertFalse(invocation.command.contains("--bind=/dev"))
            assertFalse(invocation.command.contains("--bind=/proc"))
            assertFalse(invocation.command.contains("--bind=/sys"))

            val expectedTail = listOf(
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
            ) + X11BootstrapPackageSet.PACKAGES
            assertEquals(expectedTail, invocation.command.takeLast(expectedTail.size))
            assertFalse(invocation.command.contains(RootlessRuntimePlanner.GUEST_SHELL))
            assertFalse(invocation.command.contains("-c"))

            val fullCommand = invocation.command.joinToString(" ")
            listOf("/sdcard", "/storage", "/system", "/vendor").forEach {
                assertFalse("Unexpected host exposure: $it", fullCommand.contains(it))
            }
            assertEquals("noninteractive", invocation.environment["DEBIAN_FRONTEND"])
            assertEquals("none", invocation.environment["APT_LISTCHANGES_FRONTEND"])
            assertEquals(l2s.absolutePath, invocation.environment["PROOT_L2S_DIR"])
            assertEquals(loader.absolutePath, invocation.environment["PROOT_LOADER"])
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun verificationUsesFixedDpkgQueryWithoutNetworkOrShell() {
        val workspace = Files.createTempDirectory("anvildesk-x11-bootstrap-verify").toFile()
        try {
            val rootfs = File(workspace, "mutable-rootfs").apply { mkdirs() }
            val l2s = File(rootfs, ".l2s").apply { mkdirs() }
            File(rootfs, "usr/bin/dpkg-query").apply {
                parentFile.mkdirs()
                writeText("dpkg-query")
            }
            val nativeDir = File(workspace, "native").apply { mkdirs() }
            val proot = File(nativeDir, RootlessRuntimePlanner.PROOT_LIBRARY).apply { writeText("proot") }
            val loader = File(nativeDir, RootlessRuntimePlanner.LOADER_LIBRARY).apply { writeText("loader") }
            val runtimeTemp = File(workspace, "runtime-tmp").apply { mkdirs() }
            val sessionHome = File(workspace, "session/home").apply { mkdirs() }
            val sessionTemp = File(workspace, "session/tmp").apply { mkdirs() }

            val invocation = X11BootstrapPlanner.verify(
                mutableRootfs = rootfs,
                nativeLibraryDirectory = nativeDir,
                hostTempDirectory = runtimeTemp,
                sessionHomeDirectory = sessionHome,
                sessionTempDirectory = sessionTemp,
            )

            val expectedTail = listOf(
                X11BootstrapPlanner.GUEST_DPKG_QUERY,
                "--show",
                "--showformat=\${binary:Package}\\t\${Version}\\t\${db:Status-Abbrev}\\n",
            ) + X11BootstrapPackageSet.PACKAGES
            assertEquals(expectedTail, invocation.command.takeLast(expectedTail.size))
            assertTrue(invocation.command.contains("-L"))
            assertTrue(invocation.command.contains("--link2symlink"))
            assertTrue(invocation.command.contains("--bind=${sessionHome.absolutePath}:/root!"))
            assertTrue(invocation.command.contains("--bind=${sessionTemp.absolutePath}:/tmp!"))
            assertFalse(invocation.command.any { it.contains("/etc/resolv.conf") })
            assertFalse(invocation.command.contains("--bind=/dev"))
            assertFalse(invocation.command.contains("--bind=/proc"))
            assertFalse(invocation.command.contains("--bind=/sys"))
            assertFalse(invocation.command.contains(RootlessRuntimePlanner.GUEST_SHELL))
            assertFalse(invocation.command.contains("-c"))

            val fullCommand = invocation.command.joinToString(" ")
            listOf("/sdcard", "/storage", "/system", "/vendor").forEach {
                assertFalse("Unexpected host exposure: $it", fullCommand.contains(it))
            }
            assertEquals(l2s.absolutePath, invocation.environment["PROOT_L2S_DIR"])
            assertEquals(loader.absolutePath, invocation.environment["PROOT_LOADER"])
        } finally {
            workspace.deleteRecursively()
        }
    }
}
