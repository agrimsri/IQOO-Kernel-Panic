package com.dpdpxray.core.netcapture

import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.Phase
import com.dpdpxray.core.netcapture.NetTestData.clientHello
import com.dpdpxray.core.netcapture.NetTestData.httpGet
import com.dpdpxray.core.netcapture.NetTestData.ip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketCodecTest {
    @Test
    fun `tcp segment round trips and carries valid checksums`() {
        val raw = PacketCodec.tcp(ip("10.111.0.2"), 40000, ip("203.0.113.9"), 443, 1000, 2000, TcpFlags.SYN or TcpFlags.ACK, 8000, "hi".toByteArray(), mss = 1400)
        val p = PacketCodec.parse(raw)!!
        assertEquals("203.0.113.9", p.dstText()); assertEquals(443, p.dstPort); assertEquals(40000, p.srcPort)
        assertEquals(1000L, p.tcp!!.seq); assertEquals(2000L, p.tcp!!.ack); assertEquals(1400, p.tcp!!.mss)
        assertTrue(p.tcp!!.has(TcpFlags.SYN)); assertEquals("hi", String(p.payload()))
        assertEquals(0, PacketCodec.checksum(raw, 0, 20, 0)) // IP header sums to zero
    }

    @Test
    fun `sequence numbers above 2^31 survive`() {
        val raw = PacketCodec.tcp(ip("1.1.1.1"), 1, ip("2.2.2.2"), 2, 0xFFFFFFF0L, 0x80000001L, TcpFlags.ACK)
        val t = PacketCodec.parse(raw)!!.tcp!!
        assertEquals(0xFFFFFFF0L, t.seq); assertEquals(0x80000001L, t.ack)
    }

    @Test
    fun `udp round trips`() {
        val p = PacketCodec.parse(PacketCodec.udp(ip("10.0.0.2"), 5000, ip("8.8.8.8"), 53, byteArrayOf(1, 2, 3)))!!
        assertTrue(p.isUdp); assertEquals(53, p.dstPort); assertEquals(3, p.payloadLength)
    }

    @Test
    fun `truncated, fragmented and non-ipv4 packets are rejected`() {
        val raw = PacketCodec.tcp(ip("1.1.1.1"), 1, ip("2.2.2.2"), 2, 1, 1, TcpFlags.ACK, payload = ByteArray(20))
        assertNull(PacketCodec.parse(raw, raw.size - 5))
        val frag = raw.copyOf().also { it[6] = 0x20 } // MF set
        assertNull(PacketCodec.parse(frag))
        assertNull(PacketCodec.parse(ByteArray(40) { 0x60 }))
        assertNull(PacketCodec.parse(ByteArray(0)))
    }
}

class TlsHelloTest {
    @Test
    fun `a server name that is not a plausible host name is not kept`() {
        for (bad in listOf("bad name.example", "ctl.example", "a..b", "-x.example", "1.2.3.4", "x".repeat(64) + ".example")) {
            assertNull(bad, TlsHello.parse(clientHello(bad))!!.sni)
        }
        assertEquals("ok.example", TlsHello.parse(clientHello("OK.Example."))!!.sni)
    }

    @Test
    fun `reads server name, alpn and tls13 offer`() {
        val h = TlsHello.parse(clientHello("Tracker.Example.com", listOf("h2", "http/1.1")))!!
        assertEquals("tracker.example.com", h.sni); assertEquals(listOf("h2", "http/1.1"), h.alpn)
        assertTrue(h.offersTls13); assertFalse(h.ech)
    }

    @Test
    fun `flags encrypted client hello`() {
        val h = TlsHello.parse(clientHello(null, ech = true))!!
        assertNull(h.sni); assertTrue(h.ech)
    }

    @Test
    fun `truncated or non-tls bytes return null and never throw`() {
        val full = clientHello("a.example")
        for (n in 0 until full.size - 1) assertNull("len $n", TlsHello.parse(full, n))
        assertNull(TlsHello.parse("GET / HTTP/1.1\r\n\r\n".toByteArray()))
        val rnd = java.util.Random(7)
        repeat(5000) { TlsHello.parse(ByteArray(rnd.nextInt(200)).also(rnd::nextBytes)) }
    }
}

class HttpHeadTest {
    @Test
    fun `parses request and response heads`() {
        val r = HttpHeadParser.parse(httpGet("api.example.com", "/v1/track?x=1"))!!
        assertTrue(r.isRequest); assertEquals("GET", r.method); assertEquals("api.example.com", r.headers["host"])
        val s = HttpHeadParser.parse("HTTP/1.1 204 No Content\r\nServer: x\r\n\r\n".toByteArray())!!
        assertFalse(s.isRequest); assertEquals(204, s.status)
    }

    @Test
    fun `incomplete or binary input is not a head`() {
        assertNull(HttpHeadParser.parse("GET /a HTTP/1.1\r\nHost: x\r\n".toByteArray()))
        assertNull(HttpHeadParser.parse(ByteArray(64) { 0x16 }))
        assertTrue(HttpHeadParser.isHttp2Preface("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".toByteArray()))
    }
}

