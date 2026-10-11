package com.dpdpxray.core.netcapture

import com.dpdpxray.core.netcapture.NetTestData.clientHello
import com.dpdpxray.core.netcapture.NetTestData.httpGet
import com.dpdpxray.core.netcapture.NetTestData.ip
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Quarantine mode: [TunRelay] over a [SinkholeConnector]. Simulates the app's side of the TCP state machine with
 * hand-built segments and checks what the relay writes back and what the tracker keeps. Nothing is forwarded anywhere.
 */
class SinkholeRelayTest {
    private val appIp = ip("10.111.0.2")
    private val srvIp = ip("203.0.113.9")
    private val isn = 9000L
    private val out = LinkedBlockingQueue<ByteArray>()
    private val tracker = FlowTracker()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).also { it.isDaemon = true } }
    private val relay = TunRelay({ out.add(it) }, SinkholeConnector(holdAfterDataMs = 400, idleMs = 600, udpIdleMs = 300), tracker, { System.nanoTime() }, pool)

    @After fun stop() { relay.closeAll(); pool.shutdownNow() }

    private fun send(seq: Long, ack: Long, flags: Int, payload: ByteArray = ByteArray(0), port: Int = 443, sport: Int = 41000) {
        val raw = PacketCodec.tcp(appIp, sport, srvIp, port, seq, ack, flags, 65535, payload)
        assertTrue(relay.onPacket(raw, raw.size))
    }

    private fun next(ms: Long = 2000): IpPacket? = out.poll(ms, TimeUnit.MILLISECONDS)?.let { PacketCodec.parse(it) }

    private fun connect(port: Int = 443): Long {
        send(isn, 0, TcpFlags.SYN, port = port)
        val synAck = next()!!
        assertTrue(synAck.tcp!!.has(TcpFlags.SYN) && synAck.tcp!!.has(TcpFlags.ACK))
        assertEquals(isn + 1, synAck.tcp!!.ack)
        assertEquals("203.0.113.9", synAck.srcText()) // answered in the server's name, on the phone
        val iss = synAck.tcp!!.seq
        send(isn + 1, iss + 1, TcpFlags.ACK, port = port)
        assertTrue(relay.hasTcpFlow(41000, "203.0.113.9", port))
        return iss
    }

    @Test
    fun `a TLS connection is answered locally, its server name read, then closed by the relay`() {
        val iss = connect()
        val hello = clientHello("graph.example-tracker.com", listOf("h2"))
        // The hello arrives in two segments, as a large one would.
        send(isn + 1, iss + 1, TcpFlags.ACK or TcpFlags.PSH, hello.copyOfRange(0, 40))
        assertEquals(isn + 41, next()!!.tcp!!.ack)
        send(isn + 41, iss + 1, TcpFlags.ACK or TcpFlags.PSH, hello.copyOfRange(40, hello.size))
        assertEquals(isn + 1 + hello.size, next()!!.tcp!!.ack)
        // After the hold time the "server" ends the stream: a FIN with no data, and never a byte of payload.
        val fin = next()!!
        assertTrue(fin.tcp!!.has(TcpFlags.FIN))
        assertEquals(0, fin.payloadLength)
        send(isn + 1 + hello.size, iss + 2, TcpFlags.FIN or TcpFlags.ACK)
        next() // ACK of the app's FIN
        waitUntil { relay.activeTcp() == 0 }
        val f = tracker.snapshot().single()
        assertEquals("graph.example-tracker.com", f.sni)
        assertEquals(TlsStatus.SNI_ONLY, f.tls)
        assertEquals(listOf("h2"), f.alpn)
        assertEquals(hello.size.toLong(), f.bytesUp)
        assertEquals(0L, f.bytesDown)
    }

    @Test
    fun `a cleartext HTTP request is read redacted and nothing is answered`() {
        val iss = connect(port = 80)
        val req = httpGet("collect.example.in", "/v1/e?email=asha@example.in&ev=open")
        send(isn + 1, iss + 1, TcpFlags.ACK or TcpFlags.PSH, req, port = 80)
        assertEquals(isn + 1 + req.size, next()!!.tcp!!.ack)
        val fin = next()!!
        assertTrue(fin.tcp!!.has(TcpFlags.FIN)); assertEquals(0, fin.payloadLength)
        val f = tracker.snapshot().single()
        assertEquals(TlsStatus.CLEARTEXT, f.tls)
        val h = f.http.single()
        assertEquals("GET", h.method); assertEquals("collect.example.in", h.host)
        assertEquals("/v1/e?email=[email]&ev=open", h.target)
        assertTrue(SensitiveKind.EMAIL in h.sensitive)
        assertNull(h.status) // no response ever came back
        assertFalse(h.toString().contains("asha"))
    }

    @Test
    fun `a connection on which the app sends nothing is closed after the idle time`() {
        connect(port = 5222)
        val fin = next(2000)
        assertNotNull(fin)
        assertTrue(fin!!.tcp!!.has(TcpFlags.FIN))
        val f = tracker.snapshot().single()
        assertEquals(0L, f.bytesUp)
        assertNull(f.sni)
    }

    @Test
    fun `a QUIC Initial over UDP 443 is read for its name and never answered`() {
        val packet = QuicInitialTestData.rfcPacket()
        val raw = PacketCodec.udp(appIp, 50000, srvIp, 443, packet)
        assertTrue(relay.onPacket(raw, raw.size))
        val f = tracker.snapshot().single()
        assertEquals(FlowProtocol.QUIC, f.protocol)
        assertEquals("example.com", f.sni)
        assertNull("no reply is ever written to the app", out.poll(500, TimeUnit.MILLISECONDS))
    }

    private fun waitUntil(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 2000
        while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(cond())
    }
}

/** The RFC 9001 Appendix A.2 client Initial, shared by the QUIC tests. */
object QuicInitialTestData {
    fun rfcPacket(): ByteArray {
        val text = QuicInitialTestData::class.java.getResourceAsStream("/netcapture/rfc9001-client-initial.hex")!!.bufferedReader().readText()
        val clean = text.lines().filterNot { it.startsWith("#") }.joinToString("").filter { it.isLetterOrDigit() }
        return ByteArray(clean.length / 2) { clean.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}
