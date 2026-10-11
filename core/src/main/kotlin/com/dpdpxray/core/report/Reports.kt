package com.dpdpxray.core.report

import com.dpdpxray.core.darkpattern.DarkPatternScanner
import com.dpdpxray.core.darkpattern.DarkPatternText
import com.dpdpxray.core.dpdp.CitationService
import com.dpdpxray.core.fixes.FixTemplate
import com.dpdpxray.core.law.LegalLens
import com.dpdpxray.core.law.LegalProfile
import com.dpdpxray.core.law.LensPassage
import com.dpdpxray.core.law.LensText
import com.dpdpxray.core.law.PassageKind
import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.ConsentHistory
import com.dpdpxray.core.model.HistoryBounds
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.web.WebAudit
import com.dpdpxray.core.web.WebSites
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object AuditJson {
    val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    fun encodeSessions(sessions: List<AuditSession>): String = json.encodeToString(sessions)
    fun decodeSessions(text: String): List<AuditSession> = json.decodeFromString(text)
    fun encodeResult(result: AuditResult): String = json.encodeToString(result)
}

/** Builds the human-readable audit report (Markdown). The PDF on the phone is rendered from the same content. */
object MarkdownReport {
    const val DISCLAIMER = "Automated technical assessment based on network and on-screen evidence captured on this device. " +
        "This is not legal advice."
    const val PRIVACY_LINE = "0 bytes of audit data left this phone: capture, AI analysis and this report were produced on-device."

    /** The headline score's standard, the same under every legal lens. */
    const val SCORE_STANDARD = "Consent readiness out of 100: a consent-first standard (tracking waits for a clear opt-in), " +
        "the same under every legal lens. It is not a safety or compliance certificate."

    const val LENS_METHOD = "The legal lens changes which law is quoted and how each finding relates to it, never the evidence. " +
        "Required, Context and Not applicable are TraceVeil's reading of each law for review, not a legal verdict."

