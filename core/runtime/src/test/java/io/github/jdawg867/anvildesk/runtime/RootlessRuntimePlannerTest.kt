package io.github.jdawg867.anvildesk.runtime

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
