package com.dpdpxray.app.ui.screens

import com.dpdpxray.app.ai.ResultSource
import com.dpdpxray.app.ai.ResultSources
import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.data.AuditBundle
import com.dpdpxray.app.audit.AuditPlanCoverage
import com.dpdpxray.app.audit.AuditTimingPolicy
import com.dpdpxray.app.data.AuditNetworkReadings
import com.dpdpxray.app.data.AuditComparisonResult
import com.dpdpxray.app.netproof.NetworkProofCard
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ui.EndpointMapIntegration
import com.dpdpxray.app.ui.screens.map.EndpointMapCard
import com.dpdpxray.app.ui.components.Callout
import com.dpdpxray.app.ui.components.ChipFlow
import com.dpdpxray.app.ui.components.CodeBlock
import com.dpdpxray.app.ui.components.CollapsibleCard
import com.dpdpxray.app.ui.components.GroupHeader
import com.dpdpxray.app.ui.components.LearnMore
import com.dpdpxray.app.ui.components.SectionCard
import com.dpdpxray.app.ui.components.ConsentLine
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.PrimaryAction
import com.dpdpxray.app.ui.components.RuleRow
import com.dpdpxray.app.ui.components.SecondaryAction
import com.dpdpxray.app.ui.components.SectionTitle
import com.dpdpxray.app.ui.components.Tag
import com.dpdpxray.app.ui.components.TopBar
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.GlyphIcon
import com.dpdpxray.app.ui.guide.GuideBanner
import com.dpdpxray.app.ui.guide.NextAction
import com.dpdpxray.app.ui.guide.Panel
import com.dpdpxray.app.ui.guide.ResultFacts
import com.dpdpxray.app.ui.guide.ResultGuide
import com.dpdpxray.app.ui.guide.guideLang
import com.dpdpxray.app.ui.theme.Brand
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.FilmExtra
import com.dpdpxray.app.ui.theme.bandColor
import com.dpdpxray.app.ui.theme.severityColor
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.Finding
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.Severity
import com.dpdpxray.core.score.ConfidenceGrade
import com.dpdpxray.core.score.ConfidenceGrader
import com.dpdpxray.core.score.ConfidenceLevel
import java.io.File

@Composable
private fun WithBundle(vm: XrayViewModel, id: String, content: @Composable (AuditBundle) -> Unit) {
    LaunchedEffect(id) { vm.open(id) }
    val bundle by vm.bundle.collectAsState()
    val b = bundle
    if (b == null || b.meta.id != id) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), color = Film.scan, strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Note("Opening audit…")
        }
    } else content(b)
}

