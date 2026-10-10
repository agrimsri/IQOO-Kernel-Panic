package com.dpdpxray.core.report

import com.dpdpxray.core.audit.PhaseAssigner
import com.dpdpxray.core.dpdp.CitationService
import com.dpdpxray.core.fixes.FixTemplate
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.Category
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.Finding
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.Phase
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.Severity
import com.dpdpxray.core.score.ConfidenceGrader
import com.dpdpxray.core.trackers.TrackerCatalog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs

/**
 * Coverage and scope statements that open the HTML report, before any score. The Android app passes the same
 * statements that EvidenceReport places at the top of the Markdown and PDF exports.
 */
data class HtmlCoverage(
    /** Missing-choice, fresh-state and capture-timing statements. Empty means none applied. */
    val statements: List<String>,
    /** What ran on the phone and what this audit cannot see. */
    val scope: String,
    /** Shown when [statements] is empty. */
    val noGaps: String = "No missing planned consent choice was detected. This does not certify app safety or complete network visibility.",
    /** In-app synthetic sample, never a live audit. */
    val sample: Boolean = false,
    val sampleNotes: List<String> = emptyList(),
)

/**
 * Builds the interactive single-file HTML report: inline CSS and JS, no network requests (a Content-Security-Policy
 * forbids every fetch), no external fonts. Every visual is drawn from the recorded sessions; nothing is invented.
 * Same inputs as [MarkdownReport] plus the tracker catalogue used to label hosts.
 */
object HtmlReport {
    const val SIZE_BUDGET_BYTES = 400 * 1024

    /** Per-session cap for timeline dots and table rows; the graph still aggregates every lookup. */
    const val MAX_LOOKUPS_PER_SESSION = 400

    const val SAMPLE_BANNER = "Synthetic sample evidence — not a live audit."
    const val LOOKUP_CAVEAT = "A DNS lookup is an attempt to contact a host, not proof that personal data was sent."
    const val LEGAL_NOTE = "Legal text is context for review, not a verdict. The DPDP Act's main provisions are scheduled to " +
        "commence on 13 May 2027; read these passages as readiness guidance and confirm applicability with a qualified reviewer."

    /** Findings that rest on DNS lookups, where the upload of personal data itself is not established. */
    private val NETWORK_CHECKS = setOf(CheckId.C1, CheckId.C2, CheckId.C9, CheckId.C10, CheckId.NC, CheckId.C11, CheckId.C12)

    private val css: String by lazy { resource("/report/traceveil-report.css") }
    private val js: String by lazy { resource("/report/traceveil-report.js") }

    private class Lane(val session: AuditSession, val events: List<NetEvent>, val title: String, val index: Int)

    fun build(
        result: AuditResult,
        sessions: List<AuditSession>,
        fixes: List<FixTemplate>,
        citations: CitationService,
        catalog: TrackerCatalog,
        coverage: HtmlCoverage,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val attempts = sessions.groupBy { it.plan }
        val seen = HashMap<AuditPlan, Int>()
        val lanes = sessions.mapIndexed { i, s ->
            val n = attempts.getValue(s.plan).size
            val k = (seen[s.plan] ?: 0) + 1
            seen[s.plan] = k
            Lane(s, timeline(s, catalog), if (n > 1) "${s.plan.label} · attempt $k of $n" else s.plan.label, i)
        }
        val first = sessions.firstOrNull()
        val label = first?.targetLabel ?: "Unknown app"
        return buildString(64 * 1024) {
            append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
            append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; ")
            append("script-src 'unsafe-inline'; img-src data:; base-uri 'none'; form-action 'none'\">\n")
            append("<meta name=\"referrer\" content=\"no-referrer\">\n<meta name=\"color-scheme\" content=\"dark\">\n")
            append("<meta name=\"generator\" content=\"TraceVeil (generated on-device)\">\n")
            append("<title>TraceVeil report · ").append(esc(label)).append("</title>\n")
            append("<style>\n").append(css).append("\n</style>\n</head>\n<body>\n")
            append("<a class=\"skip\" href=\"#main\">Skip to the report</a>\n")
            topbar()
            append("<main id=\"main\">\n")
            coverageBox(coverage)
            hero(result, sessions, coverage, zone)
            timelineSection(lanes)
            constellationSection(lanes)
            findingsSection(result, fixes, citations, catalog, lanes)
            networkSection(lanes)
            methodSection()
            append("<footer class=\"foot\"><p class=\"disclaimer\">").append(esc(MarkdownReport.DISCLAIMER)).append("</p>")
            append("<p>Generated on the phone by TraceVeil. This file makes no network requests: its Content-Security-Policy blocks every fetch. ")
            append("Links to cited sources open only when you click them.</p></footer>\n")
            append("</main>\n<div id=\"tv-tip\" class=\"tip\" role=\"tooltip\" hidden></div>\n")
            append("<script type=\"application/json\" id=\"tv-graph\">").append(safeJson(graphJson(lanes, catalog, first))).append("</script>\n")
            append("<script>\n").append(js).append("\n</script>\n</body>\n</html>\n")
        }
    }

    // ---------------------------------------------------------------- sections

    private fun StringBuilder.topbar() {
        append("<header class=\"topbar\"><div class=\"topbar-in\">")
        append("<span class=\"brand\"><svg viewBox=\"0 0 32 32\" aria-hidden=\"true\"><defs><linearGradient id=\"bg\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\">")
        append("<stop offset=\"0\" stop-color=\"#FAA275\"/><stop offset=\".5\" stop-color=\"#FF8C61\"/><stop offset=\"1\" stop-color=\"#CE6A85\"/></linearGradient></defs>")
        append("<path d=\"M16 3 29 10v12L16 29 3 22V10z\" fill=\"none\" stroke=\"url(#bg)\" stroke-width=\"2.4\"/>")
        append("<path d=\"M9 13h14M11 17h10M13 21h6\" stroke=\"url(#bg)\" stroke-width=\"2\" stroke-linecap=\"round\"/></svg>TraceVeil</span>")
        append("<nav aria-label=\"Report sections\">")
        for ((id, name) in listOf("coverage" to "Coverage", "summary" to "Summary", "timeline" to "Timeline", "constellation" to "Constellation",
            "findings" to "Findings", "network" to "Network", "method" to "Method")) {
            append("<a href=\"#").append(id).append("\">").append(name).append("</a>")
        }
        append("</nav></div></header>\n")
    }

