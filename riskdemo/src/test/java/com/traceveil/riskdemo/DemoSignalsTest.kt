package com.traceveil.riskdemo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The demo's network attempts must only ever target names and addresses that are reserved and can never belong to a
 * real server. The oracle below is copied from the RFCs, not from production code.
 */
class DemoSignalsTest {
    /** RFC 2606 section 2 and RFC 6761 section 6: top-level names reserved for testing, examples and invalid use. */
    private val reservedTlds = setOf("invalid", "example", "test", "localhost")

    /** RFC 5737 section 3: TEST-NET-1, TEST-NET-2 and TEST-NET-3, reserved for documentation and never routed. */
    private val testNets = listOf(listOf(192, 0, 2), listOf(198, 51, 100), listOf(203, 0, 113))

    @Test
    fun `the demo hostnames are exactly the three documented reserved names`() {
        assertEquals(listOf("c2.traceveil-demo.invalid", "pay-challan-update.invalid", "otp-relay.example"), DemoSignals.HOSTS)
    }

    @Test
    fun `every demo hostname is under a reserved top-level domain`() {
        DemoSignals.HOSTS.forEach { host ->
            val tld = host.trimEnd('.').substringAfterLast('.').lowercase()
            assertTrue("$host is not under a reserved top-level domain", tld in reservedTlds)
        }
    }

    @Test
    fun `the hard-coded address is inside an RFC 5737 documentation range`() {
        val octets = DemoSignals.TEST_NET_ADDRESS.address.map { it.toInt() and 0xFF }
        assertEquals(listOf(203, 0, 113, 10), octets)
        assertTrue("${octets.joinToString(".")} is not in a TEST-NET range", testNets.any { it == octets.take(3) })
    }

    @Test
    fun `the connect attempt uses port 443 and a short timeout`() {
        assertEquals(443, DemoSignals.PORT)
        assertTrue(DemoSignals.CONNECT_TIMEOUT_MS in 1..3_000)
    }

    @Test
    fun `run looks up each reserved name once, makes one connect attempt and survives every failure`() {
        val looked = mutableListOf<String>()
        val connected = mutableListOf<Triple<InetAddress, Int, Int>>()
        val attempts = DemoSignals.run(
            lookup = { host -> looked += host; throw UnknownHostException(host) },
            connect = { address, port, timeout -> connected += Triple(address, port, timeout); throw SocketTimeoutException("timeout") },
        )

        assertEquals(DemoSignals.HOSTS, looked)
        assertEquals(1, connected.size)
        assertEquals("203.0.113.10", connected.single().first.hostAddress)
        assertEquals(443, connected.single().second)
        assertEquals(DemoSignals.CONNECT_TIMEOUT_MS, connected.single().third)
        assertEquals(DemoSignals.HOSTS + "203.0.113.10:443", attempts.map { it.target })
        assertTrue(attempts.none { it.reached })
    }

    @Test
    fun `an unexpected answer is reported on screen, not hidden`() {
        val attempts = DemoSignals.run(lookup = { 1 }, connect = { _, _, _ -> })
        assertTrue(attempts.all { it.reached })
        assertTrue(attempts.all { it.detail.contains("unexpected", ignoreCase = true) })
    }
}