@Composable
fun ResultsScreen(
    vm: XrayViewModel, id: String, onBack: () -> Unit, onFinding: (CheckId) -> Unit, onFixes: () -> Unit, onReport: () -> Unit, onAsk: () -> Unit,
    /** Starts a new check from the app picker; when null, "Check again" falls back to going back. */
    onCheckAgain: (() -> Unit)? = null,
) {
    WithBundle(vm, id) { b ->
        val r = b.result
        Column(Modifier.fillMaxSize()) {
            TopBar(b.meta.targetLabel, onBack)
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                val grade = remember(b.meta.id) { if (b.meta.sample) null else ConfidenceGrader.gradeAudit(b.sessions) }
                ScorePanel(b, grade)
                Text(headline(r.findings), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp))
                // Coverage caveats stay in view, above anything the reader might act on.
                val gaps = AuditPlanCoverage.gaps(b.sessions)
                val freshHint = AuditPlanCoverage.freshStateHint(b.sessions)
                if (gaps.isNotEmpty() || freshHint != null) {
                    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        gaps.forEach { Callout(it) }
                        freshHint?.let { Callout(it) }
                    }
                }

                val fixCount = vm.fixesFor(b).size
                val facts = ResultFacts(
                    band = r.band, score = r.score, findingCount = r.findings.size, fixCount = fixCount,
                    coverageGaps = gaps.isNotEmpty(), rejectPlanned = b.sessions.any { it.plan == AuditPlan.REJECT },
                    fastPreview = b.meta.timingProfile == AuditTimingPolicy.FAST_PREVIEW, sample = b.meta.sample,
                )
                GuideBanner(ResultGuide.step(facts), Modifier.padding(top = 18.dp))
                ResultGuidePanel(facts) { action ->
                    when (action) {
                        NextAction.SEE_EVIDENCE -> r.findings.firstOrNull()?.let { onFinding(it.checkId) }
                        NextAction.HOW_TO_FIX -> onFixes()
                        NextAction.CHECK_AGAIN, NextAction.CHECK_REAL_APP -> onCheckAgain?.invoke() ?: onBack()
                        NextAction.ASK -> onAsk()
                        NextAction.REPORT -> onReport()
                    }
                }

                GroupHeader("Findings") {
                    if (r.findings.isNotEmpty()) Text("${r.findings.size}", style = MaterialTheme.typography.labelLarge, color = Film.boneDim)
                }
                if (r.findings.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlyphIcon(Glyph.CHECK, Film.clear, size = 20.dp)
                        Spacer(Modifier.width(10.dp))
                        Note("No consent problems in the captured evidence.", Film.clear)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    r.findings.forEach { f -> FindingRow(f, ResultSources.of(f, b.sessions)) { onFinding(f.checkId) } }
                }

                GroupHeader("Evidence and limits")
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CollapsibleCard(
                        "Evidence coverage",
                        summary = if (b.meta.sample) "Synthetic sample: capture confidence was not measured." else grade?.summary,
                        summaryColor = if (b.meta.sample) Film.warn else Film.boneDim,
                        glyph = Glyph.WATCHER,
                    ) {
                        if (b.meta.sample) Note("Synthetic sample: capture confidence was not measured.", Film.warn)
                        else grade?.let { Note(it.summary) }
                        NetworkProofCard(AuditNetworkReadings.proof(b.sessions, b.networkReadings))
                    }

                    CollapsibleCard(
                        "Endpoint evidence",
                        summary = if (b.meta.sample) "Synthetic sample: example IPs do not establish real locations." else "Where lookups were answered, and who runs those services",
                        summaryColor = if (b.meta.sample) Film.warn else Film.boneDim,
                        glyph = Glyph.PRIVATE_DNS,
                    ) {
                        if (b.meta.sample) Note("Synthetic sample: example IPs do not establish real locations.", Film.warn)
                        val mapSettings by vm.settings.collectAsState()
                        val mapCatalog = remember(vm, mapSettings.firstPartyDomains) { vm.graph.catalog() }
                        val mapOwners = remember(mapCatalog) { EndpointMapIntegration.owners(mapCatalog) }
                        EndpointMapCard(b.sessions, mapCatalog, owners = mapOwners)
                    }

                    val comparison by produceState<AuditComparisonResult?>(null, b.meta.id) { value = vm.previousComparison(b) }
                    val change = comparison
                    CollapsibleCard(
                        "Before / after",
                        summary = when (change) {
                            null -> "Checking earlier saved audits…"
                            is AuditComparisonResult.Unavailable -> change.reason
                            is AuditComparisonResult.Available -> "${if (change.scoreDelta > 0) "+${change.scoreDelta}" else change.scoreDelta.toString()} points since the previous audit"
                        },
                        glyph = Glyph.RESET,
                    ) {
                        when (change) {
                            null -> Note("Checking earlier saved audits…")
                            is AuditComparisonResult.Unavailable -> Note(change.reason)
                            is AuditComparisonResult.Available -> {
                                val delta = if (change.scoreDelta > 0) "+${change.scoreDelta}" else change.scoreDelta.toString()
                                Text("$delta points since the previous audit", style = MaterialTheme.typography.titleMedium)
                                Note("Same package and audit plans. Previous audit: ${java.time.Instant.ofEpochMilli(change.previousTimeMs).atZone(java.time.ZoneId.systemDefault())}.")
                                Note("${change.noLongerRecorded.size} findings no longer recorded" +
                                    change.noLongerRecorded.takeIf { it.isNotEmpty() }?.joinToString(prefix = ": ") { it.code }.orEmpty())
                                if (change.newlyRecorded.isNotEmpty()) Note("New findings: ${change.newlyRecorded.joinToString { it.code }}", Film.warn)
                                Note("Capture coverage can differ. A finding not repeated is not proof it was fixed or that the app is safe.")
                            }
                        }
                    }

                    CollapsibleCard(
                        "Timeline",
                        summary = "Each run's lookups, with the moment of the consent choice marked",
                        glyph = Glyph.CLOCK,
                        initiallyOpen = true,
                    ) {
                        PlanTimelines(vm, b)
                    }

                    val notes = b.sessions.flatMap { s -> s.notes.map { "${s.plan.label}: $it" } }.distinct()
                    if (notes.isNotEmpty()) {
                        CollapsibleCard("Audit notes", summary = if (notes.size == 1) "1 note from the check" else "${notes.size} notes from the check", glyph = Glyph.SAMPLE) {
                            notes.forEach { Note("• $it") }
                        }
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

private fun headline(findings: List<Finding>): String {
    val c1 = findings.firstOrNull { it.checkId == CheckId.C1 }
    val c2 = findings.firstOrNull { it.checkId == CheckId.C2 }
    val v0 = findings.any { it.checkId == CheckId.V0 }
    return when {
        v0 -> "Too few lookups were captured to judge this app. See the first finding for how to re-run."
        c2 != null -> "Tracking continued after the user refused consent: ${c2.entities.joinToString()}."
        c1 != null -> "${c1.entities.size} tracker${if (c1.entities.size == 1) "" else "s"} contacted before any consent choice: ${c1.entities.joinToString()}."
        findings.isEmpty() -> "No trackers before consent and no consent-screen problems found."
        findings.all { it.severity == Severity.INFO } && findings.any { it.checkId == CheckId.U1 } ->
            "Consent history unknown: this app kept its data, so consent given earlier could not be checked."
        findings.all { it.severity == Severity.INFO } -> "No third-party trackers before consent and no consent-screen problems found. See the information notes."
        else -> "No tracking before consent, but the consent screen needs work."
    }
}

/**
 * The score hero: big number in its band colour, a meter, chips for sample data, evidence confidence and capture
 * timing, then the severity summary. Inconclusive shows "?" and a dashed track, never a zero.
 */
@Composable
private fun ScorePanel(b: AuditBundle, grade: ConfidenceGrade?) {
    val r = b.result
    val color = bandColor(r.band)
    val inconclusive = r.band == ScoreBand.INCONCLUSIVE
    Panel(Modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(if (inconclusive) "?" else r.score.toString(), style = MaterialTheme.typography.displayLarge, color = color)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.padding(bottom = 8.dp).weight(1f)) {
                Text(r.band.label, style = MaterialTheme.typography.titleLarge, color = color)
                Text("DPDP readiness out of 100", style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
            }
        }
        var shown by remember(b.meta.id) { mutableStateOf(false) }
        LaunchedEffect(b.meta.id) { shown = true }
        val fill by animateFloatAsState(if (shown && !inconclusive) (r.score ?: 0) / 100f else 0f, tween(700), label = "score-meter")
        Canvas(Modifier.fillMaxWidth().padding(top = 10.dp).height(6.dp)) {
            val h = size.height
            if (inconclusive) {
                drawLine(Film.line, Offset(0f, h / 2), Offset(size.width, h / 2), h, StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx())))
            } else {
                drawLine(Film.line, Offset(h / 2, h / 2), Offset(size.width - h / 2, h / 2), h, StrokeCap.Round)
                if (fill > 0f) drawLine(color, Offset(h / 2, h / 2), Offset(h / 2 + (size.width - h) * fill, h / 2), h, StrokeCap.Round)
            }
        }
        val preview = b.meta.timingProfile == AuditTimingPolicy.FAST_PREVIEW
        ChipFlow(Modifier.padding(top = 14.dp)) {
            if (b.meta.sample) Tag("Sample data", Film.warn)
            if (grade != null) Tag("Evidence confidence: ${grade.level.label}", confidenceColor(grade.level))
            else if (b.meta.sample) Tag("Confidence not measured", Film.boneDim)
            if (!b.meta.sample) {
                Tag(
                    if (preview) "Fast Preview · limited evidence" else if (b.meta.timingProfile == AuditTimingPolicy.NORMAL) "Normal capture" else "Timing unknown",
                    if (preview) Film.warn else Film.boneDim,
                )
            }
        }
        if (!b.meta.sample) {
            if (b.meta.timingProfile == AuditTimingPolicy.NORMAL) {
                LearnMore(label = "About this capture") { Note(AuditTimingPolicy.coverageHint(b.meta.timingProfile)) }
            } else {
                Spacer(Modifier.height(10.dp))
                Note(AuditTimingPolicy.coverageHint(b.meta.timingProfile), if (preview) Film.warn else Film.boneDim)
            }
        }
        SeverityStrip(r.findings)
    }
}

