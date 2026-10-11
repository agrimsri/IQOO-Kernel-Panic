package com.dpdpxray.core.netcapture

import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.Phase

/**
 * Collects per-connection evidence from the relay: counts, TLS hello, and cleartext HTTP heads. Thread-safe and bounded
 * ([MAX_FLOWS] connections, [MAX_HTTP_PER_FLOW] requests each). Only metadata is kept; payload bytes are inspected in
 * place and never stored. Request lines and headers are redacted before they are saved.
 */
class FlowTracker(private val maxFlows: Int = MAX_FLOWS) {
    private class Flow(val id: Long, val protocol: FlowProtocol, val dstIp: String, val dstPort: Int, val start: Long) {
        var end = 0L
        var up = 0L
        var down = 0L
        var sni: String? = null
        var tls = TlsStatus.NOT_APPLICABLE
        var alpn: List<String> = emptyList()
        var failed = false
        var decided = false
        val sniff = java.io.ByteArrayOutputStream()
        val http = ArrayList<HttpExchange>()
        val samples = ArrayList<ByteSample>()
        var pendingStatus = false
    }

    private val lock = Any()
    private val flows = LinkedHashMap<Long, Flow>()
    private var nextId = 1L
    var dropped = 0
        private set

    fun open(protocol: FlowProtocol, dstIp: String, dstPort: Int, nowNanos: Long): Long = synchronized(lock) {
        val id = nextId++
        if (flows.size >= maxFlows) { dropped++; return -id }
        flows[id] = Flow(id, protocol, dstIp, dstPort, nowNanos).also {
            if (protocol == FlowProtocol.QUIC) it.tls = TlsStatus.OPAQUE
        }
        id
    }

    fun clientBytes(id: Long, data: ByteArray, off: Int, len: Int, now: Long) = synchronized(lock) {
        val f = flows[id] ?: return
        f.up += len
        sample(f, now, len.toLong(), 0)
        if (f.protocol != FlowProtocol.TCP) return
        if (!f.decided) sniff(f, data, off, len, now) else if (f.tls == TlsStatus.CLEARTEXT) httpRequest(f, data, off, len, now)
    }

    fun serverBytes(id: Long, data: ByteArray, off: Int, len: Int, now: Long) = synchronized(lock) {
        val f = flows[id] ?: return
        f.down += len
        sample(f, now, 0, len.toLong())
        if (f.pendingStatus && f.tls == TlsStatus.CLEARTEXT) {
            f.pendingStatus = false
            val head = HttpHeadParser.parse(data.copyOfRange(off, off + minOf(len, 2048)))
            val status = head?.status
            if (status != null && f.http.isNotEmpty()) f.http[f.http.size - 1] = f.http.last().copy(status = status)
        }
    }

    fun transportOnly(id: Long, now: Long, up: Long, down: Long) = synchronized(lock) {
        val f = flows[id] ?: return
        f.up += up; f.down += down
        sample(f, now, up, down)
    }

    fun setTls(id: Long, status: TlsStatus, now: Long = 0) = synchronized(lock) { flows[id]?.let { it.tls = status; it.decided = true } }

    fun failed(id: Long, now: Long) = synchronized(lock) { flows[id]?.let { it.failed = true; it.end = now } }

    fun close(id: Long, now: Long) = synchronized(lock) { flows[id]?.let { if (it.end == 0L) it.end = now } }

    /** Forgets every connection (a new audit plan starts). Ids keep counting so late callbacks never hit a new flow. */
    fun clear() = synchronized(lock) { flows.clear(); dropped = 0 }

    fun snapshot(ipNames: Map<String, String> = emptyMap()): List<FlowRecord> = synchronized(lock) {
        flows.values.map { f ->
            val dns = ipNames[f.dstIp]
            val noLookup = dns == null && !isLocal(f.dstIp) && f.dstPort != 53 && f.dstPort != 123
            FlowRecord(
                f.id, f.protocol, f.dstIp, f.dstPort, f.start, f.end, f.up, f.down, f.sni, dns, f.tls, f.alpn,
                f.http.toList(), f.samples.toList(), Phase.NO_CONSENT_SEEN, noLookup, f.failed,
            )
        }
    }

