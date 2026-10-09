package com.dpdpxray.core.notice

import com.dpdpxray.core.dpdp.DpdpCorpus
import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.trackers.TrackerCatalog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Drafts a [RightsNotice] from a C2 finding. Deterministic: same sessions, same text. Each listed lookup is tied to an
 * observed Reject in the same session and timed on that session's clock; lookups after a later Accept are left out.
 * The wording asks the developer to confirm facts and never asserts a violation, a data transfer or a recipient.
 */
class RightsNoticeBuilder(
    private val catalog: TrackerCatalog,
    private val corpus: DpdpCorpus,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    init {
        val missing = CITED_IDS.filter { corpus.byId(it) == null }
        check(missing.isEmpty()) { "Rights notice cites passages missing from passages.json: ${missing.joinToString()}" }
    }

    /** Null when the result has no C2 finding or these sessions contain no observed Reject followed by tracker lookups. */
    fun build(result: AuditResult, sessions: List<AuditSession>): RightsNotice? {
        if (result.findings.none { it.checkId == CheckId.C2 }) return null
        val declined = sessions.mapNotNull { s -> declinedChoice(s)?.let { s to it } }
        val first = declined.firstOrNull()?.first ?: return null
        val choices = declined.filter { it.first.targetPackage == first.targetPackage }.map { it.second }
        return RightsNotice(first.targetLabel, first.targetPackage, choices, CITED_IDS, text(first, choices))
    }

    private fun declinedChoice(s: AuditSession): DeclinedChoice? {
        val ordered = s.consentEvents.sortedBy { it.tNanos }
        // Same rule as PhaseAssigner: only a Reject that was the user's first choice makes later lookups "after refusal".
        val reject = ordered.firstOrNull { it.kind == ConsentKind.ACCEPT || it.kind == ConsentKind.REJECT }
            ?.takeIf { it.kind == ConsentKind.REJECT } ?: return null
        val laterAccept = ordered.firstOrNull { it.kind == ConsentKind.ACCEPT && it.tNanos > reject.tNanos }?.tNanos ?: Long.MAX_VALUE

        val contacts = s.netEvents.asSequence()
            .filter { it.tNanos >= reject.tNanos && it.tNanos < laterAccept }
            .map(::classified)
            .filter { it.category.isTracking }
            .groupBy { it.hostname.lowercase().trimEnd('.') to it.viaPackage }
            .map { (key, events) ->
                val firstEvent = events.minBy { it.tNanos }
                NoticeContact(
                    hostname = key.first, entity = firstEvent.entity, firstDelayNanos = firstEvent.tNanos - reject.tNanos,
                    lookups = events.size, viaPackage = key.second, disguisedVia = events.firstNotNullOfOrNull { it.disguisedVia },
                )
            }
            .sortedWith(compareBy({ it.firstDelayNanos }, { it.hostname }))
        if (contacts.isEmpty()) return null

        val inferred = reject.nodeText?.contains(INFERRED_MARKER) == true
        return DeclinedChoice(
            sessionId = s.id, plan = s.plan, rejectNanos = reject.tNanos,
            rejectEpochMs = if (s.startedAtEpochMs > 0) s.startedAtEpochMs + (reject.tNanos - s.startedAtNanos) / 1_000_000 else null,
            choiceLabel = reject.nodeText?.takeUnless { inferred || it.isBlank() },
            inferred = inferred,
            contacts = contacts,
        )
    }

    /** Same classification the rules engine applies: a stored entity wins, otherwise the catalogue decides. */
    private fun classified(e: NetEvent): NetEvent =
        if (e.entity != null) e else catalog.classify(e.hostname, e.cnameChain)
            .let { e.copy(entity = it.entity, category = it.category, disguisedVia = e.disguisedVia ?: it.disguisedVia) }

    private fun text(app: AuditSession, choices: List<DeclinedChoice>): String = buildString {
        val label = app.targetLabel
        appendLine("Subject: Please confirm tracker activity after I declined consent in $label")
        appendLine()
        appendLine("To the team responsible for $label (${app.targetPackage}),")
        appendLine()
        choices.forEach { c ->
            val how = c.choiceLabel?.let { " by choosing \"$it\"" }.orEmpty()
            val plan = c.plan.label
            if (c.rejectEpochMs != null) appendLine("On ${DATE.withZone(zone).format(Instant.ofEpochMilli(c.rejectEpochMs))} I declined consent in $label$how ($plan).")
            else appendLine("I declined consent in $label$how ($plan). The date and time of that choice were not recorded.")
            if (c.inferred) appendLine("TraceVeil inferred the moment of my choice from the consent dialog closing; it did not receive the tap itself.")
            appendLine("After that choice, TraceVeil recorded these tracker lookups on my device:")
            c.contacts.take(MAX_CONTACTS).forEach { appendLine(contactLine(it)) }
            if (c.contacts.size > MAX_CONTACTS) appendLine("- and ${c.contacts.size - MAX_CONTACTS} more tracker domains, listed in the TraceVeil report")
            appendLine()
        }
        appendLine(
            "A DNS lookup is the step before a connection: it shows the device asked where these services are. " +
                "It does not show what data, if any, was sent, and it is not a legal conclusion.",
        )
        appendLine()
        appendLine("Please:")
        appendLine(
            "1. Confirm whether the app shared any of my personal data with these services, or processed it for them, " +
                "after I declined, and for which purpose.",
        )
        appendLine(
            "2. Treat my choice as a refusal of consent. To the extent you hold any consent from me, treat this message as " +
                "its withdrawal (${source("s6(4)")}), and stop, and have your Data Processors stop, processing my personal data " +
                "on the basis of that consent (${source("s6(6)")}).",
        )
        appendLine("3. Share the notice and the consent record you rely on for this processing (${source("s6(10)")}).")
        appendLine(
            "4. Tell me the link or other means to withdraw consent, exercise my rights and make a complaint to the " +
                "Data Protection Board (${source("r3(c)")}).",
        )
        appendLine()
        appendLine("Please check these observations against your own records and tell me if any detail is wrong.")
        appendLine()
        appendLine("Regards,")
        appendLine("[Your name]")
        appendLine("[Your contact details]")
    }

    private fun contactLine(c: NoticeContact): String {
        val about = listOfNotNull(c.entity, c.disguisedVia?.let { "alias observed for $it" })
        val name = c.hostname + (if (about.isEmpty()) "" else " (${about.joinToString(", ")})") +
            (c.viaPackage?.let { ", looked up by $it" }.orEmpty())
        val delay = String.format(Locale.US, "%.1f s after I declined", c.firstDelayNanos / 1e9)
        return "- $name: " + if (c.lookups == 1) delay else "${c.lookups} lookups, the first $delay"
    }

    private fun source(id: String): String = corpus.byId(id)!!.source

    companion object {
        /** Withdrawal, stopping processing, proof of notice and consent, and the means to exercise rights. */
        val CITED_IDS = listOf("s6(4)", "s6(6)", "s6(10)", "r3(c)")
        const val MAX_CONTACTS = 20

        /** The consent watcher's label for a choice it inferred from the dialog closing (ConsentAccessibilityService). */
        private const val INFERRED_MARKER = "inferred"
        private val DATE = DateTimeFormatter.ofPattern("d MMMM yyyy 'at' HH:mm:ss z", Locale.ENGLISH)
    }
}