    private fun StringBuilder.coverageBox(c: HtmlCoverage) {
        append("<section id=\"coverage\" class=\"plate coverage\" aria-labelledby=\"coverage-h\">")
        append("<p class=\"eyebrow\">Read this first</p><h2 id=\"coverage-h\">Audit coverage</h2>")
        if (c.sample) {
            append("<p class=\"banner\"><strong>").append(esc(SAMPLE_BANNER)).append("</strong></p>")
            if (c.sampleNotes.isNotEmpty()) {
                append("<ul class=\"notes\">")
                c.sampleNotes.forEach { append("<li>").append(esc(it)).append("</li>") }
                append("</ul>")
            }
        }
        if (c.statements.isEmpty()) append("<p class=\"cov-line\">").append(esc(c.noGaps)).append("</p>")
        else c.statements.forEach { append("<p class=\"cov-line warn\">").append(esc(it)).append("</p>") }
        append("<p class=\"cov-scope\">").append(esc(c.scope)).append("</p></section>\n")
    }

    private fun StringBuilder.hero(result: AuditResult, sessions: List<AuditSession>, c: HtmlCoverage, zone: ZoneId) {
        val first = sessions.firstOrNull()
        val lookups = sessions.sumOf { it.netEvents.size }
        val band = bandClass(result.band)
        append("<section id=\"summary\" class=\"hero\" aria-labelledby=\"app-title\"><div class=\"hero-main\">")
        append("<p class=\"eyebrow\">Consent evidence report</p>")
        append("<h1 id=\"app-title\">").append(esc(first?.targetLabel ?: "Unknown app")).append("</h1>")
        append("<p class=\"pkg\">").append(esc(first?.targetPackage ?: "?")).append("</p><dl class=\"meta\">")
        if (first != null) {
            meta("Audited", date(first.startedAtEpochMs, zone))
            if (first.deviceModel.isNotBlank()) meta("Device", "${first.deviceModel} · Android ${first.androidVersion}")
        }
        meta("Plans", sessions.joinToString { it.plan.label }.ifEmpty { "None recorded" })
        meta("Lookups", "$lookups DNS ${if (lookups == 1) "lookup" else "lookups"} across ${sessions.size} ${if (sessions.size == 1) "session" else "sessions"}")
        append("</dl><div class=\"sevstrip\" aria-label=\"Findings by severity\">")
        val bySeverity = result.findings.groupBy { it.severity }
        if (result.findings.isEmpty()) append("<span class=\"sevcount\">No findings raised</span>")
        for (sev in Severity.entries) {
            val n = bySeverity[sev]?.size ?: continue
            append("<span class=\"sevcount sev-").append(sev.name.lowercase()).append("\"><i aria-hidden=\"true\"></i>")
                .append(n).append(' ').append(sev.label).append("</span>")
        }
        append("</div></div><div class=\"hero-score ").append(band).append("\">")
        val inconclusive = result.band == ScoreBand.INCONCLUSIVE || result.score == null
        val score = result.score ?: 0
        val circ = 2 * PI * 52
        append("<div class=\"dial\"><svg viewBox=\"0 0 132 132\" role=\"img\" aria-label=\"")
        append(if (inconclusive) "Score inconclusive" else "Score $score out of 100, ${esc(result.band.label)}").append("\">")
        append("<circle cx=\"66\" cy=\"66\" r=\"60\" class=\"dial-halo\"/><circle cx=\"66\" cy=\"66\" r=\"52\" class=\"dial-track\"/>")
        if (!inconclusive) {
            append("<circle cx=\"66\" cy=\"66\" r=\"52\" class=\"dial-arc\" stroke-dasharray=\"").append(f1(circ * score / 100.0))
                .append(' ').append(f1(circ)).append("\" transform=\"rotate(-90 66 66)\"/>")
        }
        append("</svg><span class=\"dial-num\">").append(if (inconclusive) "?" else score.toString()).append("</span></div>")
        append("<p class=\"band\">").append(esc(result.band.label)).append("</p>")
        append("<p class=\"dim small\">DPDP readiness out of 100 · not a safety or compliance certificate</p>")
        if (c.sample) append("<p class=\"conf\"><span class=\"chip ev-unknown\">Confidence not measured</span> Synthetic sample: capture confidence was not measured.</p>")
        else {
            val g = ConfidenceGrader.gradeAudit(sessions)
            append("<p class=\"conf\"><span class=\"chip conf-").append(g.level.name.lowercase()).append("\">Evidence confidence: ")
                .append(g.level.label).append("</span> ").append(esc(g.detail)).append("</p>")
        }
        append("</div></section>\n")
    }

    private fun StringBuilder.meta(k: String, v: String) {
        append("<div><dt>").append(esc(k)).append("</dt><dd>").append(esc(v)).append("</dd></div>")
    }

    private fun StringBuilder.sectionHead(id: String, n: String, title: String, lede: String) {
        append("<section id=\"").append(id).append("\" class=\"sect\" aria-labelledby=\"").append(id).append("-h\">")
        append("<div class=\"sect-head\"><p class=\"eyebrow\">Plate ").append(n).append("</p><h2 id=\"").append(id).append("-h\">")
            .append(esc(title)).append("</h2><p class=\"lede\">").append(esc(lede)).append("</p></div>")
    }

    private fun StringBuilder.timelineSection(lanes: List<Lane>) {
        sectionHead("timeline", "01", "Consent timeline",
            "Each lane is one capture session. Dots are DNS lookups on the session clock; the vertical line is the recorded Accept or Reject. Hover or tap a mark for details.")
        append("<div class=\"legend\" aria-label=\"Timeline legend\">")
        for (p in listOf(Phase.BEFORE_CONSENT, Phase.AFTER_REJECT, Phase.AFTER_ACCEPT, Phase.NO_CONSENT_SEEN)) {
            append("<span><svg viewBox=\"-8 -8 16 16\" aria-hidden=\"true\">").append(shape(p, 0.0, 0.0, 5.0, "")).append("</svg>Tracker · ")
                .append(phaseLabel(p)).append("</span>")
        }
        append("<span><svg viewBox=\"-8 -8 16 16\" aria-hidden=\"true\"><circle r=\"3.5\" class=\"o-known\"/></svg>Other lookup · catalogued</span>")
        append("<span><svg viewBox=\"-8 -8 16 16\" aria-hidden=\"true\"><circle r=\"3.5\" class=\"o-unk\"/></svg>Other lookup · unclassified</span>")
        append("<span><svg viewBox=\"-8 -8 16 16\" aria-hidden=\"true\"><rect x=\"-4\" y=\"-6\" width=\"8\" height=\"12\" rx=\"1.5\" class=\"cm-screen\"/></svg>Consent screen detected</span>")
        append("<span><svg viewBox=\"-8 -8 16 16\" aria-hidden=\"true\"><path d=\"M0-8V8\" class=\"choice-line reject\"/></svg>Recorded choice</span>")
        append("</div>")
        if (lanes.isEmpty()) append("<p class=\"plate\">No capture sessions were recorded.</p>")
        append("<div class=\"lanes\">")
        lanes.forEach { lane(it) }
        append("</div></section>\n")
    }

