package com.dpdpxray.app.netproof

import android.net.TrafficStats
import android.os.Process
import com.dpdpxray.core.model.CaptureHealth
import java.util.Locale

/** One `TrafficStats` reading of TraceVeil's own transmitted bytes and packets. Null = not read. */
data class UidTx(val bytes: Long?, val packets: Long?) {
    companion object {
        /** Value of [TrafficStats.UNSUPPORTED]. */
        const val UNSUPPORTED = -1L

        /** Reads this app's own transmit counters; Android reports only the calling app's UID. */
        fun now() = UidTx(
            TrafficStats.getUidTxBytes(Process.myUid()).takeIf { it != UNSUPPORTED },
            TrafficStats.getUidTxPackets(Process.myUid()).takeIf { it != UNSUPPORTED },
        )
    }
}

/** Counters for one capture session: TraceVeil's upload at start and end, and the DNS it relayed in between. */
data class SessionCounters(val start: UidTx, val end: UidTx, val dnsPayloadBytesSent: Long?, val dnsDatagramsSent: Long?) {
    companion object {
        fun from(health: CaptureHealth, start: UidTx, end: UidTx) =
            SessionCounters(start, end, health.dnsUpstreamPayloadBytesSent, health.dnsUpstreamDatagramsSent)
    }
}

/** An optional feature that may have used the network during the audit; [txBytes] null = not measured. */
data class OnlineUse(val label: String, val txBytes: Long?)

enum class ProofStatus { WITHIN_TOLERANCE, ABOVE_TOLERANCE, INCONSISTENT, UNCERTAIN }

sealed interface NetworkProof {
    data class Measured(
        val totalTxBytes: Long,
        val totalTxPackets: Long?,
        val dnsPayloadBytes: Long,
        val dnsDatagrams: Long,
        val online: List<OnlineUse>,
        /** May be negative: kept as measured, never clamped. */
        val remainderBytes: Long,
        val toleranceBytes: Long,
        /** Packets sent minus DNS datagrams; null when packet counters were not read. */
        val packetRemainder: Long?,
        val status: ProofStatus,
    ) : NetworkProof {
        val dnsBytesOnWire: Long get() = dnsPayloadBytes + dnsDatagrams * CaptureHealth.IPV4_UDP_HEADER_BYTES
    }

    data class Unavailable(val reason: String) : NetworkProof
}

/**
 * remainder = TraceVeil's measured upload − (DNS payload + datagrams × IPv4/UDP header) − measured optional online use.
 * `TrafficStats` counts whole IP packets while the relay counts DNS payload, so the remainder is an estimate.
 */
object NetworkProofCalculator {
    /** Proposal until tuned on a real audit: 1 KiB plus 1 % of the relayed DNS bytes. */
    const val BASE_TOLERANCE_BYTES = 1024L

    fun compute(sessions: List<SessionCounters>, online: List<OnlineUse> = emptyList()): NetworkProof {
        if (sessions.isEmpty()) return NetworkProof.Unavailable("no capture session was recorded")
        var tx = 0L
        var packets: Long? = 0L
        var payload = 0L
        var datagrams = 0L
        for ((i, s) in sessions.withIndex()) {
            val n = i + 1
            val a = s.start.bytes ?: return NetworkProof.Unavailable("upload counter not read at the start of session $n")
            val b = s.end.bytes ?: return NetworkProof.Unavailable("upload counter not read at the end of session $n")
            if (a < 0 || b < 0) return NetworkProof.Unavailable("upload counter unsupported on this phone (session $n)")
            if (b < a) return NetworkProof.Unavailable("upload counter reset during session $n")
            tx += b - a
            payload += s.dnsPayloadBytesSent ?: return NetworkProof.Unavailable("DNS relay bytes not recorded for session $n")
            datagrams += s.dnsDatagramsSent ?: return NetworkProof.Unavailable("DNS relay datagrams not recorded for session $n")
            val p0 = s.start.packets
            val p1 = s.end.packets
            packets = if (packets == null || p0 == null || p1 == null || p1 < p0) null else packets + (p1 - p0)
        }
        val wire = payload + datagrams * CaptureHealth.IPV4_UDP_HEADER_BYTES
        val remainder = tx - wire - online.sumOf { it.txBytes ?: 0L }
        val tolerance = BASE_TOLERANCE_BYTES + wire / 100
        val status = when {
            online.any { it.txBytes == null } -> ProofStatus.UNCERTAIN
            remainder > tolerance -> ProofStatus.ABOVE_TOLERANCE
            remainder < -tolerance -> ProofStatus.INCONSISTENT
            else -> ProofStatus.WITHIN_TOLERANCE
        }
        return NetworkProof.Measured(tx, packets, payload, datagrams, online, remainder, tolerance, packets?.minus(datagrams), status)
    }
}

/** Card wording: raw figures, units, formula and tolerance, with no claim beyond what two counters can support. */
object NetworkProofText {
    private fun n(v: Long) = String.format(Locale.US, "%,d", v)

    fun lines(proof: NetworkProof): List<String> = when (proof) {
        is NetworkProof.Unavailable -> listOf(
            "Network Proof is unavailable: ${proof.reason}.",
            "No figure is shown rather than an assumed zero.",
        )
        is NetworkProof.Measured -> buildList {
            add("TraceVeil's own upload during this audit: ${n(proof.totalTxBytes)} bytes" +
                (proof.totalTxPackets?.let { " in ${n(it)} packets" } ?: "") + " (Android per-app counter).")
            add("DNS lookups relayed for the audited app: ${n(proof.dnsBytesOnWire)} bytes (${n(proof.dnsPayloadBytes)} bytes of DNS " +
                "messages + ${n(proof.dnsDatagrams)} datagrams × ${CaptureHealth.IPV4_UDP_HEADER_BYTES} header bytes).")
            proof.online.forEach { add("${it.label} (optional, online): ${it.txBytes?.let { b -> "${n(b)} bytes" } ?: "not measured"}.") }
            add("Remainder: ${n(proof.remainderBytes)} bytes (tolerance ± ${n(proof.toleranceBytes)} bytes)." +
                (proof.packetRemainder?.let { " Packets beyond the DNS datagrams: ${n(it)}." } ?: ""))
            add(when (proof.status) {
                ProofStatus.WITHIN_TOLERANCE -> "The remainder is within tolerance of zero."
                ProofStatus.ABOVE_TOLERANCE -> "${n(proof.remainderBytes)} bytes are not explained by the DNS relay. This does not show that data was sent anywhere."
                ProofStatus.INCONSISTENT -> "The counters disagree (more DNS relayed than upload measured), so no conclusion is drawn."
                ProofStatus.UNCERTAIN -> "An online feature's bytes were not measured, so the remainder is uncertain."
            })
            add(
                "Android's per-app counter may also include other TraceVeil traffic and possibly TraceVeil's own writes into the tunnel; " +
                    "whether it does has not been measured. The remainder is an estimate, not proof: unexplained bytes do not show that data " +
                    "was sent anywhere, and a zero or near-zero figure does not prove the absence of uploads.",
            )
        }
    }
}
