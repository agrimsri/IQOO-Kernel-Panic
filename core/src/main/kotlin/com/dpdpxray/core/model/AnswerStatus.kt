package com.dpdpxray.core.model

import kotlinx.serialization.Serializable

/**
 * What became of the resolver reply for one lookup. Stored on [NetEvent.answerStatus]; null there means "not
 * recorded" (audits saved before this field, or the reply had not arrived when the session was saved), which is
 * different from every value below.
 *
 * Only COMPLETE and PARTIAL replies can carry [NetEvent.cnameChain] / [NetEvent.answerIps]. With COMPLETE, an empty
 * address list is a real "no address" outcome: NXDOMAIN when [NetEvent.rcode] is 3, NODATA when rcode is 0. With
 * PARTIAL it may be incomplete. The others never carry addresses, and consumers must not map or count them as answers.
 */
@Serializable
enum class AnswerStatus {
    /** Every resolver failed or timed out; the app received SERVFAIL from TraceVeil. */
    NO_REPLY,

    /** The reply matched the query and every answer record was read. */
    COMPLETE,

    /** The reply matched, but was truncated, capped, looping or partly unreadable; only verified records are kept. */
    PARTIAL,

    /** A reply arrived but did not answer this lookup's question (id, name, type or QR bit differ). */
    MISMATCHED,

    /** A reply arrived but its header or question could not be read. */
    MALFORMED,
}