    private fun StringBuilder.lane(lane: Lane) {
        val s = lane.session
        val choice = s.consentChoice
        val trackers = lane.events.filter { it.category.isTracking }
        append("<article class=\"lane plate\" aria-label=\"").append(esc(lane.title)).append("\"><header class=\"lane-head\"><h3>")
            .append(esc(lane.title)).append("</h3><p>")
        append(lane.events.size).append(if (lane.events.size == 1) " lookup" else " lookups").append(" · ")
            .append(trackers.size).append(if (trackers.size == 1) " to a catalogued tracker" else " to catalogued trackers")
        for (p in listOf(Phase.BEFORE_CONSENT, Phase.AFTER_REJECT, Phase.AFTER_ACCEPT, Phase.NO_CONSENT_SEEN)) {
            val n = trackers.count { it.phase == p }
            if (n > 0) append(" · <span class=\"ph ").append(phaseClass(p)).append("\">").append(n).append(' ').append(phaseLabel(p).lowercase()).append("</span>")
        }
        append("</p><p class=\"choice-note\">")
        if (choice != null) {
            append(if (choice.kind == ConsentKind.ACCEPT) "Accept" else "Reject").append(" recorded at ").append(secs(choice.tNanos - s.startedAtNanos)).append(" s")
            choice.nodeText?.takeIf { it.isNotBlank() }?.let { append(" · button “").append(esc(it.take(60))).append("”") }
        } else {
            append(if (s.consentScreenSeen) "Consent screen detected, but no Accept or Reject was recorded; the choice is unknown."
                else "No consent screen was detected in this session.")
        }
        append("</p></header>")
        if (lane.events.size > MAX_LOOKUPS_PER_SESSION) {
            append("<p class=\"cov-line warn\">Showing the first ").append(MAX_LOOKUPS_PER_SESSION).append(" of ").append(lane.events.size)
                .append(" lookups in this lane; the constellation counts all of them.</p>")
        }
        append("<div class=\"lane-scroll\">")
        laneSvg(lane)
        append("</div></article>")
    }

