package dev.bmcreations.blip.server.services

import java.net.InetAddress
import kotlin.test.*

class SsrfGuardTest {

    private fun blocked(ip: String) = SsrfGuard.isBlocked(InetAddress.getByName(ip))

    @Test
    fun `blocks IPv6 unique local fc00 and fd00`() {
        assertTrue(blocked("fc00::1"))
        assertTrue(blocked("fd12:3456:789a::1"))
    }

    @Test
    fun `blocks CGNAT range edges`() {
        assertTrue(blocked("100.64.0.1"))
        assertTrue(blocked("100.127.255.254"))
        assertFalse(blocked("100.63.255.255"))
        assertFalse(blocked("100.128.0.1"))
    }

    @Test
    fun `blocks IPv4-mapped IPv6 of private addresses`() {
        assertTrue(blocked("::ffff:127.0.0.1"))
        assertTrue(blocked("::ffff:10.0.0.5"))
        assertTrue(blocked("::ffff:169.254.169.254"))
        assertTrue(blocked("::ffff:100.64.1.1"))
        assertFalse(blocked("::ffff:8.8.8.8"))
    }

    @Test
    fun `mapped form built from raw bytes is unwrapped`() {
        val raw = ByteArray(16).also { it[10] = 0xff.toByte(); it[11] = 0xff.toByte(); it[12] = 192.toByte(); it[13] = 168.toByte(); it[15] = 1 }
        assertTrue(SsrfGuard.isBlocked(InetAddress.getByAddress(raw)))
    }

    @Test
    fun `blocks loopback link-local any-local multicast and zero network`() {
        listOf("127.0.0.1", "::1", "169.254.169.254", "fe80::1", "0.0.0.0", "::", "0.1.2.3", "224.0.0.1", "ff02::1", "10.1.1.1", "172.16.0.1", "192.168.1.1")
            .forEach { assertTrue(blocked(it), "$it should be blocked") }
    }

    @Test
    fun `allows public addresses`() {
        listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111").forEach { assertFalse(blocked(it), "$it should be allowed") }
    }

    @Test
    fun `validateTargetAddress throws for blocked address`() {
        assertFailsWith<IllegalArgumentException> { SsrfGuard.validateTargetAddress(InetAddress.getByName("fc00::1")) }
    }
}
