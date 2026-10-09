package com.dpdpxray.core.dns

import com.dpdpxray.core.dns.TestPackets.dnsQuery
import com.dpdpxray.core.dns.TestPackets.ipv4Udp
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/** Shapes observed in the tunnel on an iQOO I2501 with Private DNS on Automatic, rebuilt byte by byte. */
class UnparsedPacketTest {
    private fun kind(p: ByteArray) = DnsPacketCodec.classifyUnparsed(p, p.size)

    /** IPv6 header with [nextHeader] followed by [rest]; addresses zero. */
    private fun ipv6(nextHeader: Int, rest: ByteArray) =
        byteArrayOf(0x60, 0, 0, 0, (rest.size shr 8).toByte(), rest.size.toByte(), nextHeader.toByte(), 1) + ByteArray(32) + rest

    private fun ports(dst: Int) = byteArrayOf(0x9c.toByte(), 0x40, (dst shr 8).toByte(), dst.toByte()) + ByteArray(16)

    @Test
    fun `DNS over TLS and QUIC on port 853 is hidden DNS`() {
        assertEquals(UnparsedPacket.HIDDEN_DNS, kind(ipv4Udp(ByteArray(40), dstPort = 853, protocol = 6)))  // TCP SYN, as seen
        assertEquals(UnparsedPacket.HIDDEN_DNS, kind(ipv4Udp(ByteArray(40), dstPort = 853, protocol = 17))) // DoQ
        assertEquals(UnparsedPacket.HIDDEN_DNS, kind(ipv6(6, ports(853))))
    }

    @Test
    fun `DNS over TCP port 53 is hidden DNS because the relay only reads UDP`() {
        assertEquals(UnparsedPacket.HIDDEN_DNS, kind(ipv4Udp(ByteArray(40), dstPort = 53, protocol = 6)))
    }

    @Test
    fun `an unreadable UDP port 53 packet is malformed DNS`() {
        assertEquals(UnparsedPacket.MALFORMED_DNS, kind(ipv4Udp(byteArrayOf(1, 2, 3), dstPort = 53)))
        val zeroQuestions = dnsQuery("a.example", 1).also { it[5] = 0 }
        assertEquals(UnparsedPacket.MALFORMED_DNS, kind(ipv4Udp(zeroQuestions, dstPort = 53)))
    }

    @Test
    fun `IPv6 control traffic and other ports are not DNS`() {
        assertEquals(UnparsedPacket.NOT_DNS, kind(ipv6(58, ByteArray(8))))           // ICMPv6, as seen
        assertEquals(UnparsedPacket.NOT_DNS, kind(ipv6(0, ByteArray(36))))           // hop-by-hop options (MLD), as seen
        assertEquals(UnparsedPacket.NOT_DNS, kind(ipv4Udp(ByteArray(10), dstPort = 443)))
        assertEquals(UnparsedPacket.NOT_DNS, kind(ipv4Udp(ByteArray(10), dstPort = 5353)))
        assertEquals(UnparsedPacket.NOT_DNS, kind(ipv4Udp(ByteArray(10), protocol = 1)))
    }

    @Test
    fun `too short or unknown packets are not DNS and never throw`() {
        assertEquals(UnparsedPacket.NOT_DNS, kind(ByteArray(0)))
        assertEquals(UnparsedPacket.NOT_DNS, kind(byteArrayOf(0x45, 0, 0)))
        assertEquals(UnparsedPacket.NOT_DNS, kind(byteArrayOf(0x20) + ByteArray(40)))
        val rnd = Random(853)
        repeat(5_000) { kind(rnd.nextBytes(rnd.nextInt(0, 80))) }
        val buf = ipv4Udp(ByteArray(40), dstPort = 853, protocol = 6)
        assertEquals("only the given length is read", UnparsedPacket.NOT_DNS, DnsPacketCodec.classifyUnparsed(buf, 21))
    }
}