    private fun StringBuilder.laneSvg(lane: Lane) {
        val s = lane.session
        val start = s.startedAtNanos
        fun rel(t: Long) = (t - start) / 1e9
        val events = lane.events.take(MAX_LOOKUPS_PER_SESSION)
        val consent = s.consentEvents.sortedBy { it.tNanos }
        val choice = s.consentChoice
        val window = if (s.endedAtNanos > start) rel(s.endedAtNanos) else 0.0
        val dur = maxOf(window, events.maxOfOrNull { rel(it.tNanos) } ?: 0.0, consent.maxOfOrNull { rel(it.tNanos) } ?: 0.0, 1.0)
        val span = dur * 1.03
        val w = 1000.0
        val left = 96.0
        val right = 18.0
        fun x(t: Double) = left + (t.coerceIn(0.0, span) / span) * (w - left - right)
        val top = 26.0
        val consentY = 46.0
        val trackTop = 66.0
        val trackY = 101.0
        val otherTop = 136.0
        val otherY = 158.0
        val bottom = 180.0
        append("<svg class=\"lane-svg\" viewBox=\"0 0 1000 206\" role=\"img\" aria-label=\"")
            .append(esc("${lane.title}: ${lane.events.size} lookups over ${f1(dur)} seconds. The Network table lists every lookup."))
            .append("\" data-lane=\"").append(lane.index).append("\">")

        // Phase regions, exactly as PhaseAssigner labels the lookups.
        val xEnd = w - right
        if (choice != null) {
            val xc = x(rel(choice.tNanos))
            val after = if (choice.kind == ConsentKind.ACCEPT) Phase.AFTER_ACCEPT else Phase.AFTER_REJECT
            rect(left, top, xc - left, bottom - top, "reg ${phaseClass(Phase.BEFORE_CONSENT)}")
            rect(xc, top, xEnd - xc, bottom - top, "reg ${phaseClass(after)}")
            if (xc - left > 130) regionLabel(left + 8, "BEFORE CONSENT", Phase.BEFORE_CONSENT)
        } else {
            val p = if (s.consentScreenSeen) Phase.BEFORE_CONSENT else Phase.NO_CONSENT_SEEN
            rect(left, top, xEnd - left, bottom - top, "reg ${phaseClass(p)}")
            regionLabel(left + 8, if (s.consentScreenSeen) "BEFORE CONSENT · NO CHOICE RECORDED" else "NO CONSENT SCREEN SEEN", p)
        }
        for (y in listOf(trackTop, otherTop)) {
            append("<line x1=\"").append(f1(left)).append("\" x2=\"").append(f1(xEnd)).append("\" y1=\"").append(f1(y)).append("\" y2=\"").append(f1(y)).append("\" class=\"row-sep\"/>")
        }
        rowLabel(consentY + 4, "CONSENT")
        rowLabel(trackY + 4, "TRACKERS")
        rowLabel(otherY + 4, "OTHER")

        // Axis
        val step = niceStep(span)
        append("<g class=\"axis\">")
        append("<line x1=\"").append(f1(left)).append("\" x2=\"").append(f1(xEnd)).append("\" y1=\"").append(f1(bottom)).append("\" y2=\"").append(f1(bottom)).append("\"/>")
        var t = 0.0
        while (t <= span + 1e-9) {
            val xt = x(t)
            append("<line x1=\"").append(f1(xt)).append("\" x2=\"").append(f1(xt)).append("\" y1=\"").append(f1(bottom)).append("\" y2=\"").append(f1(bottom + 5)).append("\"/>")
            append("<text x=\"").append(f1(xt)).append("\" y=\"").append(f1(bottom + 18)).append("\">").append(trimNum(t)).append(" s</text>")
            t += step
        }
        append("</g>")

        // Consent markers
        val firstScreen = consent.firstOrNull { it.kind == ConsentKind.SCREEN_APPEARED }
        for (e in consent) {
            val xe = x(rel(e.tNanos))
            val attrs = consentAttrs(e, rel(e.tNanos))
            when {
                e === choice -> {
                    val cls = if (e.kind == ConsentKind.ACCEPT) "accept" else "reject"
                    append("<line x1=\"").append(f1(xe)).append("\" x2=\"").append(f1(xe)).append("\" y1=\"").append(f1(top - 6)).append("\" y2=\"").append(f1(bottom))
                        .append("\" class=\"choice-line ").append(cls).append("\"/>")
                    append("<circle cx=\"").append(f1(xe)).append("\" cy=\"").append(f1(consentY)).append("\" r=\"6.5\" class=\"cm cm-choice ").append(cls)
                        .append("\" tabindex=\"0\"").append(attrs).append("/>")
                    val text = "${if (e.kind == ConsentKind.ACCEPT) "ACCEPT" else "REJECT"} · ${secs(e.tNanos - start)} s"
                    val pw = text.length * 6.9 + 18
                    val leftSide = xe + pw + 6 > xEnd
                    val px = if (leftSide) xe - pw - 6 else xe + 6
                    append("<g class=\"pill ").append(cls).append("\"><rect x=\"").append(f1(px)).append("\" y=\"4\" width=\"").append(f1(pw))
                        .append("\" height=\"19\" rx=\"9.5\"/><text x=\"").append(f1(px + 9)).append("\" y=\"17.5\">").append(esc(text)).append("</text></g>")
                }
                e.kind == ConsentKind.SCREEN_APPEARED -> {
                    append("<line x1=\"").append(f1(xe)).append("\" x2=\"").append(f1(xe)).append("\" y1=\"").append(f1(top)).append("\" y2=\"").append(f1(bottom))
                        .append("\" class=\"screen-line\"/>")
                    append("<rect x=\"").append(f1(xe - 5)).append("\" y=\"").append(f1(consentY - 8)).append("\" width=\"10\" height=\"16\" rx=\"2\" class=\"cm cm-screen\" tabindex=\"0\"")
                        .append(attrs).append("/>")
                    if (e === firstScreen) {
                        val xc = choice?.let { x(rel(it.tNanos)) }
                        val room = if (xc != null && xc > xe) xc - xe else xEnd - xe
                        if (room > 120) append("<text x=\"").append(f1(xe + 10)).append("\" y=\"").append(f1(consentY + 4)).append("\" class=\"cm-label\">Consent screen</text>")
                        else if (xe - left > 120) append("<text x=\"").append(f1(xe - 10)).append("\" y=\"").append(f1(consentY + 4)).append("\" class=\"cm-label end\">Consent screen</text>")
                    }
                }
                e.kind == ConsentKind.MANUAL_MARK ->
                    append("<path d=\"M").append(f1(xe)).append(' ').append(f1(consentY - 7)).append("l6 11h-12z\" class=\"cm cm-mark\" tabindex=\"0\"").append(attrs).append("/>")
                e.kind == ConsentKind.SETTINGS ->
                    append("<circle cx=\"").append(f1(xe)).append("\" cy=\"").append(f1(consentY)).append("\" r=\"4.5\" class=\"cm cm-settings\" tabindex=\"0\"").append(attrs).append("/>")
                else ->
                    append("<circle cx=\"").append(f1(xe)).append("\" cy=\"").append(f1(consentY)).append("\" r=\"3.5\" class=\"cm cm-later\" tabindex=\"0\"").append(attrs).append("/>")
            }
        }

        // Lookups: trackers in their own row, shaped and coloured by phase; everything else in the bottom row.
        val (trk, other) = events.partition { it.category.isTracking }
        val trkX = trk.map { x(rel(it.tNanos)) }
        stack(trkX, 11.0, 3).forEachIndexed { i, lvl ->
            val e = trk[i]
            append(shape(e.phase, trkX[i], trackY + lvl * 9.0, 5.2, dotAttrs(e, rel(e.tNanos))))
        }
        val otherX = other.map { x(rel(it.tNanos)) }
        stack(otherX, 7.5, 2).forEachIndexed { i, lvl ->
            val e = other[i]
            append("<circle cx=\"").append(f1(otherX[i])).append("\" cy=\"").append(f1(otherY + lvl * 7.0)).append("\" r=\"3.4\" class=\"dot ")
                .append(if (e.entity != null || e.category == Category.FIRST_PARTY) "o-known" else "o-unk").append('"').append(dotAttrs(e, rel(e.tNanos))).append("/>")
        }
        append("</svg>")
    }

    private fun StringBuilder.rect(x: Double, y: Double, w: Double, h: Double, cls: String) {
        if (w <= 0) return
        append("<rect x=\"").append(f1(x)).append("\" y=\"").append(f1(y)).append("\" width=\"").append(f1(w)).append("\" height=\"").append(f1(h))
            .append("\" class=\"").append(cls).append("\"/>")
    }

    private fun StringBuilder.regionLabel(x: Double, text: String, p: Phase) {
        append("<text x=\"").append(f1(x)).append("\" y=\"17\" class=\"reg-label ").append(phaseClass(p)).append("\">").append(esc(text)).append("</text>")
    }

    private fun StringBuilder.rowLabel(y: Double, text: String) {
        append("<text x=\"12\" y=\"").append(f1(y)).append("\" class=\"row-label\">").append(text).append("</text>")
    }

    /** Tracker marks carry phase twice: colour and shape (circle, diamond, ring, square), so colour is never alone. */
    private fun shape(p: Phase, x: Double, y: Double, r: Double, attrs: String): String {
        val cls = "dot t ${phaseClass(p)}"
        return when (p) {
            Phase.BEFORE_CONSENT -> "<circle cx=\"${f1(x)}\" cy=\"${f1(y)}\" r=\"${f1(r)}\" class=\"$cls\"$attrs/>"
            Phase.AFTER_REJECT -> {
                val d = r * 1.3
                "<path d=\"M${f1(x)} ${f1(y - d)}L${f1(x + d)} ${f1(y)}L${f1(x)} ${f1(y + d)}L${f1(x - d)} ${f1(y)}Z\" class=\"$cls\"$attrs/>"
            }
            Phase.AFTER_ACCEPT -> "<circle cx=\"${f1(x)}\" cy=\"${f1(y)}\" r=\"${f1(r - 0.6)}\" class=\"$cls ring\"$attrs/>"
            Phase.NO_CONSENT_SEEN -> "<rect x=\"${f1(x - r * 0.9)}\" y=\"${f1(y - r * 0.9)}\" width=\"${f1(r * 1.8)}\" height=\"${f1(r * 1.8)}\" rx=\"1.2\" class=\"$cls\"$attrs/>"
        }
    }

