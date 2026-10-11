package com.dpdpxray.core.netcapture

import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ConcurrentHashMap

/** A real or fake outbound TCP connection. Reads block; a read of -1 is end of stream. */
interface UpstreamTcp {
    fun write(b: ByteArray, off: Int, len: Int): Boolean
    fun read(buf: ByteArray): Int
    fun shutdownOutput()
    fun close()
}

/** A real or fake outbound UDP association. [receive] blocks up to the connector's idle time; -1 ends the flow. */
interface UpstreamUdp {
    fun send(b: ByteArray, len: Int): Boolean
    fun receive(buf: ByteArray): Int
    fun close()
}

/** Opens outbound sockets that bypass the tunnel (on Android: `VpnService.protect`). Returns null when unreachable. */
interface UpstreamConnector {
    fun tcp(dstIp: String, port: Int): UpstreamTcp?
    fun udp(dstIp: String, port: Int): UpstreamUdp?
}

/**
 * A user-space TCP/UDP relay between a VPN tunnel and real sockets, written from RFC 793/768 behaviour.
 *
 * It terminates each app connection locally (so the app talks to this relay) and opens a separate real connection
 * upstream. The SYN-ACK is only sent once the upstream connect succeeds, so an unreachable server reaches the app as a
 * normal reset, never a silent hang. Bytes pass through unchanged; [FlowTracker] only reads the first bytes of each
 * connection in place. IPv4 only; the tunnel is not given an IPv6 route.
 *
 * Deliberate simplifications (the tunnel never drops or reorders): no retransmission toward the app, no congestion
 * control, no TCP options other than MSS, and one in-order stream per connection. Unrecognised packets return false
 * from [onPacket] so the caller can account for them.
 */