private fun confidenceColor(level: ConfidenceLevel) = when (level) {
    ConfidenceLevel.HIGH -> Film.clear
    ConfidenceLevel.MEDIUM -> Film.warn
    ConfidenceLevel.LOW -> Film.high
}

private fun nextGlyph(a: NextAction) = when (a) {
    NextAction.SEE_EVIDENCE -> Glyph.WATCHER
    NextAction.HOW_TO_FIX -> Glyph.BOOK
    NextAction.CHECK_AGAIN -> Glyph.RESET
    NextAction.CHECK_REAL_APP -> Glyph.PLAY
    NextAction.ASK -> Glyph.ASK
    NextAction.REPORT -> Glyph.SAMPLE
}

/** "What this means" in plain language, then "What you can do next" as tappable rows for the existing actions. */
@Composable
private fun ResultGuidePanel(facts: ResultFacts, onAction: (NextAction) -> Unit) {
    val lang = guideLang()
    val e = remember(facts) { ResultGuide.explain(facts) }
    Panel(Modifier.padding(top = 12.dp)) {
        Text(ResultGuide.meaningTitle.pick(lang), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        e.meaning.forEach { line ->
            Row(Modifier.padding(top = 8.dp)) {
                Box(Modifier.padding(top = 9.dp).size(6.dp).clip(CircleShape).background(Film.scan))
                Spacer(Modifier.width(10.dp))
                Text(line.pick(lang), style = MaterialTheme.typography.bodyLarge, color = Film.bone)
            }
        }
    }
    Text(
        ResultGuide.nextTitle.pick(lang), style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp).semantics { heading() },
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        e.next.forEachIndexed { i, step ->
            val shape = RoundedCornerShape(16.dp)
            val first = i == 0
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(shape).background(Film.raised)
                    .border(if (first) 1.5.dp else 1.dp, if (first) Brand.horizontal else SolidColor(FilmExtra.hairline), shape)
                    .clickable(role = Role.Button) { onAction(step.action) }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val accent = if (first) Brand.coral else Film.scan
                Box(Modifier.size(36.dp).clip(CircleShape).background(accent.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                    GlyphIcon(nextGlyph(step.action), accent, size = 20.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(step.title.pick(lang), style = MaterialTheme.typography.titleMedium, color = Film.bone)
                    Text(step.reason.pick(lang), style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
                }
            }
        }
    }
}

/** Severity summary inside the score card: a proportional strip, then one chip per severity present. */
@Composable
private fun SeverityStrip(findings: List<Finding>) {
    val counts = Severity.entries.associateWith { s -> findings.count { it.severity == s } }.filterValues { it > 0 }
    if (counts.isEmpty()) return
    Spacer(Modifier.height(14.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(FilmExtra.hairline))
    Text("Findings by severity", style = MaterialTheme.typography.labelMedium, color = Film.boneDim, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
    Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))) {
        counts.forEach { (s, n) -> Box(Modifier.weight(n.toFloat()).height(8.dp).background(severityColor(s))) }
    }
    ChipFlow(Modifier.padding(top = 10.dp)) {
        counts.forEach { (s, n) -> Tag("$n ${s.label.lowercase()}", severityColor(s)) }
    }
}

/** A finding as a card: severity and evidence chips, the title, who was contacted. Tapping opens the evidence. */
@Composable
private fun FindingRow(f: Finding, source: ResultSource, onClick: () -> Unit) {
    RuleRow(severityColor(f.severity), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChipFlow(Modifier.weight(1f)) {
                Tag(f.severity.label, severityColor(f.severity))
                Tag(source.label, sourceColor(source))
            }
            GlyphIcon(Glyph.CHEVRON_RIGHT, Film.boneDim, size = 18.dp)
        }
        Text(f.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
        if (f.entities.isNotEmpty()) Text(f.entities.joinToString(), style = MaterialTheme.typography.bodyMedium, color = severityColor(f.severity), modifier = Modifier.padding(top = 2.dp))
        Text(
            (if (f.evidenceType == EvidenceType.OBSERVED) "Observed on device" else "Inferred") +
                (if (f.penaltyBand.name != "NONE") " · ${f.penaltyBand.label.substringBefore(" (")}" else ""),
            style = MaterialTheme.typography.labelMedium, color = Film.boneDim, modifier = Modifier.padding(top = 4.dp),
        )
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlanTimelines(vm: XrayViewModel, b: AuditBundle) {
    var planIdx by remember(b.meta.id) { mutableStateOf(0) }
    var markHost by remember { mutableStateOf<String?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        b.sessions.forEachIndexed { i, s ->
            FilterChip(
                selected = i == planIdx, onClick = { planIdx = i }, label = { Text(s.plan.label.substringAfter("· ")) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Film.scan, selectedLabelColor = Film.base),
            )
        }
    }
    val s = b.sessions.getOrNull(planIdx) ?: return
    val events = vm.timeline(s)
    val choice = s.consentChoice
    Note("Long-press a host to mark it as your own domain.")
    Column(Modifier.padding(top = 6.dp)) {
        var lineDrawn = false
        events.forEach { e ->
            if (!lineDrawn && choice != null && e.tNanos >= choice.tNanos) {
                lineDrawn = true
                ConsentLine(choiceLabel(choice.kind, secs(choice.tNanos - s.startedAtNanos)), if (choice.kind == ConsentKind.ACCEPT) Film.scan else Film.warn)
            }
            Box(Modifier.combinedClickable(onClick = {}, onLongClick = { markHost = e.hostname })) {
                TimelineRow(e, s.startedAtNanos, beforeConsent = choice == null || e.tNanos < choice.tNanos, afterRefusal = choice?.kind == ConsentKind.REJECT)
            }
        }
        if (!lineDrawn && choice != null) ConsentLine(choiceLabel(choice.kind, secs(choice.tNanos - s.startedAtNanos)), Film.scan)
        if (choice == null) Note(if (s.consentScreenSeen) "The user never chose; every lookup above happened before consent." else "No consent screen was shown in this run.")
    }
    markHost?.let { host ->
        AlertDialog(
            onDismissRequest = { markHost = null },
            title = { Text("Mark as your own domain?") },
            text = { Text("$host will be treated as first-party in future results. An observed alias to a known tracker can still count as tracking.") },
            confirmButton = { TextButton(onClick = { vm.markFirstParty(host); markHost = null }) { Text("Mark as mine") } },
            dismissButton = { TextButton(onClick = { markHost = null }) { Text("Cancel") } },
            containerColor = Film.raised,
        )
    }
}

private fun choiceLabel(kind: ConsentKind, at: String) = if (kind == ConsentKind.ACCEPT) "User accepted at $at s" else "User refused at $at s"

@Composable
fun FindingDetailScreen(vm: XrayViewModel, id: String, check: String, onBack: () -> Unit, onFixes: () -> Unit) {
    WithBundle(vm, id) { b ->
        val f = b.result.findings.firstOrNull { it.checkId.name == check } ?: return@WithBundle
        val explanations by vm.explanations.collectAsState()
        val explaining by vm.explaining.collectAsState()
        Column(Modifier.fillMaxSize()) {
            TopBar(f.checkId.code, onBack)
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                Text(f.title, style = MaterialTheme.typography.headlineSmall, color = severityColor(f.severity), modifier = Modifier.semantics { heading() })
                ChipFlow(Modifier.padding(vertical = 12.dp)) {
                    Tag(f.severity.label, severityColor(f.severity))
                    Tag(ResultSources.of(f, b.sessions).label, sourceColor(ResultSources.of(f, b.sessions)))
                    if (f.penaltyBand.name != "NONE") Tag(f.penaltyBand.label.substringBefore(" ("), Film.boneDim)
                }
                Text(f.explanation, style = MaterialTheme.typography.bodyLarge)

                val aliases = remember(b, f) { vm.findingAliases(f, b) }
                if (aliases.isNotEmpty()) {
                    SectionTitle("Observed tracker aliases")
                    aliases.forEach { Tag(it, Film.warn); Spacer(Modifier.height(6.dp)) }
                    Note("The DNS reply revealed an alias to a catalogue tracker. This does not show what data was sent.")
                }

                val notice = remember(b, f) { vm.rightsNotice(b, f.checkId) }
                var showNotice by remember(b.meta.id, f.checkId) { mutableStateOf(false) }
                if (notice != null) {
                    SectionTitle("Ask the developer to investigate")
                    SecondaryAction("Draft notice to developer", { showNotice = true }, Modifier.fillMaxWidth())
                    Note("Review before sharing. The cited rights relate to India's DPDP framework; this is not a legal finding.")
                    if (showNotice) AlertDialog(
                        onDismissRequest = { showNotice = false },
                        title = { Text("Review developer notice") },
                        text = {
                            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                                if (b.meta.sample) Note("SAMPLE: demonstration draft, not evidence from your device.", Film.warn)
                                Note("Nothing is sent automatically. Check the observations and replace the name/contact placeholders.")
                                Text(notice.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                            }
                        },
                        confirmButton = {
                            Row {
                                TextButton(onClick = { vm.copy("Developer notice", notice.text) }) { Text("Copy") }
                                TextButton(onClick = { vm.shareNotice(notice) }) { Text("Share") }
                            }
                        },
                        dismissButton = { TextButton(onClick = { showNotice = false }) { Text("Close") } },
                        containerColor = Film.raised,
                    )
                }

                screenshotFor(b, f)?.let { path ->
                    SectionTitle("Consent screen as captured")
                    val bmp = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
                    if (bmp != null) Image(bmp, contentDescription = "Captured consent screen", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp))
                }

                if (f.evidence.isNotEmpty()) {
                    SectionTitle("Evidence")
                    f.evidence.forEach { CodeBlock(it, Modifier.padding(bottom = 6.dp)) }
                }

                SectionTitle("What the law says")
                val cites by produceState(initialValue = emptyList<com.dpdpxray.core.dpdp.Passage>(), f) { value = vm.citationsFor(f) }
                if (cites.isEmpty()) Note("Finding the relevant sections…")
                cites.forEach { p ->
                    RuleRow(Film.scan) {
                        Text(p.text, style = MaterialTheme.typography.bodyMedium)
                        Text(p.source + if (p.verbatim) "" else " (summary)", style = MaterialTheme.typography.labelMedium, color = Film.scan, modifier = Modifier.padding(top = 6.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                }

                SectionTitle("Explain with on-device AI")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryAction("In English", { vm.explain(f, hindi = false) }, Modifier.weight(1f), enabled = explaining == null)
                    SecondaryAction("हिन्दी में", { vm.explain(f, hindi = true) }, Modifier.weight(1f), enabled = explaining == null)
                }
                listOf("en", "hi").forEach { lang ->
                    val key = "${f.checkId}-$lang"
                    if (explaining == key) Note("Gemma is writing on this phone…", Film.scan)
                    explanations[key]?.let { e ->
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Tag(e.source.label, sourceColor(e.source))
                            Text(if (lang == "hi") "हिन्दी" else "English", style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
                        }
                        Text(e.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
                        Text(e.detail, style = MaterialTheme.typography.bodySmall, color = Film.boneDim, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
                PrimaryAction("See how to fix", onFixes)
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

/** Observed, AI-inferred, documentation-inferred and fallback text each keep one colour everywhere. */
private fun sourceColor(s: ResultSource) = when (s) {
    ResultSource.OBSERVED -> Film.scan
    ResultSource.AI_INFERRED -> Brand.rose
    ResultSource.INFERRED -> Film.warn
    ResultSource.FALLBACK -> Film.boneDim
}

private fun screenshotFor(b: AuditBundle, f: Finding): String? {
    val screenChecks = setOf(CheckId.C3, CheckId.C4, CheckId.C5, CheckId.C6, CheckId.C7, CheckId.C8, CheckId.C9)
    if (f.checkId !in screenChecks) return null
    return b.sessions.flatMap { it.consentEvents }.mapNotNull { it.screenshotPath }.firstOrNull { File(it).exists() }
}

@Composable
fun FixesScreen(vm: XrayViewModel, id: String, onBack: () -> Unit) {
    WithBundle(vm, id) { b ->
        val fixes = vm.fixesFor(b)
        Column(Modifier.fillMaxSize()) {
            TopBar("How to fix", onBack)
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Note("Copy a fix and paste it into your project (with clipboard sync, straight into Android Studio on your laptop). Then rebuild and audit again.")
                fixes.forEach { fix ->
                    SectionCard(title = fix.title, glyph = Glyph.BOOK) {
                        if (!fix.verified) Callout("Check the exact API for your SDK version in the vendor docs.", Modifier.padding(bottom = 8.dp))
                        fix.manifest?.let { m ->
                            Text("AndroidManifest.xml", style = MaterialTheme.typography.labelMedium, color = Film.boneDim, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
                            CodeBlock(m)
                            TextButton(onClick = { vm.copy("manifest", m) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy manifest change", color = Film.scan) }
                        }
                        fix.code?.let { c ->
                            Text("Code", style = MaterialTheme.typography.labelMedium, color = Film.boneDim, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
                            CodeBlock(c)
                            TextButton(onClick = { vm.copy("code", c) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy code", color = Film.scan) }
                        }
                        fix.steps.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
                        fix.docUrl?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Film.scan, modifier = Modifier.padding(top = 8.dp)) }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
fun ReportScreen(vm: XrayViewModel, id: String, onBack: () -> Unit) {
    WithBundle(vm, id) { b ->
        Column(Modifier.fillMaxSize()) {
            TopBar("Report", onBack)
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SectionCard(title = "Save to this phone", glyph = Glyph.SAMPLE) {
                    PrimaryAction("Save PDF to Downloads", { vm.exportPdf(b, share = false) })
                    Spacer(Modifier.height(10.dp))
                    SecondaryAction("Save Markdown", { vm.saveMarkdown(b) }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    SecondaryAction("Save interactive report (HTML)", { vm.exportHtml(b, share = false) }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    SecondaryAction("Export audit data (JSON)", { vm.exportAuditJson(b, share = false) }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Note("Saved files land in Downloads/TraceVeil, ready to share or move to your laptop. The HTML report opens offline in any browser.")
                }
                SectionCard(title = "Share", glyph = Glyph.ASK) {
                    SecondaryAction("Share PDF", { vm.exportPdf(b, share = true) }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    SecondaryAction("Share interactive report", { vm.exportHtml(b, share = true) }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    SecondaryAction("Share audit data (JSON)", { vm.exportAuditJson(b, share = true) }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Callout("Audit data (JSON) is this audit's full evidence (lookups, consent-screen text, device model) for TraceVeil CI on a laptop or build server. Review it before sharing it or committing it to a repository.")
                }
                CollapsibleCard("Report preview", summary = "The Markdown report, limits included", glyph = Glyph.REPORTS) {
                    Text(vm.markdown(b), style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
