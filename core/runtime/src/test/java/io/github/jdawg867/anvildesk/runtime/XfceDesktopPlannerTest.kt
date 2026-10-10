package io.github.jdawg867.anvildesk.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class XfceDesktopPlannerTest {
    @Test
    fun packageSetIsFixedAndDesktopFocused() {
        assertEquals("xfce-desktop-core-v1", XfceDesktopPackageSet.ID)
        assertEquals(300_000L, XfceDesktopPackageSet.REFRESH_TIMEOUT_MILLIS)
        assertEquals(600_000L, XfceDesktopPackageSet.INSTALL_TIMEOUT_MILLIS)
        assertEquals(
            listOf(
                "dbus-x11",
                "xfce4-session",
                "xfce4-panel",
                "xfwm4",
                "xfdesktop4",
                "xfce4-settings",
                "xfce4-appfinder",
                "thunar",
            ),
            XfceDesktopPackageSet.PACKAGES,
        )
        assertEquals(
            listOf(
                "/usr/bin/dbus-launch",
                "/usr/bin/startxfce4",
                "/usr/bin/xfce4-session",
                "/usr/bin/xfce4-panel",
                "/usr/bin/xfwm4",
                "/usr/bin/xfdesktop",
                "/usr/bin/xfsettingsd",
                "/usr/bin/xfce4-appfinder",
                "/usr/bin/thunar",
            ),
            XfceDesktopPackageSet.REQUIRED_GUEST_BINARIES,
        )
    }

    @Test
    fun refreshUsesExactAptArgvRestrictedBindsAndNoHardLinkEmulation() {
        val fixture = Fixture.create("xfce-refresh")
        try {
            val invocation = XfceDesktopPlanner.refreshIndexes(
                fixture.rootfs,
                fixture.nativeDir,
                fixture.runtimeTemp,
                fixture.sessionHome,
                fixture.sessionTemp,
                fixture.resolvConf,
            )

            val expectedTail = listOf(
                RootlessRuntimePlanner.GUEST_APT_GET,
                "-o",
                "Acquire::Retries=2",
                "-o",
                "APT::Color=0",
                "update",
            )
            assertEquals(expectedTail, invocation.command.takeLast(expectedTail.size))
            assertTrue(invocation.command.contains("-L"))
            assertFalse(invocation.command.contains("--link2symlink"))
            assertRestrictedNetworkBinds(invocation, fixture)
            assertNoBroadHostExposure(invocation)
            assertEquals("noninteractive", invocation.environment["DEBIAN_FRONTEND"])
            assertEquals("none", invocation.environment["APT_LISTCHANGES_FRONTEND"])
            assertNull(invocation.environment["PROOT_L2S_DIR"])
        } finally {
            fixture.close()
        }
    }

    @Test
    fun installUsesExactAllowlistRestrictedBindsAndHardLinkEmulation() {
        val fixture = Fixture.create("xfce-install")
        try {
            val invocation = XfceDesktopPlanner.install(
                fixture.rootfs,
                fixture.nativeDir,
                fixture.runtimeTemp,
                fixture.sessionHome,
                fixture.sessionTemp,
                fixture.resolvConf,
            )

            val expectedTail = listOf(
                RootlessRuntimePlanner.GUEST_APT_GET,
                "-o",
                "Acquire::Retries=2",
                "-o",
                "APT::Color=0",
                "-o",
                "Dpkg::Use-Pty=0",
                "-o",
                "Dpkg::Options::=--force-unsafe-io",
                "--yes",
                "--no-install-recommends",
                "install",
            ) + XfceDesktopPackageSet.PACKAGES
            assertEquals(expectedTail, invocation.command.takeLast(expectedTail.size))
            assertTrue(invocation.command.contains("--link2symlink"))
            assertEquals(fixture.l2s.absolutePath, invocation.environment["PROOT_L2S_DIR"])
            assertRestrictedNetworkBinds(invocation, fixture)
            assertNoBroadHostExposure(invocation)
            assertFalse(invocation.command.contains(RootlessRuntimePlanner.GUEST_SHELL))
            assertFalse(invocation.command.contains("-c"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun recoveryConfiguresOnlyKnownIncompletePackagesInDependencyOrder() {
        val fixture = Fixture.create("xfce-recovery")
        try {
            File(fixture.rootfs, "usr/bin/dpkg").writeText("dpkg")
            val status = XfcePackageRecoveryPolicy.CONFIGURE_ORDER.joinToString("\n\n") { name ->
                val state = when (name) {
                    "tzdata" -> "install ok half-configured"
                    "xfce4-session" -> "install ok unpacked"
                    else -> "install ok installed"
                }
                "Package: $name\nStatus: $state"
            }
            val assessment = XfcePackageRecoveryPolicy.assess(status)
            assertTrue(assessment.safeToOfferRecovery)
            val invocation = XfceDesktopPlanner.configureIncompletePackages(
                fixture.rootfs, fixture.nativeDir, fixture.runtimeTemp,
                fixture.sessionHome, fixture.sessionTemp, fixture.resolvConf, assessment,
            )
            assertEquals(
                listOf("/usr/bin/dpkg", "--configure", "tzdata", "xfce4-session"),
                invocation.command.takeLast(4),
            )
            assertTrue(invocation.command.contains("--link2symlink"))
            assertEquals(fixture.l2s.absolutePath, invocation.environment["PROOT_L2S_DIR"])
            assertRestrictedNetworkBinds(invocation, fixture)
            assertNoBroadHostExposure(invocation)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun recoveryRejectsIncompleteOrInvalidAssessments() {
        val fixture = Fixture.create("xfce-recovery-reject")
        try {
            File(fixture.rootfs, "usr/bin/dpkg").writeText("dpkg")
            val assessment = XfcePackageRecoveryPolicy.assess(
                "Package: tzdata\nStatus: install ok half-configured",
            )
            assertFalse(assessment.safeToOfferRecovery)
            try {
                XfceDesktopPlanner.configureIncompletePackages(
                    fixture.rootfs, fixture.nativeDir, fixture.runtimeTemp,
                    fixture.sessionHome, fixture.sessionTemp, fixture.resolvConf, assessment,
                )
                fail("Expected recovery to refuse incomplete dpkg status")
            } catch (_: IllegalArgumentException) {
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun verificationUsesFixedDpkgQueryWithoutNetworkOrHardLinkEmulation() {
        val fixture = Fixture.create("xfce-verify")
        try {
            val invocation = XfceDesktopPlanner.verify(
                fixture.rootfs,
                fixture.nativeDir,
                fixture.runtimeTemp,
                fixture.sessionHome,
                fixture.sessionTemp,
            )

            val expectedTail = listOf(
                XfceDesktopPlanner.GUEST_DPKG_QUERY,
                "--show",
                "--showformat=\${binary:Package}\\t\${Version}\\t\${db:Status-Abbrev}\\n",
            ) + XfceDesktopPackageSet.PACKAGES
            assertEquals(expectedTail, invocation.command.takeLast(expectedTail.size))
            assertFalse(invocation.command.contains("--link2symlink"))
            assertFalse(invocation.command.any { it.contains("/etc/resolv.conf") })
            assertNull(invocation.environment["PROOT_L2S_DIR"])
            assertNoBroadHostExposure(invocation)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun managedSessionContractIsFixedLocalAndDoesNotOpenTransport() {
        val fixture = Fixture.create("xfce-session")
        try {
            XfceDesktopPackageSet.REQUIRED_GUEST_BINARIES.forEach { path ->
                File(fixture.rootfs, path.removePrefix("/")).apply {
                    parentFile.mkdirs()
                    writeText("binary")
                }
            }
            File(fixture.sessionTemp, "anvildesk-xdg-runtime").mkdirs()

            val invocation = XfceDesktopPlanner.managedSession(
                fixture.rootfs,
                fixture.nativeDir,
                fixture.runtimeTemp,
                fixture.sessionHome,
                fixture.sessionTemp,
                displayNumber = 0,
            )

            assertEquals(
                listOf(
                    XfceDesktopPlanner.GUEST_DBUS_LAUNCH,
                    "--exit-with-session",
                    XfceDesktopPlanner.GUEST_START_XFCE4,
                ),
                invocation.command.takeLast(3),
            )
            assertEquals(":0", invocation.environment["DISPLAY"])
            assertEquals(XfceDesktopPlanner.GUEST_XDG_RUNTIME, invocation.environment["XDG_RUNTIME_DIR"])
            assertEquals("XFCE", invocation.environment["XDG_CURRENT_DESKTOP"])
            assertEquals("xfce", invocation.environment["XDG_SESSION_DESKTOP"])
            assertEquals("x11", invocation.environment["XDG_SESSION_TYPE"])
            assertEquals("x11", invocation.environment["GDK_BACKEND"])
            assertNull(invocation.environment["PROOT_L2S_DIR"])
            assertFalse(invocation.command.contains("--link2symlink"))
            assertFalse(invocation.command.contains(RootlessRuntimePlanner.GUEST_SHELL))
            assertFalse(invocation.command.contains("-c"))
            assertFalse(invocation.command.any { it.contains("/etc/resolv.conf") })
            assertNoBroadHostExposure(invocation)
            val joined = invocation.command.joinToString(" ")
            assertFalse(joined.contains("--listen"))
            assertFalse(joined.contains("tcp"))
            assertFalse(joined.contains("6000"))

            try {
                XfceDesktopPlanner.managedSession(
                    fixture.rootfs,
                    fixture.nativeDir,
                    fixture.runtimeTemp,
                    fixture.sessionHome,
                    fixture.sessionTemp,
                    displayNumber = 100,
                )
                fail("Expected invalid display number to be rejected")
            } catch (_: IllegalArgumentException) {
            }
        } finally {
            fixture.close()
        }
    }

    private fun assertRestrictedNetworkBinds(
        invocation: RootlessRuntimeInvocation,
        fixture: Fixture,
    ) {
        assertTrue(invocation.command.contains("--bind=${fixture.sessionHome.absolutePath}:/root!"))
        assertTrue(invocation.command.contains("--bind=${fixture.sessionTemp.absolutePath}:/tmp!"))
        assertTrue(invocation.command.contains("--bind=${fixture.resolvConf.absolutePath}:/etc/resolv.conf!"))
        assertTrue(invocation.command.contains("--bind=/dev/null:/dev/null!"))
        assertTrue(invocation.command.contains("--bind=/dev/urandom:/dev/urandom!"))
        assertTrue(invocation.command.contains("--bind=/dev/random:/dev/random!"))
    }

    private fun assertNoBroadHostExposure(invocation: RootlessRuntimeInvocation) {
        val full = invocation.command.joinToString(" ")
        listOf("/sdcard", "/storage", "/system", "/vendor").forEach {
            assertFalse("Unexpected host exposure: $it", full.contains(it))
        }
        assertFalse(invocation.command.contains("--bind=/dev"))
        assertFalse(invocation.command.contains("--bind=/proc"))
        assertFalse(invocation.command.contains("--bind=/sys"))
    }

    private class Fixture private constructor(
        val workspace: File,
        val rootfs: File,
        val nativeDir: File,
        val runtimeTemp: File,
        val sessionHome: File,
        val sessionTemp: File,
        val resolvConf: File,
        val l2s: File,
    ) {
        fun close() {
            workspace.deleteRecursively()
        }

        companion object {
            fun create(name: String): Fixture {
                val workspace = Files.createTempDirectory("anvildesk-$name").toFile()
                val rootfs = File(workspace, "mutable-rootfs").apply { mkdirs() }
                File(rootfs, "usr/bin/apt-get").apply {
                    parentFile.mkdirs()
                    writeText("apt")
                }
                File(rootfs, "usr/bin/dpkg-query").apply {
                    parentFile.mkdirs()
                    writeText("dpkg-query")
                }
                val l2s = File(rootfs, ".l2s").apply { mkdirs() }
                val nativeDir = File(workspace, "native").apply { mkdirs() }
                File(nativeDir, RootlessRuntimePlanner.PROOT_LIBRARY).writeText("proot")
                File(nativeDir, RootlessRuntimePlanner.LOADER_LIBRARY).writeText("loader")
                val runtimeTemp = File(workspace, "runtime-tmp").apply { mkdirs() }
                val sessionHome = File(workspace, "session/home").apply { mkdirs() }
                val sessionTemp = File(workspace, "session/tmp").apply { mkdirs() }
                val resolvConf = File(workspace, "session/network/resolv.conf").apply {
                    parentFile.mkdirs()
                    writeText("nameserver 8.8.8.8\n")
                }
                return Fixture(
                    workspace,
                    rootfs,
                    nativeDir,
                    runtimeTemp,
                    sessionHome,
                    sessionTemp,
                    resolvConf,
                    l2s,
                )
            }
        }
    }
}