class RedactionTest {
    @Test
    fun `query values are scrubbed but their kind is reported`() {
        val r = Redaction.target("/collect?aid=38400000-8cf0-11bd-b23e-10b96e40000d&mail=a.b@example.com&lat=12.97160&ok=1")
        assertEquals("/collect?aid=[device-id]&mail=[email]&lat=[coordinate]&ok=1", r.text)
        assertEquals(setOf(SensitiveKind.DEVICE_ID, SensitiveKind.EMAIL, SensitiveKind.LOCATION), r.found)
        assertFalse(r.text.contains("example.com"))
    }

    @Test
    fun `credential headers are dropped and flagged, harmless ones kept`() {
        val (kept, found) = Redaction.headers(mapOf("authorization" to "Bearer abc", "cookie" to "s=1", "host" to "x.com", "x-custom" to "v"))
        assertEquals(mapOf("host" to "x.com"), kept); assertEquals(setOf(SensitiveKind.CREDENTIAL), found)
    }

    @Test
    fun `long tokens and phone numbers are caught`() {
        val r = Redaction.value("t=abcdefghijklmnopqrstuvwxyz0123456789ABCD&p=9876543210")
        assertTrue(SensitiveKind.TOKEN in r.found && SensitiveKind.PHONE in r.found)
    }
}

class FlowTrackerTest {
    private fun consent(kind: ConsentKind, t: Long) = ConsentEvent(tNanos = t, kind = kind)

    @Test
    fun `hello split over two segments is still read`() {
        val ft = FlowTracker(); val id = ft.open(FlowProtocol.TCP, "203.0.113.9", 443, 0)
        val hello = clientHello("metrics.example.com")
        ft.clientBytes(id, hello, 0, 20, 1); ft.clientBytes(id, hello, 20, hello.size - 20, 2)
        val f = ft.snapshot().single()
        assertEquals("metrics.example.com", f.sni); assertEquals(TlsStatus.SNI_ONLY, f.tls)
    }

    @Test
    fun `cleartext request is stored redacted with its status`() {
        val ft = FlowTracker(); val id = ft.open(FlowProtocol.TCP, "198.51.100.4", 80, 0)
        val req = httpGet("ads.example.com", "/p?email=me@x.org", "Cookie: id=1\r\n")
        ft.clientBytes(id, req, 0, req.size, 5)
        val resp = "HTTP/1.1 200 OK\r\nServer: y\r\n\r\nbody".toByteArray()
        ft.serverBytes(id, resp, 0, resp.size, 9)
        val f = ft.snapshot().single(); val h = f.http.single()
        assertEquals(TlsStatus.CLEARTEXT, f.tls); assertEquals("/p?email=[email]", h.target); assertEquals(200, h.status)
        assertTrue(SensitiveKind.EMAIL in h.sensitive && SensitiveKind.CREDENTIAL in h.sensitive)
        assertFalse(h.headers.containsKey("cookie"))
        assertEquals("ads.example.com", f.host)
    }

    @Test
    fun `non http, non tls payload is opaque and payload is not retained`() {
        val ft = FlowTracker(); val id = ft.open(FlowProtocol.TCP, "198.51.100.4", 5222, 0)
        ft.clientBytes(id, ByteArray(100) { 7 }, 0, 100, 1)
        val f = ft.snapshot().single()
        assertEquals(TlsStatus.OPAQUE, f.tls); assertEquals(100, f.bytesUp); assertTrue(f.http.isEmpty())
    }

    @Test
    fun `bytes are split across consent phases and unknown destinations are flagged`() {
        val ft = FlowTracker(); val id = ft.open(FlowProtocol.TCP, "203.0.113.9", 443, 100)
        val junk = ByteArray(10)
        ft.transportOnly(id, 100, 10, 0)           // before choice
        ft.transportOnly(id, 2_000_000_000, 500, 1000) // after reject (> 500ms bucket)
        val clock = PhaseClock.from(listOf(consent(ConsentKind.SCREEN_APPEARED, 50), consent(ConsentKind.REJECT, 1_000_000_000)))
        val flows = ft.snapshot(ipNames = emptyMap())
        val t = FlowPhases.traffic(flows, clock).associateBy { it.phase }
        assertEquals(10L, t[Phase.BEFORE_CONSENT]!!.bytesUp); assertEquals(500L, t[Phase.AFTER_REJECT]!!.bytesUp)
        assertEquals(1000L, t[Phase.AFTER_REJECT]!!.bytesDown); assertTrue(flows.single().noLookup)
        assertNotNull(junk)
    }

    @Test
    fun `dns answers name the destination and clear the no-lookup flag`() {
        val ft = FlowTracker(); ft.open(FlowProtocol.TCP, "203.0.113.9", 443, 0)
        val names = FlowPhases.ipNames(listOf(NetEvent(tNanos = 1, hostname = "cdn.shop.test", answerIps = listOf("203.0.113.9"), cnameChain = listOf("t.tracker.example"))))
        val f = ft.snapshot(names).single()
        assertEquals("t.tracker.example", f.dnsName); assertFalse(f.noLookup)
    }

    @Test
    fun `flow table is bounded`() {
        val ft = FlowTracker(maxFlows = 3)
        repeat(10) { ft.open(FlowProtocol.UDP, "8.8.4.4", 443, it.toLong()) }
        assertEquals(3, ft.snapshot().size); assertEquals(7, ft.dropped)
    }
}
