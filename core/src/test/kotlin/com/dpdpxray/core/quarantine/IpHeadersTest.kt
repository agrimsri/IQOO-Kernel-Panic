package com.dpdpxray.core.quarantine

import com.dpdpxray.core.quarantine.QuarantinePackets.fragmentHeader
import com.dpdpxray.core.quarantine.QuarantinePackets.icmp
import com.dpdpxray.core.quarantine.QuarantinePackets.ipv4
import com.dpdpxray.core.quarantine.QuarantinePackets.ipv6
import com.dpdpxray.core.quarantine.QuarantinePackets.optionsHeader
import com.dpdpxray.core.quarantine.QuarantinePackets.tcp
import com.dpdpxray.core.quarantine.QuarantinePackets.udp
import com.dpdpxray.core.quarantine.QuarantinePackets.v4
import com.dpdpxray.core.quarantine.QuarantinePackets.v6
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IpHeadersTest {
    private val syn = 0x02
    private val synAck = 0x12
    private val ack = 0x10

    @Test
    fun `IPv4 TCP SYN gives destination address, port and a new-connection attempt`() {
        val h = IpHeaders.parse(ipv4(v4("203.0.113.9"), 6, tcp(40000, 443, syn)))!!
        assertEquals(4, h.ipVersion)
        assertEquals("203.0.113.9", h.destination)
        assertEquals(443, h.destinationPort)
        assertEquals(AttemptProtocol.TCP_SYN, h.protocol)
        assertEquals(6, h.protocolNumber)
        assertEquals(AddressScope.PUBLIC, h.scope)
        assertFalse(h.networkControl)
    }

    @Test
    fun `IPv4 TCP without SYN, or SYN-ACK, is not a new connection attempt`() {
        assertEquals(AttemptProtocol.TCP_OTHER, IpHeaders.parse(ipv4(v4("203.0.113.9"), 6, tcp(40000, 443, ack)))!!.protocol)
        assertEquals(AttemptProtocol.TCP_OTHER, IpHeaders.parse(ipv4(v4("203.0.113.9"), 6, tcp(40000, 443, synAck)))!!.protocol)
    }

    @Test
    fun `IPv4 header options are skipped to reach the transport header`() {
        val h = IpHeaders.parse(ipv4(v4("198.51.100.7"), 17, udp(5000, 8443), options = ByteArray(8) { 1 }))!!
        assertEquals(AttemptProtocol.UDP, h.protocol)
        assertEquals(8443, h.destinationPort)
    }

    @Test
    fun `IPv4 UDP and ICMP`() {
        val u = IpHeaders.parse(ipv4(v4("8.8.8.8"), 17, udp(5353, 53)))!!
        assertEquals(AttemptProtocol.UDP, u.protocol); assertEquals(53, u.destinationPort); assertEquals("8.8.8.8", u.destination)
        val i = IpHeaders.parse(ipv4(v4("1.1.1.1"), 1, icmp(8)))!!
        assertEquals(AttemptProtocol.ICMP, i.protocol); assertNull(i.destinationPort); assertEquals(8, i.icmpType)
    }

    @Test
    fun `IPv4 protocol other than TCP, UDP or ICMP is OTHER with its number and no port`() {
        val h = IpHeaders.parse(ipv4(v4("203.0.113.1"), 47, ByteArray(8)))!! // GRE
        assertEquals(AttemptProtocol.OTHER, h.protocol); assertEquals(47, h.protocolNumber); assertNull(h.destinationPort)
    }

    @Test
    fun `IPv4 non-first fragment has no transport header, so no port is read`() {
        val h = IpHeaders.parse(ipv4(v4("203.0.113.9"), 6, ByteArray(24) { 0x55 }, fragmentField = 0x0010))!!
        assertTrue(h.fragment)
        assertNull(h.destinationPort)
        assertEquals(AttemptProtocol.TCP_OTHER, h.protocol)
    }

    @Test
    fun `IPv4 first fragment (more-fragments set, offset 0) still has its ports`() {
        val h = IpHeaders.parse(ipv4(v4("203.0.113.9"), 17, udp(1, 4444, ByteArray(16)), fragmentField = 0x2000))!!
        assertFalse(h.fragment); assertEquals(4444, h.destinationPort)
    }

    @Test
    fun `IPv6 TCP SYN and UDP give a compressed destination and port`() {
        val t = IpHeaders.parse(ipv6(v6("2001:db8::1"), 6, tcp(40000, 443, syn)))!!
        assertEquals(6, t.ipVersion)
        assertEquals("2001:db8::1", t.destination)
        assertEquals(AttemptProtocol.TCP_SYN, t.protocol)
        assertEquals(443, t.destinationPort)
        val u = IpHeaders.parse(ipv6(v6("2606:4700:4700::1111"), 17, udp(1000, 853)))!!
        assertEquals("2606:4700:4700::1111", u.destination); assertEquals(853, u.destinationPort); assertEquals(AttemptProtocol.UDP, u.protocol)
    }

    @Test
    fun `IPv6 extension headers are followed to the transport header`() {
        val h = IpHeaders.parse(ipv6(v6("2001:db8::2"), 6, tcp(1, 8080, syn), listOf(0 to optionsHeader(), 60 to optionsHeader())))!!
        assertEquals(8080, h.destinationPort); assertEquals(AttemptProtocol.TCP_SYN, h.protocol)
    }

    @Test
    fun `IPv6 non-first fragment has no port, first fragment does`() {
        val later = IpHeaders.parse(ipv6(v6("2001:db8::3"), 17, ByteArray(16), listOf(44 to fragmentHeader(offsetUnits = 3, more = false))))!!
        assertTrue(later.fragment); assertNull(later.destinationPort); assertEquals(AttemptProtocol.UDP, later.protocol)
        val first = IpHeaders.parse(ipv6(v6("2001:db8::3"), 17, udp(1, 9999, ByteArray(16)), listOf(44 to fragmentHeader(offsetUnits = 0, more = true))))!!
        assertFalse(first.fragment); assertEquals(9999, first.destinationPort)
    }

    @Test
    fun `ICMPv6 echo is an ICMP attempt but neighbour discovery and MLD are network control`() {
        val echo = IpHeaders.parse(ipv6(v6("2001:db8::9"), 58, icmp(128)))!!
        assertEquals(AttemptProtocol.ICMP, echo.protocol); assertFalse(echo.networkControl)
        for (type in listOf(130, 131, 132, 133, 134, 135, 136, 137, 143)) {
            val h = IpHeaders.parse(ipv6(v6("ff02::2"), 58, icmp(type)))!!
            assertTrue("type $type", h.networkControl)
        }
    }

    @Test
    fun `IGMP is network control`() {
        assertTrue(IpHeaders.parse(ipv4(v4("224.0.0.22"), 2, ByteArray(8)))!!.networkControl)
    }

    @Test
    fun `every truncation of a valid packet is rejected, never read past the given length`() {
        val packets = listOf(
            ipv4(v4("203.0.113.9"), 6, tcp(40000, 443, syn)),
            ipv4(v4("203.0.113.9"), 17, udp(40000, 443)),
            ipv4(v4("203.0.113.9"), 1, icmp(8)),
            ipv6(v6("2001:db8::1"), 6, tcp(40000, 443, syn)),
            ipv6(v6("2001:db8::1"), 17, udp(1, 2), listOf(0 to optionsHeader())),
            ipv6(v6("2001:db8::1"), 58, icmp(128)),
        )
        for (p in packets) {
            assertNotNull(IpHeaders.parse(p))
            for (cut in 0 until p.size) assertNull("cut at $cut of ${p.size}", IpHeaders.parse(p, cut))
        }
    }

    @Test
    fun `only the given length of a reused buffer is read`() {
        val p = ipv4(v4("203.0.113.9"), 6, tcp(40000, 443, syn))
        val buffer = p + ByteArray(300) { 0x7f }
        assertEquals(443, IpHeaders.parse(buffer, p.size)!!.destinationPort)
    }

    @Test
    fun `malformed headers are rejected`() {
        assertNull(IpHeaders.parse(ByteArray(0)))
        assertNull(IpHeaders.parse(byteArrayOf(0x45), 2)) // length beyond the array
        assertNull(IpHeaders.parse(ByteArray(40) { 0x20 })) // IP version 2
        val badIhl = ipv4(v4("203.0.113.9"), 6, tcp(1, 2, syn)).also { it[0] = 0x44 } // IHL 16 bytes
        assertNull(IpHeaders.parse(badIhl))
        val shortTotal = ipv4(v4("203.0.113.9"), 6, tcp(1, 2, syn)).also { it[2] = 0; it[3] = 10 } // total < header
        assertNull(IpHeaders.parse(shortTotal))
        val badTcpOffset = ipv4(v4("203.0.113.9"), 6, tcp(1, 2, syn)).also { it[20 + 12] = 0x40 } // data offset 16 bytes
        assertNull(IpHeaders.parse(badTcpOffset))
        val loopingV6 = ipv6(v6("2001:db8::1"), 6, tcp(1, 2, syn), List(9) { 60 to optionsHeader() }) // too many headers
        assertNull(IpHeaders.parse(loopingV6))
    }

    @Test
    fun `IPv6 addresses are formatted in the compressed RFC 5952 form`() {
        assertEquals("::", IpAddresses.format(ByteArray(16)))
        assertEquals("::1", IpAddresses.format(v6("::1")))
        assertEquals("fe80::1:2", IpAddresses.format(v6("fe80::1:2")))
        assertEquals("2001:db8:0:1:1:1:1:1", IpAddresses.format(v6("2001:db8:0:1:1:1:1:1"))) // single zero group not compressed
        assertEquals("2001:db8::1:0:0:1", IpAddresses.format(v6("2001:db8:0:0:1:0:0:1"))) // first of equal runs
        assertEquals("2001:0:0:1::1", IpAddresses.format(v6("2001:0:0:1:0:0:0:1"))) // longest zero run wins
        assertEquals("203.0.113.9", IpAddresses.format(v4("203.0.113.9")))
    }

    @Test
    fun `address scopes`() {
        fun s4(t: String) = IpAddresses.scope(v4(t))
        fun s6(t: String) = IpAddresses.scope(v6(t))
        assertEquals(AddressScope.PUBLIC, s4("142.250.1.1"))
        assertEquals(AddressScope.PRIVATE, s4("10.0.0.1")); assertEquals(AddressScope.PRIVATE, s4("172.16.5.4"))
        assertEquals(AddressScope.PUBLIC, s4("172.32.0.1"))
        assertEquals(AddressScope.PRIVATE, s4("192.168.1.1")); assertEquals(AddressScope.PRIVATE, s4("100.64.0.1"))
        assertEquals(AddressScope.LOOPBACK, s4("127.0.0.1"))
        assertEquals(AddressScope.LINK_LOCAL, s4("169.254.1.1"))
        assertEquals(AddressScope.MULTICAST, s4("239.255.255.250"))
        assertEquals(AddressScope.BROADCAST, s4("255.255.255.255"))
        assertEquals(AddressScope.UNSPECIFIED, s4("0.0.0.0"))
        assertEquals(AddressScope.RESERVED, s4("240.0.0.1"))
        assertEquals(AddressScope.PUBLIC, s6("2606:4700::1"))
        assertEquals(AddressScope.PRIVATE, s6("fd00::1"))
        assertEquals(AddressScope.LINK_LOCAL, s6("fe80::1"))
        assertEquals(AddressScope.MULTICAST, s6("ff02::fb"))
        assertEquals(AddressScope.LOOPBACK, s6("::1"))
        assertEquals(AddressScope.UNSPECIFIED, s6("::"))
        assertEquals(AddressScope.PRIVATE, s6("::ffff:c0a8:101")) // IPv4-mapped 192.168.1.1
    }
}
