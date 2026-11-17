package io.github.jdawg867.anvildesk.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class M8X11ProbeRequirementsTest {
    @Test fun probeToolsAreExplicitNotAssumedInM7() {
        assertEquals(listOf("x11-utils", "xauth"), M8X11ProbeRequirements.REQUIRED_GUEST_PACKAGES)
        val rootfs = Files.createTempDirectory("m8-probe-tools").toFile()
        try {
            assertFalse(M8X11ProbeRequirements.binariesPresent(rootfs))
            for (path in M8X11ProbeRequirements.REQUIRED_GUEST_BINARIES) {
                File(rootfs, path.removePrefix("/")).apply { parentFile.mkdirs(); writeText("stub") }
            }
            assertTrue(M8X11ProbeRequirements.binariesPresent(rootfs))
        } finally {
            rootfs.deleteRecursively()
        }
    }
}
