package com.dpdpxray.core.score

import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.CaptureHealth
import com.dpdpxray.core.model.NetEvent
import java.util.Locale

/** Ordered weakest first. */
enum class ConfidenceLevel(val label: String) { LOW("Low"), MEDIUM("Medium"), HIGH("High") }

/**
 * How far the captured evidence can be trusted. [reasons] lists the limiting factors, most limiting first, ending with
 * the lookup count; for High it is just the count. [summary] is the one-line badge text.
 */
data class ConfidenceGrade(val level: ConfidenceLevel, val detail: String, val reasons: List<String>) {
    val summary: String get() = "Evidence confidence: ${level.label}: $detail"
}

/**
 * Grades capture completeness. It never changes findings or scores: an audit with zero lookups still gets V0 and an
 * inconclusive result from the rules engine, and grades Low here. Unknown counters are never read as healthy zeroes.
 *
 * The thresholds are proposals to tune on the phone. Each is the largest value still allowed at the higher level.
 */
object ConfidenceGrader {
    /** Fewer lookups than this: Low. Matches the rules engine's V0 visibility floor. */
    const val MIN_LOOKUPS = 2

    /** Fewer lookups than this: at most Medium. */
    const val HIGH_MIN_LOOKUPS = 20

    /** A shorter capture window: at most Medium. */
    const val MIN_WINDOW_NANOS = 10_000_000_000L

    /** Unreadable plain-DNS packets (all unparsed packets for builds without the breakdown), as a share of DNS packets. */
    const val MALFORMED_PACKETS_MEDIUM_PERCENT = 2
    const val MALFORMED_PACKETS_LOW_PERCENT = 20

    /** Lookups for which every resolver failed, as a share of parsed lookups. */
    const val RELAY_FAILURES_MEDIUM_PERCENT = 5
    const val RELAY_FAILURES_LOW_PERCENT = 25

    /** Replies not fully read (PARTIAL, MISMATCHED, MALFORMED), as a share of relayed replies. */
    const val MALFORMED_REPLIES_MEDIUM_PERCENT = 5
    const val MALFORMED_REPLIES_LOW_PERCENT = 25

    /** Any dropped event caps the grade at Medium; a larger share of parsed lookups than this makes it Low. */
    const val DROPPED_LOW_PERCENT = 10

    fun grade(session: AuditSession): ConfidenceGrade = grade(session.captureHealth, session.netEvents)

