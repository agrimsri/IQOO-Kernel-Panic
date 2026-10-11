package com.dpdpxray.core.netcapture

import com.dpdpxray.core.netcapture.NetTestData.clientHello
import com.dpdpxray.core.netcapture.NetTestData.ip
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private class FakeServerTcp : UpstreamTcp {
    val received = LinkedBlockingQueue<ByteArray>()
    val toApp = LinkedBlockingQueue<ByteArray>()
    @Volatile var outputShut = false
    @Volatile var closed = false
    override fun write(b: ByteArray, off: Int, len: Int): Boolean { received.add(b.copyOfRange(off, off + len)); return true }
    override fun read(buf: ByteArray): Int {
        val b = toApp.take()
        if (b.isEmpty()) return -1
        val n = minOf(b.size, buf.size)
        System.arraycopy(b, 0, buf, 0, n)
        if (n < b.size) toApp.put(b.copyOfRange(n, b.size)) // keep the rest for the next read (order matters)
        return n
    }
    override fun shutdownOutput() { outputShut = true }
    override fun close() { closed = true; toApp.add(ByteArray(0)) }
    fun eof() = toApp.add(ByteArray(0))
}

private class FakeServerUdp : UpstreamUdp {
    val sent = LinkedBlockingQueue<ByteArray>()
    val replies = LinkedBlockingQueue<ByteArray>()
    override fun send(b: ByteArray, len: Int): Boolean { sent.add(b.copyOf(len)); return true }
    override fun receive(buf: ByteArray): Int {
        val r = replies.poll(5, TimeUnit.SECONDS) ?: return -1
        if (r.isEmpty()) return -1
        System.arraycopy(r, 0, buf, 0, r.size); return r.size
    }
    override fun close() { replies.add(ByteArray(0)) }
}

private class FakeConnector : UpstreamConnector {
    val tcp = LinkedBlockingQueue<FakeServerTcp>()
    val udp = LinkedBlockingQueue<FakeServerUdp>()
    val tcpCalls = AtomicInteger()
    @Volatile var failTcp = false
    @Volatile var gate: CountDownLatch? = null
    override fun tcp(dstIp: String, port: Int): UpstreamTcp? {
        tcpCalls.incrementAndGet()
        gate?.await(5, TimeUnit.SECONDS)
        if (failTcp) return null
        return FakeServerTcp().also { tcp.add(it) }
    }
    override fun udp(dstIp: String, port: Int): UpstreamUdp = FakeServerUdp().also { udp.add(it) }
}