    private fun isLocal(ip: String): Boolean {
        val p = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) || p[0] >= 224
    }

    private fun sample(f: Flow, now: Long, up: Long, down: Long) {
        val last = f.samples.lastOrNull()
        if (last != null && (now - last.tNanos < BUCKET_NANOS || f.samples.size >= MAX_SAMPLES)) {
            f.samples[f.samples.size - 1] = last.copy(up = last.up + up, down = last.down + down)
        } else f.samples += ByteSample(now, up, down)
    }

    private fun sniff(f: Flow, data: ByteArray, off: Int, len: Int, now: Long) {
        f.sniff.write(data, off, len)
        val buf = f.sniff.toByteArray()
        if (buf.isEmpty()) return
        val first = buf[0].toInt() and 0xff
        if (first == 0x16) {
            val hello = TlsHello.parse(buf)
            if (hello != null) {
                f.sni = hello.sni
                f.alpn = hello.alpn
                f.tls = when {
                    hello.sni != null -> TlsStatus.SNI_ONLY
                    hello.ech -> TlsStatus.ECH_HIDDEN
                    else -> TlsStatus.OPAQUE
                }
                f.decided = true
                f.sniff.reset()
            } else if (buf.size > MAX_SNIFF) giveUp(f)
        } else if (HttpHeadParser.isHttp2Preface(buf)) {
            f.tls = TlsStatus.OPAQUE; f.decided = true; f.sniff.reset()
        } else if (looksLikeHttp(buf)) {
            val head = HttpHeadParser.parse(buf)
            if (head != null && head.isRequest) {
                f.tls = TlsStatus.CLEARTEXT; f.decided = true
                addHttp(f, head, now)
                f.sniff.reset()
            } else if (buf.size > MAX_SNIFF * 4) giveUp(f)
        } else giveUp(f)
    }

    private fun giveUp(f: Flow) {
        f.tls = TlsStatus.OPAQUE; f.decided = true; f.sniff.reset()
    }

    private fun httpRequest(f: Flow, data: ByteArray, off: Int, len: Int, now: Long) {
        val head = HttpHeadParser.parse(data.copyOfRange(off, off + minOf(len, 16 * 1024))) ?: return
        if (head.isRequest) addHttp(f, head, now)
    }

    private fun addHttp(f: Flow, head: HttpHead, now: Long) {
        if (f.http.size >= MAX_HTTP_PER_FLOW) return
        val target = Redaction.target(head.target ?: "/")
        val (hdrs, hdrFound) = Redaction.headers(head.headers)
        val host = head.headers["host"]?.substringBefore(':')?.lowercase()
        if (f.sni == null) f.sni = host
        f.http += HttpExchange(now, head.method ?: "", host, target.text, null, hdrs, target.found + hdrFound)
        f.pendingStatus = true
    }

    private fun looksLikeHttp(b: ByteArray): Boolean {
        val s = String(b, 0, minOf(b.size, 8), Charsets.ISO_8859_1)
        return listOf("GET ", "POST ", "PUT ", "HEAD ", "DELETE ", "OPTIONS ", "PATCH ", "CONNECT ", "TRACE ").any { s.startsWith(it) || it.startsWith(s) && s.length < it.length }
    }

    companion object {
        const val MAX_FLOWS = 3000
        const val MAX_HTTP_PER_FLOW = 50
        const val MAX_SAMPLES = 400
        const val MAX_SNIFF = 4096
        const val BUCKET_NANOS = 500_000_000L
    }
}

/** Where the user's first Accept/Reject falls on the shared clock, and what that makes any moment. */
class PhaseClock(private val choiceNanos: Long?, private val accepted: Boolean, private val screenSeen: Boolean) {
    fun at(t: Long): Phase = when {
        choiceNanos != null && t < choiceNanos -> Phase.BEFORE_CONSENT
        choiceNanos != null && accepted -> Phase.AFTER_ACCEPT
        choiceNanos != null -> Phase.AFTER_REJECT
        screenSeen -> Phase.BEFORE_CONSENT
        else -> Phase.NO_CONSENT_SEEN
    }

    companion object {
        /** Same rule as [com.dpdpxray.core.audit.PhaseAssigner]: the earliest Accept or Reject decides. */
        fun from(consent: List<ConsentEvent>): PhaseClock {
            val choice = consent.sortedBy { it.tNanos }.firstOrNull { it.kind == ConsentKind.ACCEPT || it.kind == ConsentKind.REJECT }
            val seen = consent.any { it.kind == ConsentKind.SCREEN_APPEARED || it.kind == ConsentKind.MANUAL_MARK }
            return PhaseClock(choice?.tNanos, choice?.kind == ConsentKind.ACCEPT, seen)
        }
    }
}

object FlowPhases {
    /** Labels every flow (by start) and cleartext request (by time) with its consent phase. */
    fun assign(flows: List<FlowRecord>, clock: PhaseClock): List<FlowRecord> = flows.map { f ->
        f.copy(phase = clock.at(f.startNanos), http = f.http.map { it.copy(phase = clock.at(it.tNanos)) })
    }

    /** Bytes counted in the phase they actually moved; a connection counts once, in the phase it opened. */
    fun traffic(flows: List<FlowRecord>, clock: PhaseClock): List<PhaseTraffic> {
        val up = HashMap<Phase, Long>(); val down = HashMap<Phase, Long>(); val conns = HashMap<Phase, Int>()
        for (f in flows) {
            conns.merge(clock.at(f.startNanos), 1, Int::plus)
            if (f.samples.isEmpty()) {
                up.merge(clock.at(f.startNanos), f.bytesUp, Long::plus); down.merge(clock.at(f.startNanos), f.bytesDown, Long::plus)
            } else for (s in f.samples) {
                up.merge(clock.at(s.tNanos), s.up, Long::plus); down.merge(clock.at(s.tNanos), s.down, Long::plus)
            }
        }
        return Phase.values().filter { it in conns || it in up }.map { PhaseTraffic(it, conns[it] ?: 0, up[it] ?: 0, down[it] ?: 0) }
    }

    /** Address → hostname from the DNS answers the audit saw (latest lookup wins). */
    fun ipNames(events: List<NetEvent>): Map<String, String> {
        val m = HashMap<String, String>()
        for (e in events.sortedBy { it.tNanos }) for (ip in e.answerIps) m[ip] = e.cnameChain.lastOrNull() ?: e.hostname
        return m
    }
}