    /**
     * [profile] is the legal lens: it decides which law is quoted and how each finding relates to it. The evidence and the
     * consent-first score are the same under every lens. India (the default, kept for existing callers) keeps the DPDP
     * citations and the statutory penalty ceilings; no other lens shows a penalty figure.
     */
    fun build(
        result: AuditResult,
        sessions: List<AuditSession>,
        fixes: List<FixTemplate>,
        citations: CitationService,
        profile: LegalProfile = LegalProfile.INDIA,
        lens: LegalLens = LegalLens.default,
    ): String = buildString {
        val first = sessions.firstOrNull()
        val view = lens.view(result, profile)
        val p = view.profile
        val web = WebAudit.isWeb(sessions)
        if (web) appendLine("# TraceVeil website check — ${first?.targetLabel}")
        else appendLine("# TraceVeil report — ${first?.targetLabel ?: "Unknown app"} (`${first?.targetPackage ?: "?"}`)")
        appendLine()
        val bounds = result.historyBounds
        val score = when {
            bounds != null && result.score != null -> "Score: ${result.score}/100 · ${result.band.label} · based on your answer about consent history"
            bounds != null -> "Consent history unknown · ${bounds.verdict()}"
            result.band == ScoreBand.INCONCLUSIVE -> "Inconclusive"
            else -> "Score: ${result.score}/100 · ${result.band.label}"
        }
        appendLine("**$score**")
        appendLine()
        appendLine("- $SCORE_STANDARD")
        if (bounds != null) appendLine("- ${historyLine(bounds)}")
        appendLine("- Legal lens: ${p.title} (`${profile.name}`). ${p.status}")
        appendLine("- ${view.scoreLine}. ${view.scoreNote}")
        if (first != null) {
            appendLine("- Audited: ${date(first.startedAtEpochMs)} · ${sessions.joinToString { it.plan.label }}")
            if (first.deviceModel.isNotBlank()) appendLine("- Device: ${first.deviceModel} · Android ${first.androidVersion}")
        }
        appendLine("- ${if (web) WEB_PRIVACY_LINE else PRIVACY_LINE}")
        appendLine()

        appendLine("## Summary")
        appendLine()
        if (result.findings.isEmpty()) appendLine("No consent issues were detected in the captured evidence.")
        else if (p.penaltyFigures) {
            appendLine("| Check | Finding | Severity | Evidence | Under this lens | Penalty ceiling |")
            appendLine("|---|---|---|---|---|---|")
            for (lf in view.findings) {
                val f = lf.finding
                appendLine("| ${f.checkId.code} | ${f.title} | ${f.severity.label} | ${f.evidenceType.name.lowercase()} | ${p.relationLabel(lf.relation)} | ${f.penaltyBand.label} |")
            }
        } else {
            appendLine("| Check | Finding | Severity | Evidence | Under this lens |")
            appendLine("|---|---|---|---|---|")
            for (lf in view.findings) {
                val f = lf.finding
                appendLine("| ${f.checkId.code} | ${f.title} | ${f.severity.label} | ${f.evidenceType.name.lowercase()} | ${p.relationLabel(lf.relation)} |")
            }
        }
        appendLine()

        if (result.findings.isNotEmpty()) appendLine("## Findings")
        for (lf in view.findings) {
            val f = lf.finding
            appendLine()
            appendLine("### ${f.checkId.code} · ${f.title}")
            appendLine("*${f.severity.label} · ${f.evidenceType.name.lowercase()} · confidence ${pct(f.confidence)} · ${f.plans.joinToString { it.label }}*")
            appendLine()
            val explained = LensText.explanation(f, profile)
            appendLine(explained.text)
            if (explained.citesIndia) { appendLine(); appendLine("*${LensText.INDIA_WORDING_NOTE}*") }
            if (f.evidence.isNotEmpty()) {
                appendLine()
                appendLine("**Evidence**")
                f.evidence.forEach { appendLine("- $it") }
            }
            appendLine()
            appendLine("**Under ${p.name}: ${p.relationLabel(lf.relation)}.** ${lf.mapping.note}")
            val cites = lens.passagesFor(profile, f, citations)
            if (cites.isNotEmpty()) {
                appendLine()
                appendLine(if (profile == LegalProfile.INDIA) "**DPDP basis**" else "**Legal context (${p.name})**")
                for (c in cites) quote(c)
            }
        }

        darkPatternSection(sessions)

        if (fixes.isNotEmpty()) {
            appendLine()
            appendLine("## How to fix")
            for (fix in fixes) {
                appendLine()
                appendLine("### ${fix.title}")
                if (!fix.verified) appendLine("*Check the exact API for your SDK version in the vendor docs.*")
                fix.manifest?.let { appendLine(); appendLine("AndroidManifest.xml:"); appendLine("```xml"); appendLine(it); appendLine("```") }
                fix.code?.let { appendLine(); appendLine("```kotlin"); appendLine(it); appendLine("```") }
                if (fix.steps.isNotEmpty()) { appendLine(); fix.steps.forEach { appendLine("- $it") } }
                fix.docUrl?.let { appendLine(); appendLine("Docs: $it") }
            }
        }

        if (web) {
            webSections(sessions)
            appendLine()
            appendLine("## Method and limitations")
            appendLine("- ${WebAudit.METHOD}")
            appendLine("- ${WebAudit.REQUEST_CAVEAT}")
            appendLine("- ${WebSites.RULE}")
            WebAudit.LIMITS.forEach { appendLine("- $it") }
            appendLine("- *Observed* findings come from the requests, cookies and banner recorded in TraceVeil's browser. W1 and W2 are notes that do not change the score.")
            appendLine("- $LENS_METHOD")
            p.limitations.forEach { appendLine("- $it") }
            appendLine()
            appendLine("---")
            appendLine("*$DISCLAIMER*")
            return@buildString
        }

        appendLine()
        appendLine("## Network timeline")
        for (s in sessions) {
            val bg = s.backgroundStartedNanos
            appendLine()
            appendLine("**${s.plan.label}** — ${s.netEvents.size} lookups${s.consentChoice?.let { c -> ", user chose ${c.kind.name.lowercase()} at ${secs(c.tNanos - s.startedAtNanos)} s" } ?: ""}" +
                (bg?.let { ", app in the background from ${secs(it - s.startedAtNanos)} s" } ?: ""))
            s.consentEvents.filter { it.kind == ConsentKind.PERMISSION_DIALOG }.forEach {
                appendLine()
                appendLine("- Android permission dialog at ${secs(it.tNanos - s.startedAtNanos)} s: \"${it.nodeText.orEmpty()}\"")
            }
            appendLine()
            appendLine("| t (s) | Host |")
            appendLine("|---|---|")
            s.netEvents.sortedBy { it.tNanos }.take(60).forEach {
                appendLine("| ${secs(it.tNanos - s.startedAtNanos)} | ${it.hostname}${if (bg != null && it.tNanos >= bg) " (app in background)" else ""} |")
            }
        }

        appendLine()
        appendLine("## Method and limitations")
        appendLine("- Network evidence is DNS lookups made by the audited app (captured by a local, per-app VPN). A lookup shows the app tried to contact a server; payloads are not decrypted.")
        appendLine("- *Observed* findings come from captured traffic or the screen's accessibility tree. *Inferred* findings come from SDK documentation or the on-device AI's reading of the screen, and count half in the score.")
        appendLine("- $LENS_METHOD")
        p.limitations.forEach { appendLine("- $it") }
        appendLine()
        appendLine("---")
        appendLine("*$DISCLAIMER*")
    }