class TunRelay(
    private val toTun: (ByteArray) -> Unit,
    private val connector: UpstreamConnector,
    private val tracker: FlowTracker,
    private val clock: () -> Long,
    private val executor: Executor,
    private val maxTcpFlows: Int = 256,
    private val onUdpDns: (IpPacket) -> Unit = {},
) {
    private data class Key(val srcPort: Int, val dstIp: String, val dstPort: Int)

    private enum class St { CONNECTING, ESTABLISHED, CLOSED }

    private inner class TcpFlow(val key: Key, val appIp: ByteArray, val dst: ByteArray, val appIsn: Long, val mss: Int) {
        val lock = Object()
        var state = St.CONNECTING
        var flowId = 0L
        var up: UpstreamTcp? = null
        val iss = (System.nanoTime() and 0x7fffffffL)
        var sndNxt = iss
        var sndUna = iss
        var rcvNxt = (appIsn + 1) and MASK
        var wnd = 65535
        var finSent = false
        var appFin = false
        var lastActive = clock()
        val writes = LinkedBlockingQueue<ByteArray>()
        var queued = 0

        fun send(flags: Int, payload: ByteArray = ByteArray(0), off: Int = 0, len: Int = payload.size, mssOpt: Int? = null) {
            toTun(PacketCodec.tcp(dst, key.dstPort, appIp, key.srcPort, sndNxt, rcvNxt, flags, 65535, payload, off, len, mssOpt))
        }
    }

    private val tcpFlows = ConcurrentHashMap<Key, TcpFlow>()
    private val udpFlows = ConcurrentHashMap<Key, UdpFlow>()

    private inner class UdpFlow(val appIp: ByteArray, val dst: ByteArray, val key: Key, val flowId: Long, val up: UpstreamUdp) {
        @Volatile var lastActive = clock()
    }

    fun activeTcp() = tcpFlows.size
    fun activeUdp() = udpFlows.size

    /** Handles one packet from the tunnel. Returns false if it is not IPv4 TCP/UDP. */
    fun onPacket(buf: ByteArray, len: Int): Boolean {
        val p = PacketCodec.parse(buf, len) ?: return false
        if (p.isTcp) tcp(p) else udp(p)
        return true
    }

    // ---- TCP ----

    private fun tcp(p: IpPacket) {
        val h = p.tcp!!
        val key = Key(p.srcPort, p.dstText(), p.dstPort)
        val existing = tcpFlows[key]
        if (existing == null) {
            if (h.has(TcpFlags.SYN) && !h.has(TcpFlags.ACK)) newFlow(p, key)
            else if (!h.has(TcpFlags.RST)) rejectStray(p) // a connection we never saw (e.g. opened before the tunnel)
            return
        }
        synchronized(existing.lock) {
            existing.lastActive = clock()
            if (h.has(TcpFlags.RST)) { teardown(existing, silent = true); return }
            if (existing.state == St.CONNECTING) return // retransmitted SYN while connecting
            if (h.has(TcpFlags.ACK)) {
                if (seqLe(existing.sndUna, h.ack) && seqLe(h.ack, existing.sndNxt)) existing.sndUna = h.ack
                existing.wnd = h.window
                existing.lock.notifyAll()
            }
            var advanced = false
            if (p.payloadLength > 0) {
                if (h.seq == existing.rcvNxt) {
                    if (existing.queued + p.payloadLength > MAX_QUEUED) return // no ACK: the app retransmits later
                    existing.writes.add(p.payload())
                    existing.queued += p.payloadLength
                    existing.rcvNxt = (existing.rcvNxt + p.payloadLength) and MASK
                    tracker.clientBytes(existing.flowId, p.data, p.payloadOffset, p.payloadLength, clock())
                    advanced = true
                } else { existing.send(TcpFlags.ACK); return } // old or out-of-order data: repeat what we expect
            }
            if (h.has(TcpFlags.FIN) && !existing.appFin && ((h.seq + p.payloadLength) and MASK) == existing.rcvNxt) {
                existing.appFin = true
                existing.rcvNxt = (existing.rcvNxt + 1) and MASK
                existing.writes.add(FIN_MARK)
                advanced = true
            }
            if (advanced) existing.send(TcpFlags.ACK)
            if (existing.finSent && existing.appFin && existing.sndUna == existing.sndNxt) teardown(existing, silent = true)
        }
    }

    private fun newFlow(p: IpPacket, key: Key) {
        if (tcpFlows.size >= maxTcpFlows) { reset(p); return }
        val f = TcpFlow(key, p.src, p.dst, p.tcp!!.seq, minOf(p.tcp.mss ?: 536, MAX_MSS).coerceAtLeast(536))
        if (tcpFlows.putIfAbsent(key, f) != null) return
        f.flowId = tracker.open(FlowProtocol.TCP, key.dstIp, key.dstPort, clock())
        executor.execute { runTcp(f) }
    }

    private fun runTcp(f: TcpFlow) {
        val up = try { connector.tcp(f.key.dstIp, f.key.dstPort) } catch (_: Exception) { null }
        synchronized(f.lock) {
            if (f.state == St.CLOSED) { up?.close(); return }
            if (up == null) {
                tracker.failed(f.flowId, clock())
                f.send(TcpFlags.RST or TcpFlags.ACK)
                f.state = St.CLOSED; tcpFlows.remove(f.key, f)
                return
            }
            f.up = up
            f.send(TcpFlags.SYN or TcpFlags.ACK, mssOpt = MAX_MSS)
            f.sndNxt = (f.sndNxt + 1) and MASK
            f.state = St.ESTABLISHED
        }
        val u = f.up ?: return
        executor.execute { writer(f, u) }
        pump(f, u)
    }

    /** App → server, in order, off the tunnel-reading thread so a slow server cannot stall other connections. */
    private fun writer(f: TcpFlow, up: UpstreamTcp) {
        try {
            while (true) {
                val b = f.writes.take()
                if (b === FIN_MARK) { up.shutdownOutput(); return }
                synchronized(f.lock) { f.queued -= b.size }
                if (!up.write(b, 0, b.size)) { synchronized(f.lock) { abort(f) }; return }
            }
        } catch (_: InterruptedException) {
        }
    }

    /** Server → app, split into segments and held back while the app's advertised window is full. */
    private fun pump(f: TcpFlow, up: UpstreamTcp) {
        val buf = ByteArray(f.mss)
        try {
            while (true) {
                val n = up.read(buf)
                if (n < 0) break
                if (n == 0) continue
                synchronized(f.lock) {
                    val deadline = clock() + WINDOW_WAIT_NANOS
                    while (f.state == St.ESTABLISHED && inflight(f) + n > maxOf(f.wnd, n)) {
                        val left = (deadline - clock()) / 1_000_000
                        if (left <= 0) { abort(f); return }
                        f.lock.wait(left)
                    }
                    if (f.state != St.ESTABLISHED) return
                    f.send(TcpFlags.ACK or TcpFlags.PSH, buf, 0, n)
                    f.sndNxt = (f.sndNxt + n) and MASK
                    f.lastActive = clock()
                }
                tracker.serverBytes(f.flowId, buf, 0, n, clock())
            }
            synchronized(f.lock) {
                if (f.state != St.ESTABLISHED) return
                f.send(TcpFlags.FIN or TcpFlags.ACK)
                f.sndNxt = (f.sndNxt + 1) and MASK
                f.finSent = true
                if (f.appFin && f.sndUna == f.sndNxt) teardown(f, silent = true)
            }
        } catch (_: Exception) {
            synchronized(f.lock) { abort(f) }
        }
    }

    private fun inflight(f: TcpFlow): Long = (f.sndNxt - f.sndUna) and MASK

    private fun abort(f: TcpFlow) {
        if (f.state == St.CLOSED) return
        f.send(TcpFlags.RST or TcpFlags.ACK)
        teardown(f, silent = true)
    }

    private fun teardown(f: TcpFlow, silent: Boolean) {
        if (f.state == St.CLOSED) return
        f.state = St.CLOSED
        tcpFlows.remove(f.key, f)
        tracker.close(f.flowId, clock())
        runCatching { f.up?.close() }
        f.writes.add(FIN_MARK)
        f.lock.notifyAll()
    }

    private fun rejectStray(p: IpPacket) = reset(p)

    private fun reset(p: IpPacket) {
        val h = p.tcp!!
        val ack = (h.seq + p.payloadLength + (if (h.has(TcpFlags.SYN) || h.has(TcpFlags.FIN)) 1 else 0)) and MASK
        toTun(PacketCodec.tcp(p.dst, p.dstPort, p.src, p.srcPort, if (h.has(TcpFlags.ACK)) h.ack else 0, ack, TcpFlags.RST or TcpFlags.ACK))
    }

    // ---- UDP ----

    private fun udp(p: IpPacket) {
        if (p.dstPort == 53) onUdpDns(p)
        val key = Key(p.srcPort, p.dstText(), p.dstPort)
        var f = udpFlows[key]
        if (f == null) {
            val up = try { connector.udp(key.dstIp, key.dstPort) } catch (_: Exception) { null } ?: return
            val quic = p.dstPort == 443 && p.payloadLength > 0 && (p.data[p.payloadOffset].toInt() and 0x80) != 0
            val id = tracker.open(if (quic) FlowProtocol.QUIC else FlowProtocol.UDP, key.dstIp, key.dstPort, clock())
            f = UdpFlow(p.src, p.dst, key, id, up)
            if (udpFlows.putIfAbsent(key, f) != null) { up.close(); f = udpFlows[key]!! } else {
                val flow = f
                executor.execute { udpReceive(flow) }
            }
        }
        f.lastActive = clock()
        val payload = p.payload()
        tracker.transportOnly(f.flowId, clock(), payload.size.toLong(), 0)
        f.up.send(payload, payload.size)
    }

    private fun udpReceive(f: UdpFlow) {
        val buf = ByteArray(65535)
        try {
            while (true) {
                val n = f.up.receive(buf)
                if (n < 0) break
                f.lastActive = clock()
                tracker.transportOnly(f.flowId, clock(), 0, n.toLong())
                toTun(PacketCodec.udp(f.dst, f.key.dstPort, f.appIp, f.key.srcPort, buf, n))
            }
        } catch (_: Exception) {
        } finally {
            udpFlows.remove(f.key, f)
            tracker.close(f.flowId, clock())
            runCatching { f.up.close() }
        }
    }

    /** Closes connections idle longer than [idleNanos]. Call about every 15 seconds. */
    fun sweep(idleNanos: Long = 120_000_000_000L) {
        val now = clock()
        for (f in tcpFlows.values) synchronized(f.lock) { if (now - f.lastActive > idleNanos) abort(f) }
        for (f in udpFlows.values) if (now - f.lastActive > idleNanos) runCatching { f.up.close() }
    }

    /** Ends every connection (tunnel closing). */
    fun closeAll() {
        for (f in tcpFlows.values) synchronized(f.lock) { teardown(f, silent = true) }
        for (f in udpFlows.values) runCatching { f.up.close() }
    }

    private fun seqLe(a: Long, b: Long): Boolean = ((b - a) and MASK) < 0x80000000L

    companion object {
        private const val MASK = 0xFFFFFFFFL
        const val MAX_MSS = 1400
        private const val MAX_QUEUED = 512 * 1024
        private const val WINDOW_WAIT_NANOS = 30_000_000_000L
        private val FIN_MARK = ByteArray(0)
    }
}