    /** [health] null means it was not recorded (e.g. an older audit), which caps the grade at Medium. */
    fun grade(health: CaptureHealth?, events: List<NetEvent>): ConfidenceGrade {
        val lookups = health?.dnsQueriesParsed ?: events.size.toLong()
        val limits = mutableListOf<Pair<ConfidenceLevel, String>>()
        fun cap(level: ConfidenceLevel, reason: String) { limits += level to reason }

        when {
            lookups == 0L -> cap(ConfidenceLevel.LOW, NO_LOOKUPS)
            lookups < MIN_LOOKUPS -> cap(ConfidenceLevel.LOW, "${plural(lookups, "lookup")} was captured; at least $MIN_LOOKUPS are needed for any confidence")
            lookups < HIGH_MIN_LOOKUPS -> cap(ConfidenceLevel.MEDIUM, "Only ${plural(lookups, "lookup")}; $HIGH_MIN_LOOKUPS are needed for High")
        }

        if (health == null) {
            cap(ConfidenceLevel.MEDIUM, "Capture health was not recorded for this audit")
        } else {
            val missing = listOfNotNull(
                "parsed lookups".takeIf { health.dnsQueriesParsed == null },
                "malformed packets".takeIf { health.malformedPackets == null },
                "relay failures".takeIf { health.dnsRelayFailures == null },
                "reply outcomes".takeIf { health.dnsResponsesRelayed == null || health.malformedResponses == null },
                "dropped events".takeIf { health.eventsDropped == null },
                "capture window".takeIf { health.captureStartedNanos == null || health.captureEndedNanos == null },
            )
            if (missing.isNotEmpty()) cap(ConfidenceLevel.MEDIUM, "Not measured: ${missing.joinToString()}")

            health.eventsDropped?.takeIf { it > 0 }?.let { dropped ->
                val level = if (exceeds(dropped, lookups, DROPPED_LOW_PERCENT)) ConfidenceLevel.LOW else ConfidenceLevel.MEDIUM
                cap(level, "$dropped lookups were dropped because the event buffer was full")
            }
            val parsed = health.dnsQueriesParsed
            val malformed = unreadableDns(health)
            if (parsed != null && malformed != null && malformed > 0) {
                ratioCap(malformed, parsed + malformed, MALFORMED_PACKETS_MEDIUM_PERCENT, MALFORMED_PACKETS_LOW_PERCENT)
                    ?.let { cap(it, "$malformed of ${parsed + malformed} packets were not readable DNS questions") }
            }
            health.hiddenDnsPackets?.takeIf { it > 0 }?.let { hidden ->
                cap(ConfidenceLevel.MEDIUM, "$hidden packets used encrypted or TCP DNS, which TraceVeil cannot read; turn Private DNS off and re-run")
            }
            val failures = health.dnsRelayFailures
            if (parsed != null && failures != null && failures > 0) {
                ratioCap(failures, parsed, RELAY_FAILURES_MEDIUM_PERCENT, RELAY_FAILURES_LOW_PERCENT)
                    ?.let { cap(it, "$failures of $parsed lookups got no reply from any resolver") }
            }
            val replies = health.dnsResponsesRelayed
            val badReplies = health.malformedResponses
            if (replies != null && badReplies != null && replies > 0 && badReplies > 0) {
                ratioCap(badReplies, replies, MALFORMED_REPLIES_MEDIUM_PERCENT, MALFORMED_REPLIES_LOW_PERCENT)
                    ?.let { cap(it, "$badReplies of $replies replies could not be fully read") }
            }
            val start = health.captureStartedNanos
            val end = health.captureEndedNanos
            if (start != null && end != null && end - start < MIN_WINDOW_NANOS) {
                cap(ConfidenceLevel.MEDIUM, "Capture lasted only ${secs(end - start)} s; ${MIN_WINDOW_NANOS / 1_000_000_000} s are needed for High")
            }
            health.notes.forEach { cap(ConfidenceLevel.MEDIUM, it) }
        }

        val level = limits.minOfOrNull { it.first } ?: ConfidenceLevel.HIGH
        val ordered = limits.sortedBy { it.first }.map { it.second }
        if (lookups == 0L) return ConfidenceGrade(level, NO_LOOKUPS, ordered)
        val count = plural(lookups, "lookup") + when (val m = health?.let(::unreadableDns)) {
            null -> ""
            0L -> ", none malformed"
            else -> ", ${plural(m, "malformed packet")}"
        }
        val detail = if (ordered.isEmpty()) count else "$count · ${ordered.first()}"
        return ConfidenceGrade(level, detail, ordered + count)
    }

    /** The weakest session decides; its reasons are labelled with the plan. */
    fun gradeAudit(sessions: List<AuditSession>): ConfidenceGrade {
        if (sessions.isEmpty()) return ConfidenceGrade(ConfidenceLevel.LOW, "No audit sessions were captured", listOf("No audit sessions were captured"))
        val graded = sessions.map { it to grade(it) }
        val level = graded.minOf { it.second.level }
        val weakest = graded.filter { it.second.level == level }
        val reasons = weakest.flatMap { (s, g) -> g.reasons.map { "${s.plan.label}: $it" } }
        val first = weakest.first()
        return ConfidenceGrade(level, "${first.first.plan.label}: ${first.second.detail}", reasons)
    }

    private const val NO_LOOKUPS = "No DNS lookups were captured"

    /**
     * Unparsed packets that were meant as plain DNS but could not be read. Without the breakdown (older builds) every
     * unparsed packet counts, as before; non-DNS traffic and hidden DNS are judged separately when it is known.
     */
    private fun unreadableDns(h: CaptureHealth): Long? {
        val total = h.malformedPackets ?: return null
        if (h.hiddenDnsPackets == null || h.nonDnsPackets == null) return total
        val parts = h.hiddenDnsPackets + h.nonDnsPackets
        // Inconsistent counters are not trusted down to a smaller number: fall back to the whole total.
        return if (parts > total) total else total - parts
    }

    private fun ratioCap(part: Long, total: Long, mediumPercent: Int, lowPercent: Int): ConfidenceLevel? = when {
        total <= 0 -> null
        exceeds(part, total, lowPercent) -> ConfidenceLevel.LOW
        exceeds(part, total, mediumPercent) -> ConfidenceLevel.MEDIUM
        else -> null
    }

    /** part / total > percent / 100, in integer arithmetic. */
    private fun exceeds(part: Long, total: Long, percent: Int) = part * 100 > percent.toLong() * total

    private fun plural(n: Long, noun: String) = if (n == 1L) "1 $noun" else "$n ${noun}s"
    private fun secs(nanos: Long) = String.format(Locale.US, "%.1f", nanos / 1e9)
}
