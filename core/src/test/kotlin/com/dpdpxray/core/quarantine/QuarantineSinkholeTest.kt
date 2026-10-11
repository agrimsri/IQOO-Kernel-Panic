package com.dpdpxray.core.quarantine

import com.dpdpxray.core.netcapture.FlowProtocol
import com.dpdpxray.core.netcapture.FlowRecord
import com.dpdpxray.core.netcapture.FlowTracker
import com.dpdpxray.core.netcapture.HttpExchange
import com.dpdpxray.core.netcapture.PacketCodec
import com.dpdpxray.core.netcapture.Redaction
import com.dpdpxray.core.netcapture.SinkholeConnector
import com.dpdpxray.core.netcapture.TcpFlags
import com.dpdpxray.core.netcapture.TlsStatus
import com.dpdpxray.core.netcapture.TunRelay
import com.dpdpxray.core.quarantine.QuarantinePackets.icmp
import com.dpdpxray.core.quarantine.QuarantinePackets.ipv4
import com.dpdpxray.core.quarantine.QuarantinePackets.ipv6
import com.dpdpxray.core.quarantine.QuarantinePackets.tcp
import com.dpdpxray.core.quarantine.QuarantinePackets.udp
import com.dpdpxray.core.quarantine.QuarantinePackets.v4
import com.dpdpxray.core.quarantine.QuarantinePackets.v6
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class QuarantineSinkholeTest {
    private val resolvers = setOf("10.111.0.53", "fd00:111::53")
    private val syn = 0x02

    private fun h(packet: ByteArray) = IpHeaders.parse(packet)!!

    @Test
    fun `IPv4 TCP and QUIC to servers are answered, while DNS ports, the phone resolver, IPv6 and the rest stay dropped`() {
        assertTrue(QuarantineSinkhole.answers(h(ipv4(v4("203.0.113.9"), 6, tcp(40000, 443, syn))), resolvers))
        assertTrue(QuarantineSinkhole.answers(h(ipv4(v4("203.0.113.9"), 6, tcp(40000, 80, 0x10))), resolvers))
        assertTrue(QuarantineSinkhole.answers(h(ipv4(v4("192.168.1.20"), 6, tcp(40000, 8009, syn))), resolvers))
        assertTrue(QuarantineSinkhole.answers(h(ipv4(v4("203.0.113.9"), 17, udp(50000, 443))), resolvers))
        assertFalse(QuarantineSinkhole.answers(h(ipv4(v4("203.0.113.9"), 6, tcp(40000, 853, syn))), resolvers))
        assertFalse(QuarantineSinkhole.answers(h(ipv4(v4("8.8.8.8"), 6, tcp(40000, 53, syn))), resolvers))
        assertFalse(QuarantineSinkhole.answers(h(ipv4(v4("10.111.0.53"), 6, tcp(40000, 443, syn))), resolvers))
        assertFalse(QuarantineSinkhole.answers(h(ipv4(v4("203.0.113.9"), 17, udp(50000, 123))), resolvers))
        assertFalse(QuarantineSinkhole.answers(h(ipv4(v4("224.0.0.251"), 17, udp(5353, 443))), resolvers))
        assertFalse(QuarantineSinkhole.answers(h(ipv4(v4("1.1.1.1"), 1, icmp(8))), resolvers))
        assertFalse(QuarantineSinkhole.answers(h(ipv6(v6("2001:db8::1"), 6, tcp(40000, 443, syn))), resolvers))
    }

    // --- The tunnel path, simulated: the app's TCP segments go in, the relay's answers come out. ---

    private val out = LinkedBlockingQueue<ByteArray>()
    private val tracker = FlowTracker()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).also { it.isDaemon = true } }
    private val relay = TunRelay({ out.add(it) }, SinkholeConnector(holdAfterDataMs = 400, idleMs = 600), tracker, { System.nanoTime() }, pool)
    private val recorder = QuarantineRecorder(resolvers)
    private val tap = SinkholeTap(relay, resolvers) { t, header -> recorder.recordPacket(t, header) }
    private val app = byteArrayOf(10, 111, 0, 2)
    private val server = byteArrayOf(203.toByte(), 0, 113, 9)

    @After fun stop() { relay.closeAll(); pool.shutdownNow() }

    private fun send(seq: Long, ack: Long, flags: Int, payload: ByteArray = ByteArray(0)) {
        val raw = PacketCodec.tcp(app, 42000, server, 443, seq, ack, flags, 65535, payload)
        tap.onPacket(System.nanoTime(), raw, raw.size)
    }

    @Test
    fun `an answered connection is one blocked SYN plus a named connection, and the app is never sent data`() {
        send(100, 0, TcpFlags.SYN)
        val synAck = PacketCodec.parse(out.poll(2, TimeUnit.SECONDS)!!)!!
        val iss = synAck.tcp!!.seq
        send(101, iss + 1, TcpFlags.ACK)
        val hello = com.dpdpxray.core.netcapture.NetTestData.clientHello("sdk.example-tracker.com")
        send(101, iss + 1, TcpFlags.ACK or TcpFlags.PSH, hello)
        val replies = generateSequence { out.poll(1500, TimeUnit.MILLISECONDS) }.take(3).toList().map { PacketCodec.parse(it)!! }
        assertTrue(replies.all { it.payloadLength == 0 }) // ACKs and a FIN only: no byte of payload ever reaches the app

        val o = recorder.snapshot()
        assertEquals(1, o.blocked.size)
        assertEquals(AttemptProtocol.TCP_SYN, o.blocked.single().protocol)
        assertEquals(1L, o.blockedPackets)
        val f = tracker.snapshot().single()
        assertEquals("sdk.example-tracker.com", f.sni)
        assertTrue(f.noLookup)
    }

    @Test
    fun `a stray segment of a connection opened before the run is still recorded and reset`() {
        send(5000, 6000, TcpFlags.ACK, "x".toByteArray())
        assertEquals(AttemptProtocol.TCP_OTHER, recorder.snapshot().blocked.single().protocol)
        val rst = PacketCodec.parse(out.poll(2, TimeUnit.SECONDS)!!)!!
        assertTrue(rst.tcp!!.has(TcpFlags.RST))
        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test
    fun `without a relay every packet is only recorded, as before`() {
        val plain = SinkholeTap(null, resolvers) { t, header -> recorder.recordPacket(t, header) }
        val raw = PacketCodec.tcp(app, 42000, server, 443, 1, 0, TcpFlags.SYN)
        plain.onPacket(1, raw, raw.size)
        plain.onPacket(2, raw, raw.size)
        assertEquals(2L, recorder.snapshot().blockedPackets)
        assertNull(out.poll(300, TimeUnit.MILLISECONDS))
    }

    // --- What a report makes of answered connections. ---

    private fun flow(id: Long, sni: String?, tls: TlsStatus, start: Long, http: List<HttpExchange> = emptyList(), proto: FlowProtocol = FlowProtocol.TCP, port: Int = 443, up: Long = 517) =
        FlowRecord(id, proto, "203.0.113.$id", port, start, start + 1_000_000, up, 0, sni = sni, tls = tls, http = http, noLookup = true)

    private fun report(connections: List<FlowRecord>, lookups: List<String> = emptyList()): QuarantineReport {
        val r = QuarantineRecorder(resolvers)
        lookups.forEachIndexed { i, name -> r.recordDns(1_100_000_000L + i, name, 1, "10.111.0.53") }
        val o = r.snapshot().copy(connections = connections, connectionsRead = true)
        return QuarantineReports.build(
            id = "quarantine-2", targetPackage = "com.demo", targetLabel = "Demo", startedAtEpochMs = 1_791_600_000_000,
            startedAtNanos = 1_000_000_000, endedAtNanos = 61_000_000_000, plannedDurationMs = 60_000,
            stopReason = QuarantineStopReason.TIMER, resolverAddresses = resolvers, observations = o,
        )
    }

    @Test
    fun `names read from connections, names never looked up, plain HTTP and personal-data fields are summarised`() {
        val target = Redaction.target("/v1/e?email=asha@example.in&first_name=Asha&ev=open")
        val get = HttpExchange(3_000_000_000, "GET", "collect.example.in", target.text, sensitive = target.found)
        val rep = report(
            listOf(
                flow(1, "dns.google", TlsStatus.SNI_ONLY, 2_000_000_000),
                flow(2, "api.example.com", TlsStatus.SNI_ONLY, 2_500_000_000),
                flow(3, "collect.example.in", TlsStatus.CLEARTEXT, 2_900_000_000, listOf(get), port = 80),
                flow(4, "public.example-ech.net", TlsStatus.ECH_HIDDEN, 4_000_000_000),
                flow(5, null, TlsStatus.OPAQUE, 5_000_000_000, proto = FlowProtocol.QUIC, up = 1200),
            ),
            lookups = listOf("api.example.com"),
        )
        val s = rep.summary
        assertEquals(5, s.connectionsAnswered)
        assertEquals(listOf("dns.google", "api.example.com", "collect.example.in", "public.example-ech.net"), s.serverNames)
        assertEquals(listOf("dns.google", "collect.example.in", "public.example-ech.net"), s.namesWithoutLookup)
        assertEquals(listOf("GET collect.example.in/v1/e?email=[email]&first_name=[personal]&ev=open"), s.cleartextRequests)
        assertEquals(listOf("GET collect.example.in/v1/e: email (email address), first_name (person's name, field name only)"), s.cleartextPersonalData)
        assertEquals(1, s.echConnections)
        val lines = rep.timeline.filter { it.kind == TimelineKind.CONNECTION }.map { it.text }
        assertEquals(4, lines.size) // the unnamed QUIC flow has nothing to say beyond its blocked row
        assertTrue(lines[0].startsWith("Opened HTTPS to dns.google"))
        assertTrue(lines.any { it.startsWith("Sent plain HTTP (no encryption): GET collect.example.in/v1/e?email=[email]") })
        assertTrue(lines.any { "may not be the real server" in it })
        assertTrue(lines.all { "nothing was forwarded" in it })
        val text = QuarantineReports.encode(rep)
        assertFalse(text.contains("asha") || text.contains("Asha"))
        assertEquals(rep, QuarantineReports.decode(text))
    }

    @Test
    fun `a report saved before connections were recorded still opens, with connections unknown`() {
        val text = QuarantineReports.encode(report(emptyList()))
            .replace(Regex(",\\s*\"connections\": \\[\\]"), "")
            .replace(Regex(",\\s*\"connectionsRead\": true"), "")
        val old = QuarantineReports.decode(text)
        assertNull(old.observations.connectionsRead)
        assertTrue(old.observations.connections.isEmpty())
    }
}
