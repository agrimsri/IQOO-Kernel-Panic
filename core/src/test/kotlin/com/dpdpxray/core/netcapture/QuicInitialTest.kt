package com.dpdpxray.core.netcapture

import com.dpdpxray.core.netcapture.NetTestData.clientHello
import com.dpdpxray.core.netcapture.NetTestData.ip
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * QUIC Initial reading. The fixture is published: RFC 9001 Appendix A.2, the protected 1200-byte client Initial (file
 * header in src/test/resources/netcapture/rfc9001-client-initial.hex). Its ClientHello names "example.com".
 */
class QuicInitialTest {
    private val rfcPacket = resourceHex("rfc9001-client-initial.hex")

    @Test
    fun `initial keys match the RFC 9001 Appendix A_1 values`() {
        val keys = QuicInitial.clientKeys(hex("8394c8f03e515708"))
        assertArrayEquals(hex("1f369613dd76d5467730efcbe3b1a22d"), keys.key)
        assertArrayEquals(hex("fa044b2f42a3fd3b46fb255c"), keys.iv)
        assertArrayEquals(hex("9f50449e04a0e810283a1e9933adedd2"), keys.hp)
    }

    @Test
    fun `the RFC 9001 client Initial decrypts to one CRYPTO frame whose ClientHello names example_com`() {
        assertEquals(1200, rfcPacket.size)
        val r = QuicInitial.read(rfcPacket) as QuicRead.Initial
        assertArrayEquals(hex("8394c8f03e515708"), r.dcid)
        assertEquals(2L, r.packetNumber)
        assertEquals(listOf(0L), r.crypto.map { it.offset })
        assertEquals(0xf1, r.crypto.single().data.size)
        val hello = QuicHelloAssembler().add(r) as QuicHello.Read
        assertEquals("example.com", hello.info.sni)
        assertTrue(hello.info.offersTls13)
        assertEquals(listOf("alpn"), hello.info.alpn) // the RFC sample offers the placeholder ALPN "alpn"
    }

    @Test
    fun `a ClientHello split over Initials, out of order and overlapping, is reassembled`() {
        val hello = (QuicInitial.read(rfcPacket) as QuicRead.Initial).crypto.single().data
        val a = QuicHelloAssembler()
        val dcid = hex("0102030405060708")
        assertEquals(QuicHello.Pending, a.add(QuicRead.Initial(dcid, 1, listOf(CryptoChunk(100, hello.copyOfRange(100, hello.size))))))
        val r = a.add(QuicRead.Initial(dcid, 0, listOf(CryptoChunk(0, hello.copyOfRange(0, 60)), CryptoChunk(50, hello.copyOfRange(50, 100)))))
        assertEquals("example.com", (r as QuicHello.Read).info.sni)
    }

    @Test
    fun `a corrupted packet fails authentication and yields nothing`() {
        val bad = rfcPacket.copyOf().also { it[600] = (it[600].toInt() xor 1).toByte() }
        assertEquals(QuicRead.Unreadable, QuicInitial.read(bad))
    }

    @Test
    fun `other versions, other packet types and non-QUIC bytes are told apart`() {
        val v2 = rfcPacket.copyOf().also { it[1] = 0x6b; it[2] = 0x33; it[3] = 0x43; it[4] = 0xcf.toByte() }
        assertEquals(QuicRead.OtherVersion(0x6b3343cf), QuicInitial.read(v2))
        assertEquals(QuicRead.NotInitial, QuicInitial.read(rfcPacket.copyOf().also { it[0] = 0xe0.toByte() })) // Handshake type
        assertEquals(QuicRead.NotInitial, QuicInitial.read("short header or anything".toByteArray()))
        assertEquals(QuicRead.NotQuic, QuicInitial.read(byteArrayOf(0xc0.toByte(), 0, 0)))
        for (n in listOf(6, 20, 30, 60, 1199)) assertEquals("$n bytes", QuicRead.Unreadable, QuicInitial.read(rfcPacket, 0, n))
    }

