package com.dpdpxray.core.netcapture

import com.dpdpxray.core.model.Phase
import kotlinx.serialization.Serializable

@Serializable
enum class FlowProtocol { TCP, UDP, QUIC }

/** How much of a connection TraceVeil could read. Never implies decryption unless it is [DECRYPTED]. */
@Serializable
enum class TlsStatus {
    /** Plain HTTP or another cleartext protocol. */
    CLEARTEXT,
    /** TLS whose server name was read from the hello; content not readable. */
    SNI_ONLY,
    /** TLS with Encrypted Client Hello: the true server name may be hidden. */
    ECH_HIDDEN,
    /** TLS terminated locally with a user-installed CA; requests were readable. */
    DECRYPTED,
    /** The app rejected interception (pinning or no user-CA trust); the host was passed through untouched. */
    PINNED_PASSTHROUGH,
    /** Encrypted or unknown payload with no readable hello (QUIC, custom protocols). */
    OPAQUE,
    NOT_APPLICABLE,
}

/** One plaintext or decrypted HTTP request, with every value redacted. */
@Serializable
data class HttpExchange(
    val tNanos: Long,
    val method: String,
    val host: String?,
    /** Path and query with values scrubbed. */
    val target: String,
    val status: Int? = null,
    val headers: Map<String, String> = emptyMap(),
    val sensitive: Set<SensitiveKind> = emptySet(),
    val phase: Phase = Phase.NO_CONSENT_SEEN,
)

/** Bytes moved in one short time bucket, so a long-lived connection can be split across consent phases. */
@Serializable
data class ByteSample(val tNanos: Long, val up: Long, val down: Long)

@Serializable
data class FlowRecord(
    val id: Long,
    val protocol: FlowProtocol,
    val dstIp: String,
    val dstPort: Int,
    val startNanos: Long,
    val endNanos: Long = 0,
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
    /** Server name read from the TLS hello or HTTP Host header. */
    val sni: String? = null,
    /** Hostname the app looked up that resolved to [dstIp], when a lookup was seen. */
    val dnsName: String? = null,
    val tls: TlsStatus = TlsStatus.NOT_APPLICABLE,
    val alpn: List<String> = emptyList(),
    val http: List<HttpExchange> = emptyList(),
    val samples: List<ByteSample> = emptyList(),
    val phase: Phase = Phase.NO_CONSENT_SEEN,
    /** Connection reached an address the app never looked up (hardcoded IP or hidden DNS). */
    val noLookup: Boolean = false,
    /** Connection could not be opened upstream. */
    val failed: Boolean = false,
) {
    /** Best name for the destination: what the connection itself says, then what DNS said. */
    val host: String? get() = sni ?: http.firstOrNull()?.host ?: dnsName
}

/** Bytes and connection counts per consent phase for one audit. */
@Serializable
data class PhaseTraffic(val phase: Phase, val connections: Int, val bytesUp: Long, val bytesDown: Long)
