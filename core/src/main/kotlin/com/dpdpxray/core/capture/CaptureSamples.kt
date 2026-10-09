package com.dpdpxray.core.capture

import com.dpdpxray.core.model.AnswerStatus
import com.dpdpxray.core.model.CaptureHealth
import com.dpdpxray.core.model.NetEvent

/**
 * SAMPLE DATA for UI work and tests (Network Proof, confidence badge, persistence, maps). These values were written
 * by hand to exercise each case; they are **not** device measurements and must be labelled as samples if shown.
 * Addresses come from documentation ranges (RFC 5737 / RFC 3849); names use `.example`/`.test`.
 */
object CaptureSamples {
    private const val S = 1_000_000_000L

    /** 41 lookups over 30 s, all relayed, one fallback retry. High confidence. */
    val healthy = CaptureHealth(
        captureStartedNanos = 0, captureEndedNanos = 30 * S,
        dnsQueriesParsed = 41, malformedPackets = 0, dnsResponsesRelayed = 41, dnsRelayFailures = 0, malformedResponses = 0,
        eventsRecorded = 41, eventsDropped = 0, eventBufferCapacity = 5_000,
        firstLookupNanos = 400_000_000, lastLookupNanos = 28 * S,
        dnsUpstreamPayloadBytesSent = 1_394, dnsUpstreamDatagramsSent = 42,
        dnsUpstreamPayloadBytesReceived = 4_510, dnsUpstreamDatagramsReceived = 41,
    )

    /** An audit saved before capture health existed: unknown, not healthy. */
    val notRecorded: CaptureHealth? = null

    /** A session in which capture never ran: every counter unknown. */
    val notMeasured = CaptureHealth(
        eventBufferCapacity = 5_000,
        notes = listOf("Capture was not running during this session; nothing was measured."),
    )

    /** More lookups than the buffer holds: 5,000 kept, 1,200 dropped. Low confidence. */
    val overflow = healthy.copy(
        dnsQueriesParsed = 6_200, dnsResponsesRelayed = 6_200, eventsRecorded = 5_000, eventsDropped = 1_200,
        dnsUpstreamPayloadBytesSent = 210_800, dnsUpstreamDatagramsSent = 6_200,
        dnsUpstreamPayloadBytesReceived = 682_000, dnsUpstreamDatagramsReceived = 6_200,
    )

    /** Resolvers timing out: 6 of 40 lookups got no reply after retries; one reply not fully read. Medium. */
    val resolverTrouble = healthy.copy(
        dnsQueriesParsed = 40, dnsResponsesRelayed = 34, dnsRelayFailures = 6, malformedResponses = 1, eventsRecorded = 40,
        dnsUpstreamPayloadBytesSent = 2_560, dnsUpstreamDatagramsSent = 80,
        dnsUpstreamPayloadBytesReceived = 3_740, dnsUpstreamDatagramsReceived = 34,
    )

    /** The VPN was revoked and restarted mid-session. Medium, with notes. */
    val interrupted = healthy.copy(
        notes = listOf(
            "Capture stopped 12.0 s after it started: VPN was turned off by the system or the user.",
            "Capture restarted after a 4.0 s gap; lookups during the gap were not seen.",
        ),
    )

    /** One event per reply outcome, plus a pending one (status not recorded). Only the first may be mapped. */
    val answerOutcomes: List<NetEvent> = listOf(
        NetEvent(1 * S, "metrics.shop.test", cnameChain = listOf("metrics.shop.test", "t.tracker.example"),
            answerIps = listOf("192.0.2.10", "2001:db8::10"), answerStatus = AnswerStatus.COMPLETE, rcode = 0),
        NetEvent(2 * S, "gone.shop.test", answerStatus = AnswerStatus.COMPLETE, rcode = 3), // NXDOMAIN
        NetEvent(3 * S, "v6only.shop.test", answerStatus = AnswerStatus.COMPLETE, rcode = 0), // NODATA
        NetEvent(4 * S, "long.shop.test", cnameChain = listOf("long.shop.test", "a.cdn.example"),
            answerStatus = AnswerStatus.PARTIAL, rcode = 0), // chain capped or truncated: no address attributed
        NetEvent(5 * S, "other.shop.test", answerStatus = AnswerStatus.MISMATCHED),
        NetEvent(6 * S, "broken.shop.test", answerStatus = AnswerStatus.MALFORMED),
        NetEvent(7 * S, "slow.shop.test", answerStatus = AnswerStatus.NO_REPLY),
        NetEvent(8 * S, "pending.shop.test"), // answerStatus null: not recorded
    )
}