    @Test
    fun `crypto data that is not a ClientHello, or exceeds the bound, is given up`() {
        val dcid = hex("01")
        assertEquals(QuicHello.NotHello, QuicHelloAssembler().add(QuicRead.Initial(dcid, 0, listOf(CryptoChunk(0, byteArrayOf(2, 0, 0, 1, 0))))))
        val huge = byteArrayOf(1, 0x01, 0x00, 0x00) // declares 64 KB
        assertEquals(QuicHello.NotHello, QuicHelloAssembler(maxBytes = 4096).add(QuicRead.Initial(dcid, 0, listOf(CryptoChunk(0, huge)))))
        assertEquals(QuicHello.Pending, QuicHelloAssembler(maxBytes = 64).add(QuicRead.Initial(dcid, 0, listOf(CryptoChunk(1000, ByteArray(10))))))
    }

    @Test
    fun `the flow tracker names a QUIC flow from its Initial and stops looking afterwards`() {
        val t = FlowTracker()
        val id = t.open(FlowProtocol.QUIC, "203.0.113.5", 443, 0)
        t.quicDatagram(id, rfcPacket, 0, rfcPacket.size)
        val f = t.snapshot().single()
        assertEquals("example.com", f.sni)
        assertEquals(TlsStatus.SNI_ONLY, f.tls)
        assertEquals("example.com", f.host)
    }

    @Test
    fun `a QUIC flow without a readable Initial stays opaque and is bounded`() {
        val t = FlowTracker()
        val id = t.open(FlowProtocol.QUIC, "203.0.113.5", 443, 0)
        repeat(FlowTracker.MAX_QUIC_DATAGRAMS + 5) { t.quicDatagram(id, ByteArray(1200) { 0x40 }, 0, 1200) }
        t.quicDatagram(id, rfcPacket, 0, rfcPacket.size) // too late: the tracker stopped looking
        val f = t.snapshot().single()
        assertNull(f.sni)
        assertEquals(TlsStatus.OPAQUE, f.tls)
        // A TCP or UDP flow is never decrypted as QUIC.
        val udp = t.open(FlowProtocol.UDP, "203.0.113.6", 443, 0)
        t.quicDatagram(udp, rfcPacket, 0, rfcPacket.size)
        assertNull(t.snapshot().first { it.id == udp }.sni)
    }

    @Test
    fun `the relay hands QUIC datagrams to the tracker, so a relayed HTTP-3 connection gets its name`() {
        val sent = LinkedBlockingQueue<ByteArray>()
        val connector = object : UpstreamConnector {
            override fun tcp(dstIp: String, port: Int): UpstreamTcp? = null
            override fun udp(dstIp: String, port: Int): UpstreamUdp = object : UpstreamUdp {
                override fun send(b: ByteArray, len: Int): Boolean { sent.add(b.copyOf(len)); return true }
                override fun receive(buf: ByteArray): Int { Thread.sleep(50); return -1 }
                override fun close() {}
            }
        }
        val tracker = FlowTracker()
        val pool = Executors.newCachedThreadPool { r -> Thread(r).also { it.isDaemon = true } }
        val relay = TunRelay({}, connector, tracker, { System.nanoTime() }, pool)
        val raw = PacketCodec.udp(ip("10.111.0.2"), 50000, ip("203.0.113.9"), 443, rfcPacket)
        assertTrue(relay.onPacket(raw, raw.size))
        assertArrayEquals(rfcPacket, sent.poll(2, TimeUnit.SECONDS)) // forwarded unchanged
        val f = tracker.snapshot().single()
        assertEquals(FlowProtocol.QUIC, f.protocol)
        assertEquals("example.com", f.sni)
        pool.shutdownNow()
    }

    @Test
    fun `a TLS hello with an encrypted client hello extension is marked as possibly hiding the real name`() {
        val t = FlowTracker()
        val id = t.open(FlowProtocol.TCP, "203.0.113.7", 443, 0)
        val hello = clientHello("public.example-ech.net", ech = true)
        t.clientBytes(id, hello, 0, hello.size, 0)
        val f = t.snapshot().single()
        assertEquals(TlsStatus.ECH_HIDDEN, f.tls)
        assertEquals("public.example-ech.net", f.sni) // the visible (outer) name is kept, labelled as possibly not the real one
    }

    private fun hex(text: String): ByteArray {
        val clean = text.filter { it.isLetterOrDigit() }
        return ByteArray(clean.length / 2) { clean.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    private fun resourceHex(name: String): ByteArray {
        val text = QuicInitialTest::class.java.getResourceAsStream("/netcapture/$name")!!.bufferedReader().readText()
        return hex(text.lines().filterNot { it.startsWith("#") }.joinToString(""))
    }
}
