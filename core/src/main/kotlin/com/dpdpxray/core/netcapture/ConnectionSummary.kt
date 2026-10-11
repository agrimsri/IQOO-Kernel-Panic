package com.dpdpxray.core.netcapture

import com.dpdpxray.core.model.AuditSession

/**
 * What an audit's connection capture adds up to, for one-line summaries (traceveil-ci check, Results). Null from [of]
 * when no plan recorded its capture depth (older audits): unknown, never "no connections".
 */
data class ConnectionSummary(
    /** "full-relay" when at least one plan captured connections, otherwise "dns-only". */
    val depth: String,
    val connections: Int,
    /** Connections whose own first message named the server (TLS or QUIC hello, HTTP Host). */
    val named: Int,
    val cleartextRequests: Int,
    /** Plain-HTTP requests with fields that look like personal data (see [CleartextPersonalData]). */
    val personalDataRequests: Int,
    /** Names the connections stated at addresses no DNS answer gave, that no lookup asked for. */
    val namesWithoutLookup: List<String>,
    val encryptedClientHello: Int,
    val quicUnnamed: Int,
) {
    val captured: Boolean get() = depth == ConnectionFacts.FULL_RELAY

    companion object {
        fun of(sessions: List<AuditSession>): ConnectionSummary? {
            if (sessions.none { it.captureDepth != null }) return null
            val relayed = sessions.filter { it.captureDepth == ConnectionFacts.FULL_RELAY }
            val flows = relayed.flatMap { it.flows }
            val requests = flows.filter { it.tls == TlsStatus.CLEARTEXT }.flatMap { it.http }
            return ConnectionSummary(
                depth = if (relayed.isEmpty()) "dns-only" else ConnectionFacts.FULL_RELAY,
                connections = flows.size,
                named = flows.count { it.sni != null || it.http.any { h -> h.host != null } },
                cleartextRequests = requests.size,
                personalDataRequests = requests.count { CleartextPersonalData.scan(it).isNotEmpty() },
                namesWithoutLookup = relayed.flatMap { ConnectionFacts.namesWithoutLookup(it) }.distinct(),
                encryptedClientHello = flows.count { it.tls == TlsStatus.ECH_HIDDEN },
                quicUnnamed = flows.count { it.protocol == FlowProtocol.QUIC && it.sni == null },
            )
        }
    }
}

object ConnectionFacts {
    const val FULL_RELAY = "full-relay"
    private val IPV4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}")

    /**
     * Names a plan's connections stated themselves (TLS or QUIC hello, HTTP Host) at addresses no DNS answer TraceVeil
     * saw had given ([FlowRecord.noLookup]), and that no lookup (or recorded alias) asked for. The app reached them
     * another way: its own DNS (for example DNS over HTTPS), or cached or built-in addresses.
     */
    fun namesWithoutLookup(s: AuditSession): List<String> {
        if (s.captureDepth != FULL_RELAY) return emptyList()
        val looked = s.netEvents.flatMap { listOf(it.hostname) + it.cnameChain }.map { it.lowercase().trimEnd('.') }.toSet()
        return s.flows.filter { it.noLookup }.mapNotNull { it.host?.lowercase()?.trimEnd('.') }
            .filterNot { IPV4.matches(it) || it in looked }.distinct()
    }
}