    /** Website check: each plan's requests (full addresses), then its cookies and storage keys. */
    private fun StringBuilder.webSections(sessions: List<AuditSession>) {
        appendLine()
        appendLine("## Requests")
        for (s in sessions) {
            val w = s.web ?: continue
            val trackers = w.requests.count { it.trackerEntity != null }
            appendLine()
            appendLine("**${s.plan.label}** — ${w.requests.size} requests, $trackers to catalogued trackers" +
                (s.consentChoice?.let { c -> ", user chose ${c.kind.name.lowercase()} at ${secs(c.tNanos - s.startedAtNanos)} s" } ?: "") +
                (if (w.requestsDropped > 0) ", ${w.requestsDropped} more not kept (limit reached)" else ""))
            appendLine()
            appendLine("| t (s) | Method | Type | Address | Tracker |")
            appendLine("|---|---|---|---|---|")
            w.requests.take(WEB_ROWS).forEach { r ->
                appendLine("| ${secs(r.tNanos - s.startedAtNanos)} | ${r.method} | ${r.type.label} | ${cell(clip(r.url, 120))} | ${r.trackerEntity?.let(::cell) ?: ""} |")
            }
            if (w.requests.size > WEB_ROWS) {
                appendLine()
                appendLine("…and ${w.requests.size - WEB_ROWS} more requests in the exported audit data (JSON).")
            }
        }
        appendLine()
        appendLine("## Cookies and storage")
        appendLine()
        appendLine("Cookie values are never stored; only their length. Expiry is known only for first-party cookies the page itself can read.")
        for (s in sessions) {
            val w = s.web ?: continue
            appendLine()
            appendLine("**${s.plan.label}** — ${w.cookies.size} cookies (${w.cookies.count { it.thirdParty }} third-party), ${w.storage.size} storage keys")
            if (w.cookies.isNotEmpty()) {
                appendLine()
                appendLine("| Cookie | Party | Site | Tracker | First seen | At the end |")
                appendLine("|---|---|---|---|---|---|")
                w.cookies.take(WEB_ROWS).forEach { c ->
                    appendLine("| ${cell(c.name)} | ${if (c.thirdParty) "third" else "first"} | ${c.site} | ${c.trackerEntity?.let(::cell) ?: ""} | " +
                        "${secs(c.firstSeenNanos - s.startedAtNanos)} s (${c.firstSeenAt.label}) | ${if (c.presentAtEnd) "present" else "removed"} |")
                }
            }
            if (w.storage.isNotEmpty()) {
                appendLine()
                appendLine("Storage keys: " + w.storage.take(WEB_ROWS).joinToString { "${cell(it.key)} (${it.area}, ${it.valueLength} characters)" })
            }
        }
    }

