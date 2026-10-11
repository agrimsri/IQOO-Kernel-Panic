package com.dpdpxray.core.quarantine

import com.dpdpxray.core.netcapture.CleartextPersonalData
import com.dpdpxray.core.netcapture.FlowProtocol
import com.dpdpxray.core.netcapture.FlowRecord
import com.dpdpxray.core.netcapture.PacketCodec
import com.dpdpxray.core.netcapture.TcpFlags
import com.dpdpxray.core.netcapture.TlsStatus
import com.dpdpxray.core.netcapture.TunRelay

/**
 * Which blocked packets a quarantine run hands to the sinkhole relay ([com.dpdpxray.core.netcapture.SinkholeConnector]
 * behind [com.dpdpxray.core.netcapture.TunRelay]), so the app's connections are answered on the phone and their first
 * message read. Everything else stays dropped exactly as before. Nothing is ever forwarded either way.
 */
object QuarantineSinkhole {
    /**
     * DNS transports stay plainly blocked: they are reported as DNS attempts (Android's Private DNS can send them on the
     * app's behalf), and answering them would change how Android judges its Private DNS server.
     */
    val KEEP_BLOCKED_PORTS = setOf(53, 853)

    private val SERVER_SCOPES = setOf(AddressScope.PUBLIC, AddressScope.PRIVATE)

    /**
     * IPv4 TCP (any port but [KEEP_BLOCKED_PORTS]) and QUIC (UDP 443) to a server-type address that is not the phone's
     * own tunnel resolver. IPv6 is not answered: the relay reads IPv4 only, so IPv6 attempts keep their address-only row.
     */
    fun answers(h: PacketHeader, resolverAddresses: Set<String>): Boolean {
        val port = h.destinationPort ?: return false
        if (h.ipVersion != 4 || h.fragment || h.networkControl) return false
        if (h.destination in resolverAddresses || h.scope !in SERVER_SCOPES || port in KEEP_BLOCKED_PORTS) return false
        return when (h.protocol) {
            AttemptProtocol.TCP_SYN, AttemptProtocol.TCP_OTHER -> true
            AttemptProtocol.UDP -> port == 443
            else -> false
        }
    }
}

/**
 * The quarantine tunnel's handling of every packet that is not a plain DNS question: it is recorded as a blocked attempt
 * exactly as before, and, when [QuarantineSinkhole.answers] it, handed to the sinkhole [relay]. The later segments of a
 * connection the relay is already answering (ACKs, the hello, FIN) are not recorded again, so each connection attempt
 * stays one SYN in the blocked list. Pure: the VPN service supplies the relay and the recording callback.
 */
class SinkholeTap(
    private val relay: TunRelay?,
    private val resolverAddresses: Set<String>,
    private val record: (tNanos: Long, header: PacketHeader?) -> Unit,
) {
    fun onPacket(tNanos: Long, packet: ByteArray, length: Int) {
        val header = IpHeaders.parse(packet, length)
        val answered = relay != null && header != null && QuarantineSinkhole.answers(header, resolverAddresses)
        val p = if (answered) PacketCodec.parse(packet, length) else null
        val newSyn = p?.tcp?.let { it.has(TcpFlags.SYN) && !it.has(TcpFlags.ACK) } ?: false
        val laterSegment = p != null && p.isTcp && !newSyn && relay!!.hasTcpFlow(p.srcPort, p.dstText(), p.dstPort)
        if (!laterSegment) record(tNanos, header)
        if (answered && p != null) relay!!.onPacket(packet, length)
    }
}

/** What a quarantine run's answered connections add up to. Pure: derived from saved observations. */
internal object QuarantineConnections {
    private val IPV4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}")

    /** Names the connections themselves stated (TLS/QUIC server name, HTTP Host), never DNS-derived. */
    fun serverNames(o: QuarantineObservations): List<String> =
        o.connections.mapNotNull { it.host?.lowercase()?.trimEnd('.') }.filterNot { IPV4.matches(it) }.distinct()

    /** [serverNames] that no lookup during the run asked for. */
    fun namesWithoutLookup(o: QuarantineObservations): List<String> {
        val looked = o.domains.map { it.hostname.lowercase().trimEnd('.') }.toSet()
        return serverNames(o).filter { it !in looked }
    }

    fun cleartextRequests(o: QuarantineObservations): List<String> =
        o.connections.flatMap { f -> f.http.map { "${it.method} ${it.host ?: f.dstIp}${it.target}" } }.distinct().take(MAX_LINES)

    fun cleartextPersonalData(o: QuarantineObservations): List<String> =
        o.connections.flatMap { f ->
            f.http.mapNotNull { h ->
                val hits = CleartextPersonalData.scan(h)
                if (hits.isEmpty()) null else "${h.method} ${h.host ?: f.dstIp}${h.target.substringBefore('?')}: ${CleartextPersonalData.describe(hits)}"
            }
        }.distinct().take(MAX_LINES)

    /** One timeline line per connection that said something; silent connections are covered by their blocked row. */
    fun timelineText(f: FlowRecord): String? {
        val endpoint = "${f.dstIp}:${f.dstPort}"
        val answered = "TraceVeil answered on the phone; nothing was forwarded."
        return when {
            f.tls == TlsStatus.CLEARTEXT && f.http.isNotEmpty() -> {
                val h = f.http.first()
                val hits = CleartextPersonalData.scan(h)
                "Sent plain HTTP (no encryption): ${h.method} ${h.host ?: f.dstIp}${h.target}" +
                    (if (hits.isEmpty()) "" else " · personal-data fields: ${CleartextPersonalData.describe(hits)}") + ". $answered"
            }
            f.tls == TlsStatus.ECH_HIDDEN -> {
                val what = if (f.protocol == FlowProtocol.QUIC) "Sent a QUIC (HTTP/3) hello" else "Opened HTTPS"
                "$what to $endpoint offering Encrypted Client Hello; the visible name${f.sni?.let { " ($it)" } ?: ""} may not be the real server. " +
                    if (f.protocol == FlowProtocol.QUIC) "Not answered; nothing was forwarded." else answered
            }
            f.protocol == FlowProtocol.QUIC && f.sni != null -> "Sent a QUIC (HTTP/3) hello for ${f.sni} to $endpoint. Not answered; nothing was forwarded."
            f.sni != null -> "Opened HTTPS to ${f.sni} ($endpoint); only the server name in the TLS hello was read. $answered"
            f.protocol == FlowProtocol.TCP && f.bytesUp > 0 -> "Opened a connection to $endpoint and sent ${f.bytesUp} bytes with no server name and no plain-HTTP request in them. $answered"
            else -> null
        }
    }

    private const val MAX_LINES = 50
}