class TunRelayTest {
    private val appIp = ip("10.111.0.2")
    private val srvIp = ip("203.0.113.9")
    private val isn = 5000L
    private val out = LinkedBlockingQueue<ByteArray>()
    private val connector = FakeConnector()
    private val tracker = FlowTracker()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).also { it.isDaemon = true } }
    private val relay = TunRelay({ out.add(it) }, connector, tracker, { System.nanoTime() }, pool)

    @After fun stop() { relay.closeAll(); pool.shutdownNow() }

    private fun send(seq: Long, ack: Long, flags: Int, payload: ByteArray = ByteArray(0), window: Int = 65535, port: Int = 443, sport: Int = 40000) {
        val raw = PacketCodec.tcp(appIp, sport, srvIp, port, seq, ack, flags, window, payload)
        assertTrue(relay.onPacket(raw, raw.size))
    }

    private fun next(ms: Long = 2000): IpPacket? = out.poll(ms, TimeUnit.MILLISECONDS)?.let { PacketCodec.parse(it) }

    /** Completes the handshake and returns the relay's initial sequence number. */
    private fun connect(window: Int = 65535): Long {
        send(isn, 0, TcpFlags.SYN)
        val synAck = next()!!
        assertTrue(synAck.tcp!!.has(TcpFlags.SYN) && synAck.tcp!!.has(TcpFlags.ACK))
        assertEquals(isn + 1, synAck.tcp!!.ack)
        assertNotNull(synAck.tcp!!.mss)
        val iss = synAck.tcp!!.seq
        send(isn + 1, iss + 1, TcpFlags.ACK, window = window)
        return iss
    }

    @Test
    fun `handshake, upload and download pass through unchanged`() {
        val iss = connect()
        val server = connector.tcp.poll(2, TimeUnit.SECONDS)!!
        send(isn + 1, iss + 1, TcpFlags.ACK or TcpFlags.PSH, "hello".toByteArray())
        assertEquals("hello", String(server.received.poll(2, TimeUnit.SECONDS)!!))
        assertEquals(isn + 6, next()!!.tcp!!.ack) // relay acknowledged the 5 bytes
        server.toApp.add("world".toByteArray())
        val seg = next()!!
        assertEquals("world", String(seg.payload())); assertEquals(iss + 1, seg.tcp!!.seq); assertEquals(isn + 6, seg.tcp!!.ack)
    }

    @Test
    fun `large download is split into mss-sized in-order segments`() {
        val iss = connect()
        val server = connector.tcp.poll(2, TimeUnit.SECONDS)!!
        val data = ByteArray(5000) { (it % 251).toByte() }
        server.toApp.add(data)
        var got = ByteArray(0); var expectSeq = iss + 1
        while (got.size < data.size) {
            val s = next()!!
            assertTrue(s.payloadLength <= TunRelay.MAX_MSS); assertEquals(expectSeq, s.tcp!!.seq)
            got += s.payload(); expectSeq += s.payloadLength
        }
        assertTrue(got.contentEquals(data))
    }

    @Test
    fun `sender stops at the app window and resumes when it acks`() {
        val iss = connect(window = 2800)
        val server = connector.tcp.poll(2, TimeUnit.SECONDS)!!
        server.toApp.add(ByteArray(10000))
        var total = 0
        while (true) { val s = next(400) ?: break; total += s.payloadLength }
        assertTrue("sent $total with a 2800 window", total in 1400..2800)
        send(isn + 1, iss + 1 + total, TcpFlags.ACK, window = 65535)
        var more = 0
        while (total + more < 10000) { val s = next() ?: break; more += s.payloadLength }
        assertEquals(10000, total + more)
    }

    @Test
    fun `unreachable server becomes a reset, not a hang`() {
        connector.failTcp = true
        send(isn, 0, TcpFlags.SYN)
        val r = next()!!
        assertTrue(r.tcp!!.has(TcpFlags.RST)); assertEquals(isn + 1, r.tcp!!.ack)
        assertTrue(tracker.snapshot().single().failed)
        assertEquals(0, relay.activeTcp())
    }

    @Test
    fun `repeated syn while connecting opens one upstream connection`() {
        connector.gate = CountDownLatch(1)
        repeat(3) { send(isn, 0, TcpFlags.SYN) }
        Thread.sleep(100)
        connector.gate!!.countDown()
        assertNotNull(next())
        assertEquals(1, connector.tcpCalls.get())
    }

    @Test
    fun `out-of-order data is not forwarded and the expected number is repeated`() {
        val iss = connect()
        val server = connector.tcp.poll(2, TimeUnit.SECONDS)!!
        send(isn + 100, iss + 1, TcpFlags.ACK, "late".toByteArray())
        assertEquals(isn + 1, next()!!.tcp!!.ack)
        assertNull(server.received.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `orderly close from both sides frees the flow`() {
        val iss = connect()
        val server = connector.tcp.poll(2, TimeUnit.SECONDS)!!
        server.eof()
        val fin = next()!!
        assertTrue(fin.tcp!!.has(TcpFlags.FIN))
        send(isn + 1, iss + 2, TcpFlags.FIN or TcpFlags.ACK)
        assertEquals(isn + 2, next()!!.tcp!!.ack)
        val deadline = System.currentTimeMillis() + 2000
        // The half-close is performed by the flow's writer thread, so it can land just after the flow is freed.
        while ((relay.activeTcp() > 0 || !server.outputShut) && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(0, relay.activeTcp()); assertTrue(server.outputShut)
    }

    @Test
    fun `app reset closes the upstream connection`() {
        val iss = connect()
        val server = connector.tcp.poll(2, TimeUnit.SECONDS)!!
        send(isn + 1, iss + 1, TcpFlags.RST)
        val deadline = System.currentTimeMillis() + 2000
        while (!server.closed && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(server.closed); assertEquals(0, relay.activeTcp())
    }

    @Test
    fun `packet for a connection the relay never saw gets a reset`() {
        send(777, 888, TcpFlags.ACK, "x".toByteArray())
        assertTrue(next()!!.tcp!!.has(TcpFlags.RST))
    }

    @Test
    fun `tls hello passing through is read by the tracker and forwarded intact`() {
        val iss = connect()
        val server = connector.tcp.poll(2, TimeUnit.SECONDS)!!
        val hello = clientHello("graph.tracker.example", listOf("h2"))
        send(isn + 1, iss + 1, TcpFlags.ACK or TcpFlags.PSH, hello)
        assertTrue(hello.contentEquals(server.received.poll(2, TimeUnit.SECONDS)!!))
        val f = tracker.snapshot().single()
        assertEquals("graph.tracker.example", f.sni); assertEquals(TlsStatus.SNI_ONLY, f.tls); assertEquals(hello.size.toLong(), f.bytesUp)
    }

    @Test
    fun `udp datagrams are relayed both ways and quic is labelled`() {
        val dns = PacketCodec.udp(appIp, 5353, ip("9.9.9.9"), 53, byteArrayOf(1, 2, 3))
        relay.onPacket(dns, dns.size)
        val u = connector.udp.poll(2, TimeUnit.SECONDS)!!
        assertEquals(listOf<Byte>(1, 2, 3), u.sent.poll(2, TimeUnit.SECONDS)!!.toList())
        u.replies.add(byteArrayOf(9, 9))
        val reply = next()!!
        assertTrue(reply.isUdp); assertEquals(5353, reply.dstPort); assertEquals(53, reply.srcPort); assertEquals(2, reply.payloadLength)

        val quic = PacketCodec.udp(appIp, 6000, srvIp, 443, byteArrayOf(0xC3.toByte(), 0, 0, 0, 1))
        relay.onPacket(quic, quic.size)
        assertTrue(tracker.snapshot().any { it.protocol == FlowProtocol.QUIC && it.dstPort == 443 })
    }

    @Test
    fun `non ipv4 or other protocols are reported unhandled`() {
        assertEquals(false, relay.onPacket(ByteArray(40) { 0x60 }, 40))
    }
}