    private const val WEB_ROWS = 80

    const val WEB_PRIVACY_LINE = "The check opened the site over the internet in TraceVeil's own browser on this phone. The analysis " +
        "and this report were produced on the phone; no audit data was sent anywhere."

    private fun cell(s: String) = s.replace("|", "\\|").replace('\n', ' ')

    private fun clip(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"

    /**
     * The consent-history line of a bounded result: why it is a range (or whose answer picked the score) and both ends.
     * Shared by the reports so they say it the same way.
     */
    fun historyLine(b: HistoryBounds): String {
        val why = when (b.answer) {
            ConsentHistory.DECLARED_NEVER, ConsentHistory.DECLARED_AGREED -> "${b.answer?.line()}, which TraceVeil cannot check."
            else -> "Consent history unknown: the app kept its data and showed no consent screen, so whether the person agreed to " +
                "tracking earlier cannot be seen" + (if (b.answer == ConsentHistory.NOT_SURE) " (asked; the person is not sure)." else ".")
        }
        return "$why ${b.ends()}"
    }

    /** Dark-pattern signals (India CCPA 2023): their own section, apart from the scored findings. */
    private fun StringBuilder.darkPatternSection(sessions: List<AuditSession>) {
        val signals = DarkPatternScanner.scan(sessions)
        val summary = DarkPatternText.summary(signals, DarkPatternScanner.screensChecked(sessions))
        appendLine()
        appendLine("## ${DarkPatternText.CARD_TITLE}")
        appendLine()
        appendLine(if (signals.isEmpty()) "$summary." else "$summary. Not counted in the score.")
        for (s in signals) {
            appendLine()
            appendLine("### ${DarkPatternText.rowTitle(s)}")
            appendLine("*${DarkPatternText.chip(s)} · ${DarkPatternText.plans(s)}*")
            appendLine()
            appendLine(DarkPatternText.resembles(s))
            appendLine()
            s.evidence.forEach { appendLine("- $it") }
            appendLine()
            appendLine("> ${s.pattern.definition}")
            appendLine(">")
            appendLine("> — ${DarkPatternText.SOURCE_TITLE} #${s.pattern.number} (${DarkPatternText.SOURCE_URL})")
        }
        if (signals.any { it.evidenceType == EvidenceType.INFERRED }) {
            appendLine()
            appendLine("*${DarkPatternText.AI_NOTE}*")
        }
        appendLine()
        appendLine("*${DarkPatternText.FOOTER}*")
    }

    private fun StringBuilder.quote(c: LensPassage) {
        appendLine()
        appendLine("> ${c.text}")
        c.englishSummary?.let { appendLine(">"); appendLine("> *English summary (not an official translation):* $it") }
        appendLine(">")
        val where = listOfNotNull(c.url, c.retrieved?.let { "read $it" }).joinToString(", ")
        val kind = if (c.kind == PassageKind.LAW) "" else " · ${c.kind.label}"
        appendLine("> — ${c.source}${if (c.verbatim) "" else " (summary)"}$kind${if (where.isEmpty()) "" else " ($where)"}")
    }

    private fun date(epochMs: Long): String =
        if (epochMs <= 0) "—" else DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.US).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs))

    private fun secs(nanos: Long) = String.format(Locale.US, "%.1f", nanos / 1e9)
    private fun pct(d: Double) = "${(d * 100).toInt()}%"
}