    private fun dotAttrs(e: NetEvent, t: Double): String = buildString {
        append(" data-h=\"").append(esc(e.hostname)).append('"')
        append(" data-e=\"").append(esc(e.entity ?: "")).append('"')
        append(" data-c=\"").append(categoryLabel(e)).append('"')
        append(" data-t=\"").append(f1(t)).append('"')
        append(" data-p=\"").append(e.phase.name).append('"')
        aliasTrail(e)?.let { append(" data-a=\"").append(esc(it)).append('"') }
        e.viaPackage?.let { append(" data-v=\"").append(esc(it)).append('"') }
    }

    private fun consentAttrs(e: ConsentEvent, t: Double): String = buildString {
        append(" data-k=\"").append(esc(consentLabel(e.kind))).append('"')
        append(" data-t=\"").append(f1(t)).append('"')
        e.nodeText?.takeIf { it.isNotBlank() }?.let { append(" data-n=\"").append(esc(it.take(120))).append('"') }
        append(" aria-label=\"").append(esc("${consentLabel(e.kind)} at ${f1(t)} s")).append('"')
    }

    private fun StringBuilder.constellationSection(lanes: List<Lane>) {
        val all = lanes.flatMap { it.events }
        val hosts = all.map { normalise(it.hostname) }.distinct().size
        val entities = all.mapNotNull { it.entity }.distinct().size
        sectionHead("constellation", "02", "Evidence constellation",
            "The audited app at the centre, every contacted host around it, and the catalogued tracker entities those hosts belong to. Built only from this audit's recorded lookups; positions are a layout, not geography.")
        append("<div class=\"cst plate\"><div class=\"cst-stage\">")
        append("<canvas id=\"cst\" tabindex=\"0\" role=\"img\" aria-label=\"")
            .append(esc("Graph of $hosts contacted hosts and $entities tracker entities. Use the node list or the Network table for the same data as text."))
            .append("\"></canvas>")
        append("<div class=\"cst-legend\" aria-label=\"Constellation legend\">")
        append("<span><i class=\"n-app\"></i>Audited app</span><span><i class=\"n-ent\"></i>Tracker entity</span>")
        append("<span><i class=\"n-host\"></i>Host · tracker</span><span><i class=\"n-host-o\"></i>Host · other catalogued</span><span><i class=\"n-unk\"></i>Host · unclassified</span>")
        for (p in listOf(Phase.BEFORE_CONSENT, Phase.AFTER_REJECT, Phase.AFTER_ACCEPT, Phase.NO_CONSENT_SEEN)) {
            append("<span><b class=\"e ").append(phaseClass(p)).append("\"></b>").append(phaseLabel(p)).append("</span>")
        }
        append("<span><b class=\"e alias\"></b>Host → entity via alias</span>")
        append("<span class=\"dim\">Node size = number of lookups</span></div>")
        append("<div class=\"cst-tools\"><button type=\"button\" id=\"cst-spin\" aria-pressed=\"true\">Spin</button>")
        append("<button type=\"button\" id=\"cst-zin\" aria-label=\"Zoom in\">+</button><button type=\"button\" id=\"cst-zout\" aria-label=\"Zoom out\">−</button>")
        append("<button type=\"button\" id=\"cst-reset\">Reset</button></div>")
        append("<p class=\"cst-hint\">Drag to rotate · click the graph, then scroll or pinch to zoom · click a node to focus</p>")
        append("<noscript><p class=\"cst-hint\">The constellation needs JavaScript; the Network table below lists the same lookups.</p></noscript>")
        append("</div><aside class=\"cst-side\" aria-live=\"polite\"><label for=\"cst-pick\" class=\"eyebrow\">Focus a node</label>")
        append("<select id=\"cst-pick\"><option value=\"\">Whole graph</option></select><div id=\"cst-info\" class=\"cst-info\">")
        append("<p class=\"dim\">").append(hosts).append(if (hosts == 1) " host" else " hosts").append(" · ").append(entities)
            .append(if (entities == 1) " entity" else " entities").append(" · ").append(all.size).append(if (all.size == 1) " lookup" else " lookups").append("</p>")
        append("<p class=\"dim small\">Select a node to see its lookups, phases and alias trail.</p></div></aside></div></section>\n")
    }

    private fun StringBuilder.findingsSection(result: AuditResult, fixes: List<FixTemplate>, citations: CitationService, catalog: TrackerCatalog, lanes: List<Lane>) {
        sectionHead("findings", "03", "Findings",
            "Each finding names its evidence type, the cited legal passages and a developer fix you can copy. Observed means seen on this device; inferred means derived from SDK documentation or on-device AI.")
        if (result.findings.isEmpty()) {
            append("<div class=\"plate empty\"><p>No finding was raised from the captured evidence.</p><p class=\"dim\">")
            append("A missing finding is not proof of compliance or safety. Check the coverage statements at the top.</p></div>")
        }
        val shown = mutableSetOf<String>()
        val codeIds = intArrayOf(0)
        for (f in result.findings) {
            val keys = f.entities.mapNotNull { catalog.sdkKeyFor(it) }.toSet()
            val own = fixes.filter { fix -> f.checkId in fix.appliesTo && (fix.kind != "sdk" || fix.id in keys) }
            finding(f, own, shown, citations, lanes, codeIds)
        }
        val rest = fixes.filter { it.id !in shown }
        if (rest.isNotEmpty()) {
            append("<div class=\"plate more-fixes\"><h3>More fixes</h3>")
            rest.forEach { fix(it, codeIds); shown += it.id }
            append("</div>")
        }
        append("</section>\n")
    }

