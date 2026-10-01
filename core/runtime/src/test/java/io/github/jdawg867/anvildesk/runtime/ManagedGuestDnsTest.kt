package io.github.jdawg867.anvildesk.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedGuestDnsTest {
    @Test
    fun writesOnlyNormalizedNumericDnsServers() {
        val workspace = Files.createTempDirectory("anvildesk-dns").toFile()
        try {
            val destination = File(workspace, "network/resolv.conf")
            ManagedGuestDns.write(
                destination,
                listOf(" 8.8.8.8 ", "2001:4860:4860::8888", "8.8.8.8"),
            )

            val content = destination.readText()
            assertTrue(content.contains("nameserver 8.8.8.8"))
            assertTrue(content.contains("nameserver 2001:4860:4860::8888"))
            assertEquals(1, content.lineSequence().count { it == "nameserver 8.8.8.8" })
            assertFalse(content.contains("http"))
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDnsHostnames() {
        ManagedGuestDns.normalize(listOf("dns.example.com"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsScopedIpv6DnsAddress() {
        ManagedGuestDns.normalize(listOf("fe80::1%wlan0"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyDnsList() {
        ManagedGuestDns.normalize(emptyList())
    }
}
