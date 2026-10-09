package com.dpdpxray.core.model

import kotlinx.serialization.Serializable

/**
 * How complete one session's DNS capture was. Filled by the capture path, saved with the session, graded for the
 * confidence badge and read by Network Proof.
 *
 * Every counter is nullable: null means "not measured" (older build, counter unsupported, capture restarted), never
 * zero. Consumers must show unknown as unknown and must not clamp it, or a negative difference, into a reassuring 0.
 *
 * Times use the same clock as [NetEvent.tNanos] and [ConsentEvent.tNanos] (`SystemClock.elapsedRealtimeNanos`).
 * Counters cover only [captureStartedNanos]..[captureEndedNanos] of this session.
 *
 * Byte counters are DNS message bytes, i.e. UDP payload only, excluding IP and UDP headers. Android's per-UID
 * `TrafficStats` counts whole IP packets, so a comparison must add per-datagram header overhead (see
 * [IPV4_UDP_HEADER_BYTES]) and still treats the remainder as an estimate, not proof of what else was or was not sent.
 */
@Serializable
data class CaptureHealth(
    val schemaVersion: Int = SCHEMA_VERSION,
    val captureStartedNanos: Long? = null,
    val captureEndedNanos: Long? = null,
    /** Tunnel packets parsed as a DNS question from the audited app (or the included Play services). */
    val dnsQueriesParsed: Long? = null,
    /** Tunnel packets that could not be parsed as a DNS question: truncated, unsupported or not DNS. */
    val malformedPackets: Long? = null,
    /** Queries for which a resolver reply was received and returned to the app. */
    val dnsResponsesRelayed: Long? = null,
    /** Queries for which every resolver failed, so the app received SERVFAIL. */
    val dnsRelayFailures: Long? = null,
    /** Resolver replies whose answer records could not be fully read; the reply was still relayed unchanged. */
    val malformedResponses: Long? = null,
    /** Events kept in the session buffer. */
    val eventsRecorded: Long? = null,
    /** Events lost because the buffer was full ([eventBufferCapacity]). */
    val eventsDropped: Long? = null,
    val eventBufferCapacity: Int? = null,
    val firstLookupNanos: Long? = null,
    val lastLookupNanos: Long? = null,
    /** DNS message bytes TraceVeil sent to upstream resolvers, counting every attempt including fallback retries. */
    val dnsUpstreamPayloadBytesSent: Long? = null,
    /** UDP datagrams sent to upstream resolvers, one per attempt. */
    val dnsUpstreamDatagramsSent: Long? = null,
    /** DNS message bytes received from upstream resolvers. */
    val dnsUpstreamPayloadBytesReceived: Long? = null,
    val dnsUpstreamDatagramsReceived: Long? = null,
    /** Why a counter is missing or uncertain, e.g. "VPN revoked mid-capture; counters cover 0–12 s". */
    val notes: List<String> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 1

        /** IPv4 (20) + UDP (8) header bytes per datagram. The relay only uses IPv4 resolvers today. */
        const val IPV4_UDP_HEADER_BYTES = 28
    }
}
