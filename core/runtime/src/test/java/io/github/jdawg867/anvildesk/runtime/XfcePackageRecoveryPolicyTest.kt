package io.github.jdawg867.anvildesk.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XfcePackageRecoveryPolicyTest {
    private fun status(overrides: Map<String, String> = emptyMap()): String =
        XfcePackageRecoveryPolicy.CONFIGURE_ORDER.joinToString("\n\n") { name ->
            "Package: $name\nStatus: ${overrides[name] ?: "install ok installed"}\nVersion: 1"
        }

    @Test fun fullyConfiguredNeedsNoRecovery() {
        val result = XfcePackageRecoveryPolicy.assess(status())
        assertTrue(result.blockers.isEmpty())
        assertTrue(result.packagesToConfigure.isEmpty())
        assertFalse(result.safeToOfferRecovery)
    }

    @Test fun knownIncompleteChainIsOrderedAndAllowed() {
        val result = XfcePackageRecoveryPolicy.assess(status(mapOf(
            "tzdata" to "install ok half-configured",
            "python3.12" to "install ok unpacked",
            "xfce4-session" to "install ok unpacked",
        )))
        assertTrue(result.safeToOfferRecovery)
        assertEquals(listOf("tzdata", "python3.12", "xfce4-session"), result.packagesToConfigure)
    }

    @Test fun unexpectedOrMissingStateFailsClosed() {
        val unexpected = XfcePackageRecoveryPolicy.assess(status(mapOf(
            "tzdata" to "install reinstreq half-installed",
        )))
        assertFalse(unexpected.safeToOfferRecovery)
        assertTrue(unexpected.blockers.isNotEmpty())

        val missing = XfcePackageRecoveryPolicy.assess("Package: tzdata\nStatus: install ok installed")
        assertFalse(missing.safeToOfferRecovery)
        assertTrue(missing.blockers.isNotEmpty())
    }

    @Test fun duplicatePackageFailsClosed() {
        val duplicate = XfcePackageRecoveryPolicy.assess(
            status() + "\n\nPackage: tzdata\nStatus: install ok unpacked"
        )
        assertFalse(duplicate.safeToOfferRecovery)
        assertTrue(duplicate.blockers.any { it.contains("Duplicate package") })
    }
}
