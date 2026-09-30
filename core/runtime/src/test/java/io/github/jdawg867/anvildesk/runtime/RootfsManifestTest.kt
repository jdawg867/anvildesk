package io.github.jdawg867.anvildesk.runtime

import org.junit.Assert.assertTrue
import org.junit.Test

class RootfsManifestTest {
    @Test
    fun pinnedUbuntuManifestIsValid() {
        assertTrue(RootfsManifestValidator.validate(RootfsCatalog.Ubuntu24045Arm64).isEmpty())
    }

    @Test
    fun cleartextArtifactUrlIsRejected() {
        val manifest = RootfsCatalog.Ubuntu24045Arm64.copy(
            url = "http://example.invalid/rootfs.tar.gz",
        )

        val errors = RootfsManifestValidator.validate(manifest)
        assertTrue(errors.any { it.contains("url must use HTTPS") })
    }

    @Test
    fun malformedDigestIsRejected() {
        val manifest = RootfsCatalog.Ubuntu24045Arm64.copy(sha256 = "not-a-digest")

        val errors = RootfsManifestValidator.validate(manifest)
        assertTrue(errors.any { it.contains("sha256") })
    }

    @Test
    fun unsupportedArchitectureIsRejected() {
        val manifest = RootfsCatalog.Ubuntu24045Arm64.copy(architecture = "x86_64")

        val errors = RootfsManifestValidator.validate(manifest)
        assertTrue(errors.any { it.contains("architecture") })
    }
}
