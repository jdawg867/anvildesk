package io.github.jdawg867.anvildesk.runtime

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootlessRuntimePlannerTest {
    @Test
    fun smokeTestUsesOnlyPackagedRuntimeAndFixedGuestCommand() {
        val workspace = Files.createTempDirectory("anvildesk-rootless-plan").toFile()
        try {
            val rootfs = File(workspace, "rootfs").apply { mkdirs() }
            val uname = File(rootfs, "usr/bin/uname").apply {
                parentFile.mkdirs()
                writeText("guest uname")
            }
            val nativeDir = File(workspace, "native").apply { mkdirs() }
            val proot = File(nativeDir, RootlessRuntimePlanner.PROOT_LIBRARY).apply { writeText("proot") }
            val loader = File(nativeDir, RootlessRuntimePlanner.LOADER_LIBRARY).apply { writeText("loader") }
            val temp = File(workspace, "cache").apply { mkdirs() }

            val invocation = RootlessRuntimePlanner.smokeTest(rootfs, nativeDir, temp)

            assertEquals(proot.absolutePath, invocation.command.first())
            assertEquals(
                listOf(RootlessRuntimePlanner.GUEST_UNAME, "-a"),
                invocation.command.takeLast(2),
            )
            assertTrue(invocation.command.contains("--rootfs=${rootfs.absolutePath}"))
            assertTrue(invocation.command.contains("--kill-on-exit"))
            assertTrue(invocation.command.contains("--change-id=0:0"))
            assertFalse(invocation.command.any { it == "sh" || it == "/bin/sh" || it == "-c" })

            assertEquals(loader.absolutePath, invocation.environment["PROOT_LOADER"])
            assertEquals(temp.absolutePath, invocation.environment["PROOT_TMP_DIR"])
            assertEquals("1", invocation.environment["PROOT_NO_SECCOMP"])
            assertEquals("/root", invocation.environment["HOME"])
            assertEquals("/tmp", invocation.environment["TMPDIR"])
            assertEquals("C", invocation.environment["LANG"])
            assertTrue(uname.isFile)
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun managedSessionUsesFixedShellScriptAndOnlyAppPrivateBinds() {
        val workspace = Files.createTempDirectory("anvildesk-managed-session").toFile()
        try {
            val rootfs = File(workspace, "rootfs").apply { mkdirs() }
            listOf(
                "bin/sh",
                "usr/bin/id",
                "usr/bin/uname",
                "usr/bin/cat",
                "etc/os-release",
            ).forEach { relativePath ->
                File(rootfs, relativePath).apply {
                    parentFile.mkdirs()
                    writeText(relativePath)
                }
            }

            val nativeDir = File(workspace, "native").apply { mkdirs() }
            val proot = File(nativeDir, RootlessRuntimePlanner.PROOT_LIBRARY).apply { writeText("proot") }
            val loader = File(nativeDir, RootlessRuntimePlanner.LOADER_LIBRARY).apply { writeText("loader") }
            val runtimeTemp = File(workspace, "runtime-tmp").apply { mkdirs() }
            val sessionHome = File(workspace, "session/home").apply { mkdirs() }
            val sessionTemp = File(workspace, "session/tmp").apply { mkdirs() }

            val invocation = RootlessRuntimePlanner.managedSessionVerification(
                rootfs = rootfs,
                nativeLibraryDirectory = nativeDir,
                hostTempDirectory = runtimeTemp,
                sessionHomeDirectory = sessionHome,
                sessionTempDirectory = sessionTemp,
            )

            assertEquals(proot.absolutePath, invocation.command.first())
            assertTrue(invocation.command.contains("--cwd=/root"))
            assertTrue(invocation.command.contains("--bind=${sessionHome.absolutePath}:/root!"))
            assertTrue(invocation.command.contains("--bind=${sessionTemp.absolutePath}:/tmp!"))
            assertEquals(RootlessRuntimePlanner.GUEST_SHELL, invocation.command.takeLast(3)[0])
            assertEquals("-c", invocation.command.takeLast(3)[1])

            val fixedScript = invocation.command.last()
            assertTrue(fixedScript.contains("ANVILDESK_SESSION=managed-v1"))
            assertTrue(fixedScript.contains("/usr/bin/id"))
            assertTrue(fixedScript.contains("/usr/bin/uname -a"))
            assertTrue(fixedScript.contains("/usr/bin/cat /etc/os-release"))

            val fullCommand = invocation.command.joinToString(" ")
            listOf("/sdcard", "/storage", "/system", "/vendor").forEach {
                assertFalse("Unexpected host exposure: $it", fullCommand.contains(it))
            }
            assertFalse(invocation.command.contains("--bind=/proc"))
            assertFalse(invocation.command.contains("--bind=/dev"))
            assertFalse(invocation.command.contains("--bind=/sys"))

            assertEquals(loader.absolutePath, invocation.environment["PROOT_LOADER"])
            assertEquals("/root", invocation.environment["HOME"])
            assertEquals("/tmp", invocation.environment["TMPDIR"])
            assertEquals("root", invocation.environment["USER"])
            assertEquals("root", invocation.environment["LOGNAME"])
            assertEquals("/bin/sh", invocation.environment["SHELL"])
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun packageRefreshUsesFixedAptArgvAndRestrictedNetworkBinds() {
        val workspace = Files.createTempDirectory("anvildesk-package-refresh").toFile()
        try {
            val rootfs = File(workspace, "mutable-rootfs").apply { mkdirs() }
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

            val invocation = RootlessRuntimePlanner.packageIndexRefresh(
                mutableRootfs = rootfs,
                nativeLibraryDirectory = nativeDir,
                hostTempDirectory = runtimeTemp,
                sessionHomeDirectory = sessionHome,
                sessionTempDirectory = sessionTemp,
                managedResolvConf = resolvConf,
            )

            assertEquals(proot.absolutePath, invocation.command.first())
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
            assertEquals(loader.absolutePath, invocation.environment["PROOT_LOADER"])
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun boundedOutputCaptureDrainsButRetainsOnlyConfiguredLimit() {
        val payload = "0123456789".repeat(100).toByteArray()
        val result = RootlessOutputCapture.read(
            ByteArrayInputStream(payload),
            maxBytes = 32,
        )

        assertEquals(32, result.text.toByteArray().size)
        assertTrue(result.truncated)
        assertEquals(String(payload.copyOfRange(0, 32)), result.text)
    }

    @Test
    fun boundedOutputCaptureReportsCompleteSmallOutput() {
        val payload = "managed-session-ok\n".toByteArray()
        val result = RootlessOutputCapture.read(ByteArrayInputStream(payload), maxBytes = 64)

        assertEquals("managed-session-ok\n", result.text)
        assertFalse(result.truncated)
    }

    @Test(expected = IllegalArgumentException::class)
    fun smokeTestRejectsMissingPackagedLoader() {
        val workspace = Files.createTempDirectory("anvildesk-rootless-plan").toFile()
        try {
            val rootfs = File(workspace, "rootfs").apply { mkdirs() }
            File(rootfs, "usr/bin/uname").apply {
                parentFile.mkdirs()
                writeText("guest uname")
            }
            val nativeDir = File(workspace, "native").apply { mkdirs() }
            File(nativeDir, RootlessRuntimePlanner.PROOT_LIBRARY).writeText("proot")
            val temp = File(workspace, "cache").apply { mkdirs() }

            RootlessRuntimePlanner.smokeTest(rootfs, nativeDir, temp)
        } finally {
            workspace.deleteRecursively()
        }
    }
}
