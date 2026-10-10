package com.dpdpxray.core.dns

import com.dpdpxray.core.model.AnswerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The local NXDOMAIN answer used by quarantine runs: the app learns the name does not exist and no query is forwarded. */
class DnsNxDomainTest {

    @Test
    fun `NXDOMAIN keeps the id, question and RD bit and sets QR, RA and RCODE 3`() {
        val dns = TestPackets.dnsQuery("c2.example", 1, id = 0x0A0B)
        val nx = DnsPacketCodec.nxDomain(dns)
        assertEquals(0x0A, nx[0].toInt() and 0xff); assertEquals(0x0B, nx[1].toInt() and 0xff)
        assertEquals(0x80, nx[2].toInt() and 0x80) // QR: this is a response
        assertEquals(0x01, nx[2].toInt() and 0x01) // RD copied from the query
        assertEquals(0x80, nx[3].toInt() and 0x80) // RA
        assertEquals(3, nx[3].toInt() and 0x0f) // NXDOMAIN
        assertEquals("c2.example" to 1, DnsPacketCodec.parseQuestion(nx))
    }

    @Test
    fun `NXDOMAIN has one question and no answer, authority or additional records`() {
        val dns = TestPackets.dnsQuery("c2.example", 28)
        val nx = DnsPacketCodec.nxDomain(dns)
        fun count(at: Int) = ((nx[at].toInt() and 0xff) shl 8) or (nx[at + 1].toInt() and 0xff)
        assertEquals(1, count(4))
        assertEquals(0, count(6)); assertEquals(0, count(8)); assertEquals(0, count(10))
        assertEquals(dns.size, nx.size) // header + question only
    }

    @Test
    fun `NXDOMAIN drops an EDNS OPT record that followed the question`() {
        val query = TestPackets.dnsQuery("c2.example", 1)
        query[11] = 1 // ARCOUNT = 1
        val opt = byteArrayOf(0, 0, 41, 0x04, 0xD0.toByte(), 0, 0, 0, 0, 0, 0)
        val nx = DnsPacketCodec.nxDomain(query + opt)
        assertEquals(query.size, nx.size)
        assertEquals(0, nx[11].toInt())
    }

    @Test
    fun `the existing answer reader sees NXDOMAIN as a complete reply with rcode 3 and no addresses`() {
        val dns = TestPackets.dnsQuery("c2.example", 1, id = 0x1111)
        val answer = DnsAnswers.parse(DnsPacketCodec.nxDomain(dns), dns)
        assertEquals(AnswerStatus.COMPLETE, answer.status)
        assertEquals(3, answer.rcode)
        assertTrue(answer.answerIps.isEmpty())
    }

    @Test
    fun `NXDOMAIN wrapped for the app carries valid IPv4 checksums and swapped endpoints`() {
        val packet = TestPackets.ipv4Udp(TestPackets.dnsQuery("c2.example", 1), srcPort = 41000)
        val q = DnsPacketCodec.parse(packet)!!
        val pkt = DnsPacketCodec.buildResponse(q, DnsPacketCodec.nxDomain(q.dnsPayload))
        assertEquals(0xFFFF, TestPackets.onesComplementSum(pkt, 0, 20))
        assertEquals(41000, ((pkt[22].toInt() and 0xff) shl 8) or (pkt[23].toInt() and 0xff))
        val reparsed = DnsPacketCodec.nxDomain(q.dnsPayload)
        assertEquals("c2.example" to 1, DnsPacketCodec.parseQuestion(reparsed))
    }

    @Test
    fun `NXDOMAIN of a message too short to hold a header is a bare header-sized reply`() {
        val nx = DnsPacketCodec.nxDomain(byteArrayOf(1, 2, 3))
        assertEquals(12, nx.size)
        assertEquals(3, nx[3].toInt() and 0x0f)
    }
}