    private fun StringBuilder.finding(f: Finding, fixes: List<FixTemplate>, shown: MutableSet<String>, citations: CitationService, lanes: List<Lane>, codeIds: IntArray) {
        val sev = f.severity.name.lowercase()
        append("<article class=\"finding plate sev-").append(sev).append("\" id=\"finding-").append(esc(f.checkId.code)).append("\" data-check=\"")
            .append(esc(f.checkId.code)).append("\"><header class=\"f-head\">")
        append("<span class=\"f-code\">").append(esc(f.checkId.code)).append("</span>")
        append("<span class=\"sev sev-").append(sev).append("\"><i aria-hidden=\"true\"></i>").append(f.severity.label).append("</span>")
        if (f.evidenceType == EvidenceType.OBSERVED) append("<span class=\"chip ev-observed\">Observed</span>")
        else append("<span class=\"chip ev-inferred\">Inferred</span>")
        if (f.checkId in NETWORK_CHECKS) append("<span class=\"chip ev-unknown\" title=\"").append(esc(LOOKUP_CAVEAT)).append("\">Data sent · not established</span>")
        append("<span class=\"f-meta\">Confidence ").append((f.confidence * 100).toInt()).append("% · ").append(esc(f.plans.joinToString { it.label })).append("</span>")
        append("</header><h3>").append(esc(f.title)).append("</h3><p>").append(esc(f.explanation)).append("</p>")
        if (f.entities.isNotEmpty()) {
            append("<p class=\"ents\">")
            f.entities.forEach { append("<span class=\"ent\">").append(esc(it)).append("</span>") }
            append("</p>")
        }
        if (f.evidence.isNotEmpty()) {
            append("<div class=\"f-block\"><h4>Evidence</h4><ul class=\"evidence\">")
            f.evidence.forEach { append("<li>").append(esc(it)).append("</li>") }
            append("</ul></div>")
        }
        val aliases = aliasesFor(f, lanes)
        if (aliases.isNotEmpty()) {
            append("<div class=\"f-block\"><h4>Alias trail</h4><ul class=\"evidence\">")
            aliases.forEach { append("<li>").append(esc(it)).append("</li>") }
            append("</ul><p class=\"dim small\">A recorded CNAME links these names. Not every alias is a disguise, and an alias is not payload evidence.</p></div>")
        }
        val cites = citations.cite(f)
        if (cites.isNotEmpty()) {
            append("<details class=\"law\"><summary>Legal context · ").append(esc(cites.joinToString { it.source })).append("</summary>")
            for (p in cites) {
                append("<blockquote><p>").append(esc(p.text)).append("</p><footer>— ").append(esc(p.source))
                    .append(if (p.verbatim) " · verbatim" else " · summary").append("</footer></blockquote>")
            }
            append("<p class=\"dim small\">").append(esc(LEGAL_NOTE)).append("</p></details>")
        }
        var opened = false
        for (fix in fixes) {
            if (shown.add(fix.id)) { fix(fix, codeIds, open = !opened); opened = true }
            else append("<p class=\"fix-ref\">Fix: <a href=\"#fix-").append(esc(fix.id)).append("\">").append(esc(fix.title)).append("</a> (shown above)</p>")
        }
        append("</article>")
    }

    private fun StringBuilder.fix(fix: FixTemplate, codeIds: IntArray, open: Boolean = true) {
        append("<details class=\"fix\" id=\"fix-").append(esc(fix.id)).append(if (open) "\" open>" else "\">")
            .append("<summary><span class=\"fix-tag\">Fix</span>").append(esc(fix.title)).append("</summary>")
        if (!fix.verified) append("<p class=\"cov-line warn\">Check the exact API for your SDK version in the vendor docs.</p>")
        fix.manifest?.let { code("AndroidManifest.xml", it, codeIds) }
        fix.code?.let { code("Kotlin", it, codeIds) }
        if (fix.steps.isNotEmpty()) {
            append("<ul class=\"steps\">")
            fix.steps.forEach { append("<li>").append(esc(it)).append("</li>") }
            append("</ul>")
        }
        fix.docUrl?.let { append("<p class=\"small\">Vendor docs: ").append(link(it)).append("</p>") }
        append("</details>")
    }

    private fun StringBuilder.code(label: String, text: String, codeIds: IntArray) {
        val id = "code-${codeIds[0]++}"
        append("<div class=\"code\"><div class=\"code-bar\"><span>").append(esc(label)).append("</span><button type=\"button\" class=\"copy\" data-copy=\"")
            .append(id).append("\">Copy</button></div><pre id=\"").append(id).append("\"><code>").append(esc(text)).append("</code></pre></div>")
    }

    private fun StringBuilder.networkSection(lanes: List<Lane>) {
        val total = lanes.sumOf { minOf(it.events.size, MAX_LOOKUPS_PER_SESSION) }
        sectionHead("network", "04", "Network timeline",
            "Every recorded DNS lookup, per session, in time order. $LOOKUP_CAVEAT")
        append("<div class=\"plate net\"><div class=\"net-tools\"><label>Search <input type=\"search\" id=\"net-q\" placeholder=\"host or entity\" autocomplete=\"off\"></label>")
        append("<label>Session <select id=\"net-plan\"><option value=\"\">All</option>")
        lanes.forEach { append("<option value=\"").append(it.index).append("\">").append(esc(it.title)).append("</option>") }
        append("</select></label><label class=\"chk\"><input type=\"checkbox\" id=\"net-trk\"> Trackers only</label>")
        append("<span id=\"net-count\" class=\"dim small\" aria-live=\"polite\">").append(total).append(" rows</span></div>")
        append("<div class=\"table-scroll\"><table><thead><tr><th scope=\"col\">Session</th><th scope=\"col\" class=\"num\">t (s)</th><th scope=\"col\">Host</th>")
        append("<th scope=\"col\">Entity</th><th scope=\"col\">Category</th><th scope=\"col\">Phase</th><th scope=\"col\">Alias trail</th></tr></thead><tbody id=\"net-body\">")
        for (lane in lanes) {
            for (e in lane.events.take(MAX_LOOKUPS_PER_SESSION)) {
                append("<tr data-lane=\"").append(lane.index).append("\" data-trk=\"").append(if (e.category.isTracking) 1 else 0).append("\">")
                append("<td>").append(esc(lane.session.plan.label)).append("</td><td class=\"num\">").append(secs(e.tNanos - lane.session.startedAtNanos)).append("</td>")
                append("<td class=\"mono\">").append(esc(e.hostname)).append("</td><td>")
                if (e.entity != null) append(esc(e.entity)) else append("<span class=\"unk\">unclassified</span>")
                append("</td><td>").append(categoryLabel(e)).append("</td><td><span class=\"ph ").append(phaseClass(e.phase)).append("\">")
                    .append(phaseLabel(e.phase)).append("</span></td><td class=\"mono small\">").append(esc(aliasTrail(e) ?: "")).append("</td></tr>")
            }
        }
        append("</tbody></table></div>")
        lanes.filter { it.events.size > MAX_LOOKUPS_PER_SESSION }.forEach {
            append("<p class=\"cov-line warn\">").append(esc(it.title)).append(": showing the first ").append(MAX_LOOKUPS_PER_SESSION).append(" of ")
                .append(it.events.size).append(" lookups.</p>")
        }
        append("</div></section>\n")
    }

    private fun StringBuilder.methodSection() {
        sectionHead("method", "05", "Method and limitations", "What this evidence can and cannot show.")
        append("<div class=\"plate\"><ul class=\"method\">")
        listOf(
            "Network evidence is the DNS lookups made by the audited app, captured by a local per-app VPN on the phone. A lookup shows the app tried to contact a server; payloads are not decrypted and no TLS certificate is installed.",
            "Observed findings come from captured traffic or the screen's accessibility tree. Inferred findings come from SDK documentation or the on-device AI's reading of the screen, and count half in the score.",
            "Hosts are labelled with a bundled tracker catalogue (domain suffix match, plus recorded CNAME aliases). Hosts the catalogue does not cover are shown as unclassified, not as harmless.",
            "Private DNS, encrypted DNS, cached lookups and shared Google Play services traffic can hide lookups from this capture. Unknown values stay unknown, never zero.",
            "Some processing may rely on a legitimate use (DPDP s.7) rather than consent; review findings in that light.",
            "The timelines and the constellation are drawn only from this audit's recorded lookups and consent events. A missing finding is not proof of compliance or safety.",
        ).forEach { append("<li>").append(esc(it)).append("</li>") }
        append("</ul><div class=\"chips-key\"><span class=\"chip ev-observed\">Observed</span> seen on this device <span class=\"chip ev-inferred\">Inferred</span> derived from documentation or AI ")
        append("<span class=\"chip ev-unknown\">Not established</span> this evidence cannot show it</div></div></section>\n")
    }

    // ---------------------------------------------------------------- graph data

    private class HostAgg(val host: String) {
        var count = 0
        var entity: String? = null
        var category: Category = Category.UNKNOWN
        var label: String = "Unknown"
        var viaAlias = false
        val phases = LinkedHashMap<Phase, Int>()
        val aliases = LinkedHashSet<String>()
        val plans = LinkedHashSet<String>()
    }

    private fun graphJson(lanes: List<Lane>, catalog: TrackerCatalog, first: AuditSession?): JsonObject {
        val hosts = LinkedHashMap<String, HostAgg>()
        for (lane in lanes) for (e in lane.events) {
            val a = hosts.getOrPut(normalise(e.hostname)) { HostAgg(normalise(e.hostname)) }
            a.count++
            if (a.entity == null && e.entity != null) { a.entity = e.entity; a.category = e.category; a.label = categoryLabel(e) }
            if (a.entity == null) { a.category = e.category; a.label = categoryLabel(e) }
            if (e.disguisedVia != null) a.viaAlias = true
            a.phases[e.phase] = (a.phases[e.phase] ?: 0) + 1
            aliasTrail(e)?.let { if (a.aliases.size < 4) a.aliases += it }
            a.plans += lane.session.plan.label
        }
        val entityIds = LinkedHashMap<String, String>()
        hosts.values.mapNotNull { it.entity }.distinct().forEachIndexed { i, ent -> entityIds[ent] = "e$i" }
        val hostIds = hosts.keys.withIndex().associate { (i, h) -> h to "h$i" }
        return buildJsonObject {
            putJsonObject("app") {
                put("label", first?.targetLabel ?: "Unknown app")
                put("pkg", first?.targetPackage ?: "?")
                put("lookups", lanes.sumOf { it.events.size })
            }
            putJsonArray("nodes") {
                for (a in hosts.values) add(buildJsonObject {
                    put("id", hostIds.getValue(a.host)); put("k", "h"); put("label", a.host); put("n", a.count)
                    a.entity?.let { put("entity", it) }
                    put("cat", a.label); put("trk", a.category.isTracking)
                    if (a.entity == null && a.category == Category.FIRST_PARTY) put("fp", true)
                    putJsonObject("ph") { a.phases.forEach { (p, n) -> put(p.name, n) } }
                    if (a.aliases.isNotEmpty()) putJsonArray("alias") { a.aliases.forEach { add(it) } }
                    putJsonArray("plans") { a.plans.forEach { add(it) } }
                })
                for ((ent, id) in entityIds) {
                    val members = hosts.values.filter { it.entity == ent }
                    val entry = catalog.entryFor(ent)
                    add(buildJsonObject {
                        put("id", id); put("k", "e"); put("label", ent); put("n", members.sumOf { it.count })
                        put("cat", members.first().label); put("trk", members.first().category.isTracking)
                        entry?.hqCountry?.let { put("hq", it) }
                        entry?.hqSource?.takeIf { isWebUrl(it) }?.let { put("hqSrc", it) }
                    })
                }
            }
            putJsonArray("edges") {
                for (a in hosts.values) {
                    for ((p, n) in a.phases) add(buildJsonObject { put("s", "app"); put("t", hostIds.getValue(a.host)); put("ph", p.name); put("n", n) })
                    a.entity?.let { ent -> add(buildJsonObject { put("s", hostIds.getValue(a.host)); put("t", entityIds.getValue(ent)); put("alias", a.viaAlias) }) }
                }
            }
        }
    }

    // ---------------------------------------------------------------- evidence helpers

    /** Same labelling as the Results screen: phase from the recorded choice, catalogue class unless one was recorded. */
    private fun timeline(s: AuditSession, catalog: TrackerCatalog): List<NetEvent> {
        // Same-company context exactly as the rules engine applies it (the audited app's own publisher is first party).
        val context = com.dpdpxray.core.rules.SessionClassifier(catalog)
        val owner = context.ownerOf(s)
        return PhaseAssigner.assign(s.netEvents, s.consentEvents).map { e ->
            val info = catalog.classify(e.hostname, e.cnameChain)
            if (e.entity != null) e.copy(disguisedVia = e.disguisedVia ?: info.disguisedVia)
            else e.copy(entity = info.entity, category = info.category, disguisedVia = e.disguisedVia ?: info.disguisedVia)
        }.map { e ->
            if (owner != null && e.category.isTracking && context.companyOf(e) == owner.company) e.copy(category = Category.FIRST_PARTY_SAME_COMPANY) else e
        }.sortedBy { it.tNanos }
    }

    private fun aliasTrail(e: NetEvent): String? = when {
        e.cnameChain.size >= 2 -> e.cnameChain.joinToString(" → ") + if (e.disguisedVia != null) " (alias observed)" else ""
        e.disguisedVia != null -> "${e.hostname} → ${e.disguisedVia} (alias observed)"
        else -> null
    }

    private fun aliasesFor(f: Finding, lanes: List<Lane>): List<String> = lanes
        .filter { it.session.plan in f.plans }
        .flatMap { it.events }
        .filter { it.entity in f.entities && it.disguisedVia != null }
        .filter {
            when (f.checkId) {
                CheckId.C1, CheckId.C10 -> it.phase == Phase.BEFORE_CONSENT || it.phase == Phase.NO_CONSENT_SEEN
                CheckId.C2 -> it.phase == Phase.AFTER_REJECT
                else -> true
            }
        }
        .mapNotNull { aliasTrail(it) }.distinct()

    private fun phaseLabel(p: Phase) = when (p) {
        Phase.BEFORE_CONSENT -> "Before consent"
        Phase.AFTER_REJECT -> "After Reject"
        Phase.AFTER_ACCEPT -> "After Accept"
        Phase.NO_CONSENT_SEEN -> "No consent screen seen"
    }

    private fun phaseClass(p: Phase) = when (p) {
        Phase.BEFORE_CONSENT -> "ph-before"
        Phase.AFTER_REJECT -> "ph-reject"
        Phase.AFTER_ACCEPT -> "ph-accept"
        Phase.NO_CONSENT_SEEN -> "ph-none"
    }

    private fun categoryLabel(e: NetEvent): String = when {
        e.entity == null && e.category == Category.UNKNOWN -> "Unclassified"
        else -> when (e.category) {
            Category.ANALYTICS -> "Analytics"
            Category.ADVERTISING -> "Advertising"
            Category.ATTRIBUTION -> "Attribution"
            Category.SOCIAL -> "Social"
            Category.CRASH_REPORTING -> "Crash reporting"
            Category.ENGAGEMENT -> "Engagement"
            Category.FIRST_PARTY -> "First party (marked)"
            Category.FIRST_PARTY_SAME_COMPANY -> "Same company as the app (first party)"
            Category.CONTENT_DELIVERY -> "Content delivery (CDN)"
            Category.COMPANY_SERVICE -> "Company service (catalogue match, not a tracking endpoint)"
            Category.UNKNOWN -> "Unknown category"
        }
    }

    private fun consentLabel(k: ConsentKind) = when (k) {
        ConsentKind.SCREEN_APPEARED -> "Consent screen detected"
        ConsentKind.ACCEPT -> "Accept recorded"
        ConsentKind.REJECT -> "Reject recorded"
        ConsentKind.SETTINGS -> "Settings or Manage opened"
        ConsentKind.MANUAL_MARK -> "Consent moment marked by hand"
        ConsentKind.PERMISSION_DIALOG -> "Android permission dialog shown"
    }

    private fun bandClass(b: ScoreBand) = when (b) {
        ScoreBand.READY -> "band-ready"
        ScoreBand.NEEDS_WORK -> "band-work"
        ScoreBand.AT_RISK -> "band-risk"
        ScoreBand.INCONCLUSIVE -> "band-unknown"
    }

    /** Places marks on discrete levels (0, -1, 1, -2, 2, ...) so lookups that share a moment stay visible. Input sorted by x. */
    internal fun stack(xs: List<Double>, gap: Double, maxLevel: Int): List<Int> {
        val order = (0..maxLevel).flatMap { if (it == 0) listOf(0) else listOf(-it, it) }
        val last = HashMap<Int, Double>()
        return xs.map { x ->
            val lvl = order.firstOrNull { (last[it] ?: Double.NEGATIVE_INFINITY) <= x - gap } ?: order.minBy { last[it] ?: Double.NEGATIVE_INFINITY }
            last[lvl] = x
            lvl
        }
    }

    private fun niceStep(span: Double): Double {
        val steps = listOf(0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 20.0, 30.0, 60.0, 120.0, 300.0, 600.0)
        return steps.firstOrNull { span / it <= 10 } ?: (span / 10)
    }

    // ---------------------------------------------------------------- text helpers

    private fun normalise(host: String) = host.lowercase(Locale.ROOT).trimEnd('.')

    /** HTML-escapes text for element content and quoted attributes; control characters become U+FFFD. */
    internal fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when {
                c == '&' -> sb.append("&amp;")
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                c == '"' -> sb.append("&quot;")
                c == '\'' -> sb.append("&#39;")
                c < ' ' && c != '\n' && c != '\t' -> sb.append('\uFFFD')
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** JSON inside a script element: no character can close the element or start markup. */
    internal fun safeJson(json: JsonObject): String = json.toString()
        .replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
        .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    private fun isWebUrl(u: String) = u.startsWith("https://") || u.startsWith("http://")

    /** Cited sources become plain anchors (navigation only when clicked); anything else stays text. */
    private fun link(url: String): String =
        if (isWebUrl(url) && url.none { it.isWhitespace() }) "<a href=\"${esc(url)}\" target=\"_blank\" rel=\"noopener noreferrer\">${esc(url)}</a>" else esc(url)

    private fun date(epochMs: Long, zone: ZoneId): String =
        if (epochMs <= 0) "Not recorded" else DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm z", Locale.US).withZone(zone).format(Instant.ofEpochMilli(epochMs))

    private fun secs(nanos: Long) = String.format(Locale.US, "%.1f", nanos / 1e9)
    private fun f1(d: Double) = String.format(Locale.US, "%.1f", d)
    private fun trimNum(d: Double) = if (abs(d - Math.rint(d)) < 1e-9) Math.rint(d).toLong().toString() else String.format(Locale.US, "%.1f", d)

    private fun resource(path: String): String = HtmlReport::class.java.getResourceAsStream(path)
        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        ?: error("$path missing from resources")
}
