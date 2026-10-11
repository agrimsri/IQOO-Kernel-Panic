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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.data.AuditBundle
import com.dpdpxray.app.data.Audience
import com.dpdpxray.app.audit.AuditPlanCoverage
import com.dpdpxray.app.audit.AuditTimingPolicy
import com.dpdpxray.app.data.AuditNetworkReadings
import com.dpdpxray.app.data.AuditComparisonResult
import com.dpdpxray.app.data.UpdateDiffWording
import com.dpdpxray.app.data.LensChoice
import com.dpdpxray.app.netproof.NetworkProofCard
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ui.EndpointMapIntegration
import com.dpdpxray.app.ui.screens.map.EndpointMapCard
import com.dpdpxray.app.ui.components.BreathingDot
import com.dpdpxray.app.ui.components.Callout
import com.dpdpxray.app.ui.components.EmptyHint
import com.dpdpxray.app.ui.components.FoldText
import com.dpdpxray.app.ui.components.GradientRule
import com.dpdpxray.app.ui.components.IconBadge
import com.dpdpxray.app.ui.components.KeyCaveat
import com.dpdpxray.app.ui.components.ScoreRing
import com.dpdpxray.app.ui.components.SkeletonCard
import com.dpdpxray.app.ui.components.SkeletonLines
import com.dpdpxray.app.ui.components.pressClickable
import com.dpdpxray.app.ui.components.reveal
import com.dpdpxray.app.ui.components.shimmer
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
import com.dpdpxray.app.ui.guide.Bi
import com.dpdpxray.app.ui.guide.NextStep
import com.dpdpxray.app.ui.guide.SpeakStep
import com.dpdpxray.app.ui.system.Group
import com.dpdpxray.app.ui.system.GroupDivider
import com.dpdpxray.app.ui.system.ListRow
import com.dpdpxray.app.ui.system.Notice
import com.dpdpxray.app.ui.system.NoticeKind
import com.dpdpxray.app.ui.system.PrimaryButton
import com.dpdpxray.app.ui.system.StatusDot
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.GlyphIcon
import com.dpdpxray.app.ui.guide.NextAction
import com.dpdpxray.app.ui.guide.Panel
import com.dpdpxray.app.ui.guide.ResultFacts
import com.dpdpxray.app.ui.guide.ResultGuide
import com.dpdpxray.app.ui.guide.GuideLang
import com.dpdpxray.app.ui.guide.guideLang
import com.dpdpxray.app.ui.guide.lessLabel
import com.dpdpxray.app.ui.guide.moreLabel
import com.dpdpxray.app.ui.theme.Brand
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.FilmExtra
import com.dpdpxray.app.ui.theme.bandColor
import com.dpdpxray.app.ui.theme.severityColor
import com.dpdpxray.core.attribution.SdkAttribution
import com.dpdpxray.core.law.LawRelation
import com.dpdpxray.core.law.LegalLens
import com.dpdpxray.core.law.LegalProfile
import com.dpdpxray.core.law.LensPassage
import com.dpdpxray.core.law.LensProfile
import com.dpdpxray.core.law.LensText
import com.dpdpxray.core.law.LensView
import com.dpdpxray.core.law.PassageKind
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
        // A skeleton shaped like the result, instead of a blank screen, while the audit opens.
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(18.dp)).background(Film.raised).shimmer().semantics { contentDescription = "Opening audit" })
            SkeletonLines(3)
            SkeletonCard()
            SkeletonCard()
        }
    } else content(b)
}

@Composable
fun ResultsScreen(
    vm: XrayViewModel, id: String, onBack: () -> Unit, onFinding: (CheckId) -> Unit, onFixes: () -> Unit, onReport: () -> Unit, onAsk: () -> Unit,
    /** Starts a new check from the app picker; when null, "Check again" falls back to going back. */
    onCheckAgain: (() -> Unit)? = null,
    /** Shows the live check started by a one-tap "Check again with a restart" (or a confirmed reset). */
    onLive: () -> Unit = {},
    /** Opens the internet-cut-off run for this app, which lists the addresses it tries. */
    onQuarantine: (String) -> Unit = {},
) {
    WithBundle(vm, id) { b ->
        val r = b.result
        val lang = guideLang()
        var menuOpen by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize()) {
            TopBar(b.meta.targetLabel, onBack, trailing = {
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp).semantics { contentDescription = "More actions" }) {
                        Text("•••", style = MaterialTheme.typography.titleMedium, color = Film.boneDim)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = Film.raised) {
                        DropdownMenuItem(text = { Text(ResultCopy.report.pick(lang)) }, onClick = { menuOpen = false; onReport() })
                        DropdownMenuItem(text = { Text(ResultCopy.ask.pick(lang)) }, onClick = { menuOpen = false; onAsk() })
                        DropdownMenuItem(text = { Text(ResultCopy.again.pick(lang)) }, onClick = { menuOpen = false; onCheckAgain?.invoke() ?: onBack() })
                    }
                }
            })
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                val grade = remember(b.meta.id) { if (b.meta.sample) null else ConfidenceGrader.gradeAudit(b.sessions) }
                val lensSettings by vm.settings.collectAsState()
                val choice = remember(lensSettings.legalProfile) { vm.lensChoice(lensSettings.legalProfile) }
                val view = remember(b.meta.id, choice.profile) { vm.legal.view(r, choice.profile) }
                val gaps = AuditPlanCoverage.gaps(b.sessions)
                val freshHint = AuditPlanCoverage.freshStateHint(b.sessions)
                // Before / after (and Update Watch's version change) is read once and shared by the notice and the card.
                val beforeAfter by produceState<XrayViewModel.BeforeAfter?>(null, b.meta.id) { value = vm.beforeAfter(b) }
                val fixCount = vm.fixesFor(b).size
                val facts = ResultFacts(
                    band = r.band, score = r.score, findingCount = r.findings.size, fixCount = fixCount,
                    coverageGaps = gaps.isNotEmpty(), rejectPlanned = b.sessions.any { it.plan == AuditPlan.REJECT },
                    fastPreview = b.meta.timingProfile == AuditTimingPolicy.FAST_PREVIEW, sample = b.meta.sample,
                    inconclusiveReason = com.dpdpxray.core.rules.Inconclusive.reason(r.findings),
                )
                val explanation = remember(facts) { ResultGuide.explain(facts) }
                // The visible step banner is gone; people who use read-aloud still hear the same step.
                SpeakStep(ResultGuide.step(facts))

                ScoreHero(b, grade, lang, Modifier.reveal(0))
                Text(headline(r.findings), style = MaterialTheme.typography.bodyLarge, color = Film.bone, modifier = Modifier.padding(top = 20.dp).reveal(1))

                // Caveats that must stay in view, above anything the reader might act on. Only those that apply are shown.
                val preview = b.meta.timingProfile == AuditTimingPolicy.FAST_PREVIEW
                val rejectTested = b.sessions.any { it.plan == AuditPlan.REJECT }
                val standalone = r.band == ScoreBand.READY || r.band == ScoreBand.INCONCLUSIVE
                Column(Modifier.padding(top = 12.dp).reveal(1), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (b.meta.sample) Notice(ResultCopy.sample.pick(lang), kind = NoticeKind.CAUTION)
                    // Update Watch: this check is of a different version than the previous one; the full diff is in Before / after.
                    beforeAfter?.update?.let { u -> Notice(UpdateDiffWording.banner(u, beforeAfter?.comparison), kind = NoticeKind.INFO) }
                    // For a quiet or inconclusive result the first meaning line is itself a caveat (not a certificate; unknown stays unknown).
                    if (standalone) explanation.meaning.firstOrNull()?.let {
                        Notice(it.pick(lang), kind = if (r.band == ScoreBand.INCONCLUSIVE) NoticeKind.CAUTION else NoticeKind.INFO)
                    }
                    if (!b.meta.sample && b.meta.timingProfile != AuditTimingPolicy.NORMAL) {
                        Notice(AuditTimingPolicy.coverageHint(b.meta.timingProfile), kind = if (preview) NoticeKind.CAUTION else NoticeKind.INFO)
                    }
                    if (!b.meta.sample && !preview && !rejectTested) Notice(ResultCopy.rejectNotTested.pick(lang), kind = NoticeKind.CAUTION)
                    gaps.forEach { Notice(it, kind = NoticeKind.CAUTION) }
                    freshHint?.let { Notice(it, kind = NoticeKind.CAUTION) }
                }

                // Why there is no score and what one tap can try next; then which steps needed the person.
                NoScoreSection(vm, b, lang, onLive, onQuarantine, Modifier.padding(top = 20.dp))
                BlockedStepsSection(b, lang, Modifier.padding(top = 20.dp))
                val noScorePrimary = remember(b.meta.id) { NoScoreHelp.hasPrimary(b) }

                val act: (NextAction) -> Unit = { action ->
                    when (action) {
                        NextAction.SEE_EVIDENCE -> r.findings.firstOrNull()?.let { onFinding(it.checkId) }
                        NextAction.HOW_TO_FIX -> onFixes()
                        NextAction.CHECK_AGAIN, NextAction.CHECK_REAL_APP -> onCheckAgain?.invoke() ?: onBack()
                        NextAction.ASK -> onAsk()
                        NextAction.REPORT -> onReport()
                    }
                }
                ResultActions(
                    // The section above already offers checking again, as the one filled button.
                    if (noScorePrimary) explanation.next.filterNot { it.action == NextAction.CHECK_AGAIN } else explanation.next,
                    lang, act, Modifier.padding(top = 20.dp).reveal(2), primary = !noScorePrimary,
                )

                GroupHeader(ResultCopy.findings.pick(lang)) {
                    if (r.findings.isNotEmpty()) Text("${r.findings.size}", style = MaterialTheme.typography.labelLarge, color = Film.boneDim)
                }
                if (r.findings.isEmpty()) EmptyHint(ResultCopy.noProblems.pick(lang), glyph = Glyph.CHECK, color = Film.clear)
                else Group {
                    r.findings.forEachIndexed { i, f ->
                        if (i > 0) GroupDivider()
                        ListRow(
                            f.title, subtitle = findingSubtitle(f, ResultSources.of(f, b.sessions)), subtitleMaxLines = 3,
                            leading = { StatusDot(severityColor(f.severity)) },
                            onClick = { onFinding(f.checkId) },
                        )
                    }
                }
                // After the findings: "Which SDK caused this?", "Play label vs what we saw" and the dark-pattern signals
                // (India CCPA 2023), none of them part of the score. Developer mode shows each as a card; everyday mode shows
                // one plain line each, grouped, and only the lines that have something to say.
                val sdkGroups = remember(b.meta.id, lensSettings.firstPartyDomains) { vm.sdkGroups(b) }
                if (lensSettings.audience == Audience.DEVELOPER) {
                    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SdkAttributionCard(vm, b, sdkGroups)
                        PlayLabelCard(vm, b, sdkGroups)
                        DarkPatternCard(b.sessions)
                    }
                } else {
                    val sdkLine = remember(sdkGroups) { SdkAttribution.headline(sdkGroups, byCompany = true) }
                    val playLine = playLabelEverydayLine(vm, b, sdkGroups)
                    val darkLine = remember(b.meta.id) { DarkPatternView.of(b.sessions).everydayLine }
                    val lines = listOfNotNull(sdkLine, playLine, darkLine)
                    if (lines.isNotEmpty()) Group(Modifier.padding(top = 12.dp)) {
                        lines.forEachIndexed { i, line ->
                            if (i > 0) GroupDivider()
                            Text(line, style = MaterialTheme.typography.bodyMedium, color = Film.boneDim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
                        }
                    }
                }

                // The server map has its own open section, so nobody has to find it inside "Evidence and limits".
                GroupHeader("Where it connects")
                CollapsibleCard(
                    "Server map",
                    summary = if (b.meta.sample) "Synthetic sample: example IPs do not establish real locations." else "Which countries answered this app's lookups, and who runs those services",
                    summaryColor = if (b.meta.sample) Film.warn else Film.boneDim,
                    glyph = Glyph.PRIVATE_DNS,
                    initiallyOpen = !b.meta.sample,
                ) {
                    if (b.meta.sample) Note("Synthetic sample: example IPs do not establish real locations.", Film.warn)
                    val mapSettings by vm.settings.collectAsState()
                    val mapCatalog = remember(vm, mapSettings.firstPartyDomains) { vm.graph.catalog() }
                    val mapOwners = remember(mapCatalog) { EndpointMapIntegration.owners(mapCatalog) }
                    EndpointMapCard(b.sessions, mapCatalog, owners = mapOwners)
                }
                // Connections beyond DNS: this audit's own (full-relay checks) and the latest quarantine run's, if any.
                AuditConnectionsCard(b.sessions, vm, Modifier.padding(top = 12.dp))
                if (!b.meta.sample) QuarantineConnectionsCard(b.meta.targetPackage, vm, Modifier.padding(top = 12.dp))

                NetworkFindingsSection(b.sessions, vm)

                GroupHeader(ResultCopy.evidence.pick(lang))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CollapsibleCard(
                        "Evidence coverage",
                        summary = if (b.meta.sample) "Synthetic sample: capture confidence was not measured." else grade?.summary,
                        summaryColor = if (b.meta.sample) Film.warn else Film.boneDim,
                        glyph = Glyph.WATCHER,
                    ) {
                        if (b.meta.sample) Note("Synthetic sample: capture confidence was not measured.", Film.warn)
                        else grade?.let { Note(it.summary) }
                        if (!b.meta.sample && b.meta.timingProfile == AuditTimingPolicy.NORMAL) Note(AuditTimingPolicy.coverageHint(b.meta.timingProfile))
                        NetworkProofCard(AuditNetworkReadings.proof(b.sessions, b.networkReadings))
                    }


                    val change = beforeAfter?.comparison
                    val update = beforeAfter?.update
                    // Update Watch: across an app update the card opens by itself and lists what changed between the two
                    // checks. Keyed so it opens once the comparison (read from disk) arrives.
                    androidx.compose.runtime.key(update != null) {
                    CollapsibleCard(
                        "Before / after",
                        summary = when {
                            change == null -> "Checking earlier saved audits…"
                            update != null && change is AuditComparisonResult.Available -> UpdateDiffWording.summary(update, change)
                            update != null && change is AuditComparisonResult.Unavailable -> "${UpdateDiffWording.title(update)} · ${change.reason}"
                            change is AuditComparisonResult.Unavailable -> change.reason
                            change is AuditComparisonResult.Available -> "${if (change.scoreDelta > 0) "+${change.scoreDelta}" else change.scoreDelta.toString()} points since the previous audit"
                            else -> ""
                        },
                        glyph = Glyph.RESET,
                        initiallyOpen = update != null,
                    ) {
                        when {
                            change == null -> SkeletonLines(2, label = "Checking earlier saved audits")
                            update != null && change is AuditComparisonResult.Available -> UpdateDiff(update, change, b.meta.targetLabel, vm)
                            update != null && change is AuditComparisonResult.Unavailable -> {
                                Text(UpdateDiffWording.title(update), style = MaterialTheme.typography.titleMedium)
                                Note(change.reason)
                                beforeAfter?.previousTimeMs?.let { Note(UpdateDiffWording.honest(update, it)) }
                            }
                            change is AuditComparisonResult.Unavailable -> Note(change.reason)
                            change is AuditComparisonResult.Available -> {
                                val delta = if (change.scoreDelta > 0) "+${change.scoreDelta}" else change.scoreDelta.toString()
                                Text("$delta points since the previous audit", style = MaterialTheme.typography.titleMedium)
                                Note("Same package and audit plans. Previous audit: ${java.time.Instant.ofEpochMilli(change.previousTimeMs).atZone(java.time.ZoneId.systemDefault())}.")
                                Note("${change.noLongerRecorded.size} findings no longer recorded" +
                                    change.noLongerRecorded.takeIf { it.isNotEmpty() }?.joinToString(prefix = ": ") { it.code }.orEmpty())
                                if (change.newlyRecorded.isNotEmpty()) Note("New findings: ${change.newlyRecorded.joinToString { it.code }}", Film.warn)
                                KeyCaveat("Capture coverage can differ. A finding not repeated is not proof it was fixed or that the app is safe.")
                            }
                        }
                    }
                    }

                    CollapsibleCard("Legal lens · ${view.profile.name}", summary = view.scoreLine, glyph = Glyph.BOOK) {
                        Note("${choice.describe()}. Change it in Settings.")
                        Note(view.profile.laws)
                        Note(view.profile.status)
                        Note(view.scoreNote)
                        choice.hint?.let { Note(it, Film.warn) }
                    }

                    CollapsibleCard(
                        "Timeline",
                        summary = "Each run's lookups, with the moment of the consent choice marked",
                        glyph = Glyph.CLOCK,
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

                // The one-line lookup caveat stays in view; the remaining notes fold behind one link.
                val caveat = if (r.findings.isNotEmpty()) explanation.meaning.getOrNull(1) else null
                caveat?.let { KeyCaveat(it.pick(lang), Modifier.padding(top = 20.dp), color = Film.boneDim) }
                // Everything else "What this means" said, including its first line when no notice above showed it.
                val rest = explanation.meaning.filterIndexed { i, _ -> !(i == 0 && standalone) && !(i == 1 && caveat != null) }
                if (rest.isNotEmpty()) {
                    LearnMore(
                        Modifier.padding(top = 4.dp),
                        label = if (lang == GuideLang.HI) "इस नतीजे के बारे में और (${rest.size})" else "More about this result (${rest.size})",
                        openLabel = lang.lessLabel,
                    ) { rest.forEach { Text(it.pick(lang), style = MaterialTheme.typography.bodyMedium, color = Film.bone) } }
                }
                Text(ResultCopy.notLegalAdvice.pick(lang), style = MaterialTheme.typography.bodySmall, color = Film.tertiary, modifier = Modifier.padding(top = 12.dp))
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

/** Words that are new on the redesigned result screen, in both guidance languages. */
object ResultCopy {
    val report = Bi("Report and export", "रिपोर्ट और एक्सपोर्ट")
    val ask = Bi("Ask about this check", "इस जाँच के बारे में पूछें")
    val again = Bi("Check again", "फिर से जाँचें")
    val sample = Bi("Sample data: made-up example, not from your device.", "नमूना डेटा: बनावटी उदाहरण, आपके डिवाइस का नहीं।")
    val rejectNotTested = Bi("Your No (Reject) was not tested in this check.", "इस जाँच में आपकी 'ना' (Reject) की जाँच नहीं हुई।")
    val findings = Bi("Findings", "फ़ाइंडिंग")
    val evidence = Bi("Evidence and limits", "सबूत और सीमाएँ")
    val noProblems = Bi("No consent problems in the captured evidence.", "दर्ज सबूतों में कोई कंसेंट समस्या नहीं मिली।")
    val notLegalAdvice = Bi("An automated technical assessment, not legal advice.", "स्वचालित तकनीकी आकलन, कानूनी सलाह नहीं।")
}

private val SCREEN_CHECKS = setOf(CheckId.C3, CheckId.C4, CheckId.C5, CheckId.C6, CheckId.C7, CheckId.C8)

private fun headline(findings: List<Finding>): String {
    val c1 = findings.firstOrNull { it.checkId == CheckId.C1 }
    val c2 = findings.firstOrNull { it.checkId == CheckId.C2 }
    val v0 = findings.any { it.checkId == CheckId.V0 }
    return when {
        v0 -> "Too few lookups were captured to judge this app. Why, and what to try next, is just below."
        com.dpdpxray.core.rules.Inconclusive.reason(findings) == com.dpdpxray.core.rules.Inconclusive.Reason.CONSENT_HISTORY_UNKNOWN ->
            "No score: this app kept its data, so whether consent came before its tracker lookups is unknown " +
                "(${findings.first { it.checkId == CheckId.U1 }.entities.joinToString()}). Run a Full check on a test install."
        c2 != null -> "Tracker hosts were looked up after the user refused consent: ${c2.entities.joinToString()}."
        c1 != null -> "Hosts of ${c1.entities.size} tracker${if (c1.entities.size == 1) "" else "s"} looked up before any consent choice: ${c1.entities.joinToString()}."
        findings.isEmpty() -> "No tracker lookups before consent and no consent-screen problems in this capture."
        findings.all { it.severity == Severity.INFO } && findings.any { it.checkId == CheckId.U1 } ->
            "Consent history unknown: this app kept its data, so consent given earlier could not be checked."
        findings.all { it.severity == Severity.INFO } -> "No third-party tracker lookups before consent and no scored consent-screen problems in this capture. See the information notes."
        // Scored findings outside the consent screen (C9, C13 manifest defaults, C14 permission before notice).
        findings.none { it.severity != Severity.INFO && it.checkId in SCREEN_CHECKS } -> "No tracker lookups before consent, but other checks found issues. See the findings below."
        else -> "No tracker lookups before consent, but the consent screen needs work."
    }
}

/**
 * The result's top: the score ring (a thin arc in the band's one colour, the number counting up once), the band word,
 * and one line of severity counts. Inconclusive shows "?" in a dashed ring, never a zero.
 */
@Composable
private fun ScoreHero(b: AuditBundle, grade: ConfidenceGrade?, lang: GuideLang, modifier: Modifier = Modifier) {
    val r = b.result
    val color = bandColor(r.band)
    Column(modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ScoreRing(if (r.band == ScoreBand.INCONCLUSIVE) null else r.score, r.band, ringSize = 176.dp, key = b.meta.id)
        Text(r.band.label, style = MaterialTheme.typography.titleLarge, color = color, modifier = Modifier.padding(top = 12.dp))
        Text("Consent readiness", style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
        val counts = Severity.entries.map { s -> s to r.findings.count { it.severity == s } }.filter { it.second > 0 }
        if (counts.isNotEmpty()) {
            Text(
                counts.joinToString(" · ") { (s, n) -> "$n ${s.label.lowercase()}" },
                style = MaterialTheme.typography.bodyMedium, color = Film.boneDim, modifier = Modifier.padding(top = 4.dp),
            )
        }
        // How sure the capture is, in words (the full grade is in Evidence and limits); a sample was never measured.
        val confidence = when {
            b.meta.sample -> "Confidence not measured"
            grade != null -> "Evidence confidence: ${grade.level.label}"
            else -> null
        }
        val capture = if (!b.meta.sample && b.meta.timingProfile == AuditTimingPolicy.NORMAL) "Normal capture" else null
        listOfNotNull(confidence, capture).takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Film.boneDim, modifier = Modifier.padding(top = 2.dp))
        }
        // How far AI and documentation readings could move this score; shown only when they can move it at all.
        ScoreRobustnessLine.of(r)?.let { rob ->
            FoldText(
                ScoreRobustnessLine.text(rob, lang), Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
                color = if (rob.bandStable) Film.boneDim else Film.warn, moreLabel = lang.moreLabel, lessLabel = lang.lessLabel,
            )
        }
    }
}

/** Relation chips keep one colour each: required (or good practice) warm, context and not applicable muted. */
private fun relationColor(r: LawRelation) = when (r) {
    LawRelation.REQUIRED -> Brand.peach
    LawRelation.CONTEXT -> Film.scan
    LawRelation.NOT_APPLICABLE -> Film.boneDim
}

private fun relationTag(p: LensProfile, r: LawRelation) = "${p.name}: ${p.relationLabel(r)}"

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


/**
 * What to do next, from the existing result guide (so the choices still depend on the band, coverage and samples): the
 * first, recommended step is the one filled button; the rest are plain rows in one group.
 */
@Composable
private fun ResultActions(next: List<NextStep>, lang: GuideLang, onAction: (NextAction) -> Unit, modifier: Modifier = Modifier, primary: Boolean = true) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!primary) {
            // Another section holds the screen's one filled action: every step is a plain row.
            if (next.isNotEmpty()) Group {
                next.forEachIndexed { i, step ->
                    if (i > 0) GroupDivider()
                    ListRow(step.title.pick(lang), subtitle = step.reason.pick(lang), onClick = { onAction(step.action) })
                }
            }
            return@Column
        }
        next.firstOrNull()?.let {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PrimaryButton(it.title.pick(lang), { onAction(it.action) })
                Text(
                    it.reason.pick(lang), style = MaterialTheme.typography.bodySmall, color = Film.boneDim, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp, start = 8.dp, end = 8.dp),
                )
            }
        }
        if (next.size > 1) Group {
            next.drop(1).forEachIndexed { i, step ->
                if (i > 0) GroupDivider()
                ListRow(step.title.pick(lang), subtitle = step.reason.pick(lang), onClick = { onAction(step.action) })
            }
        }
    }
}

/**
 * A finding's second line: severity word first (the dot only repeats it), then who was contacted, then where the
 * finding comes from (observed on the device, AI-inferred or inferred from documentation).
 */
private fun findingSubtitle(f: Finding, source: ResultSource): String = buildString {
    append(f.severity.label)
    if (f.entities.isNotEmpty()) append(" · ").append(f.entities.joinToString())
    append(" · ").append(if (source == ResultSource.OBSERVED) "Observed on device" else source.label)
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
        val lensSettings by vm.settings.collectAsState()
        val profile = remember(lensSettings.legalProfile) { vm.lensProfile() }
        val lensProfile = vm.legal.profile(profile)
        val mapping = remember(profile, f.checkId) { vm.legal.mapping(profile, f.checkId) }
        val explanations by vm.explanations.collectAsState()
        val explaining by vm.explaining.collectAsState()
        Column(Modifier.fillMaxSize()) {
            TopBar(f.checkId.code, onBack)
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                Text(f.title, style = MaterialTheme.typography.headlineSmall, color = severityColor(f.severity), modifier = Modifier.semantics { heading() })
                ChipFlow(Modifier.padding(vertical = 12.dp)) {
                    Tag(f.severity.label, severityColor(f.severity))
                    Tag(ResultSources.of(f, b.sessions).label, sourceColor(ResultSources.of(f, b.sessions)))
                    if (lensProfile.penaltyFigures && f.penaltyBand.name != "NONE") Tag(f.penaltyBand.label.substringBefore(" ("), Film.boneDim)
                    Tag(relationTag(lensProfile, mapping.relation), relationColor(mapping.relation))
                }
                val explained = remember(f, profile) { LensText.explanation(f, profile) }
                FoldText(explained.text, style = MaterialTheme.typography.bodyLarge, color = Film.bone, maxLines = 3)
                if (explained.citesIndia) LearnMore(label = "About the wording") { Note(LensText.INDIA_WORDING_NOTE) }

                val aliases = remember(b, f) { vm.findingAliases(f, b) }
                if (aliases.isNotEmpty()) {
                    SectionTitle("Observed tracker aliases")
                    ChipFlow { aliases.forEach { Tag(it, Film.warn) } }
                    Note("The DNS reply revealed an alias to a catalogue tracker. This does not show what data was sent.")
                }

                val notice = remember(b, f) { vm.rightsNotice(b, f.checkId) }
                var showNotice by remember(b.meta.id, f.checkId) { mutableStateOf(false) }
                if (notice != null) {
                    SectionTitle("Ask the developer to investigate")
                    SecondaryAction("Draft notice to developer", { showNotice = true }, Modifier.fillMaxWidth())
                    FoldText("Review before sharing. The cited rights relate to India's DPDP framework; this is not a legal finding.", Modifier.padding(top = 8.dp))
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
                    if (bmp != null) Image(
                        bmp, contentDescription = "Captured consent screen", contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, FilmExtra.cardEdge, RoundedCornerShape(16.dp)),
                    )
                }

                if (f.evidence.isNotEmpty()) {
                    SectionTitle("Evidence")
                    f.evidence.forEach { CodeBlock(it, Modifier.padding(bottom = 6.dp)) }
                }

                SectionTitle("What the law says · ${lensProfile.name}")
                ChipFlow(Modifier.padding(bottom = 8.dp)) { Tag(lensProfile.relationLabel(mapping.relation), relationColor(mapping.relation)) }
                FoldText(mapping.note, Modifier.padding(bottom = 6.dp), color = Film.bone)
                var loaded by remember(f, profile) { mutableStateOf(false) }
                val cites by produceState(initialValue = emptyList<LensPassage>(), f, profile) { value = vm.lensPassagesFor(f, profile); loaded = true }
                if (!loaded) SkeletonLines(3, label = "Finding the relevant sections")
                else if (cites.isEmpty()) Note("No legal passage is cited for this finding under this lens.")
                // The passages are long legal text: they fold behind one line that says how many there are.
                if (cites.isNotEmpty()) LearnMore(label = if (cites.size == 1) "Read the cited passage" else "Read the ${cites.size} cited passages", openLabel = "Hide passages") {
                    cites.forEach { p -> LawPassage(p) }
                }
                FoldText(
                    if (profile == LegalProfile.INDIA) "Legal text is context, not a verdict. The DPDP Act's main provisions commence on 13 May 2027, so this describes readiness."
                    else "Legal text is context, not a verdict. Confirm applicability with a qualified reviewer.",
                    color = Film.warn,
                )
                LearnMore(label = "Compare laws") {
                    vm.legal.compare(f.checkId.code).forEach { (id, m) -> CompareRow(vm.legal, id, m.relation, m.note) }
                }

                SectionTitle("Explain with on-device AI")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryAction("In English", { vm.explain(f, hindi = false) }, Modifier.weight(1f), enabled = explaining == null)
                    SecondaryAction("हिन्दी में", { vm.explain(f, hindi = true) }, Modifier.weight(1f), enabled = explaining == null)
                }
                listOf("en", "hi").forEach { lang ->
                    val key = "${f.checkId}-$lang"
                    if (explaining == key) {
                        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            BreathingDot(Film.scan)
                            Spacer(Modifier.width(10.dp))
                            Note("Gemma is writing on this phone…", Film.scan)
                        }
                        SkeletonLines(3, Modifier.padding(top = 8.dp), label = "Writing the explanation")
                    }
                    explanations[key]?.let { e ->
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Tag(e.source.label, sourceColor(e.source))
                            Text(if (lang == "hi") "हिन्दी" else "English", style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
                        }
                        FoldText(e.text, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyLarge, color = Film.bone, maxLines = 4)
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

/** One cited passage: the text, an English summary for a non-English original, then source, wording and where it was read. */
@Composable
private fun LawPassage(p: LensPassage) {
    RuleRow(Film.scan) {
        Text(p.text, style = MaterialTheme.typography.bodyMedium)
        p.englishSummary?.let {
            Text("English summary, not an official translation: $it", style = MaterialTheme.typography.bodySmall, color = Film.boneDim, modifier = Modifier.padding(top = 6.dp))
        }
        val kind = if (p.kind == PassageKind.LAW) "" else " · ${p.kind.label}"
        Text("${p.source} · ${p.wording}$kind", style = MaterialTheme.typography.labelMedium, color = Film.scan, modifier = Modifier.padding(top = 6.dp))
        val where = listOfNotNull(p.url, p.retrieved?.let { "read $it" }).joinToString(" · ")
        if (where.isNotEmpty()) Text(where, style = MaterialTheme.typography.labelSmall, color = Film.boneDim, modifier = Modifier.padding(top = 2.dp))
    }
    Spacer(Modifier.height(8.dp))
}

/** A "Compare laws" line: the relation chip, the law's name and its note for this check. */
@Composable
private fun CompareRow(lens: LegalLens, id: LegalProfile, relation: LawRelation, note: String) {
    val p = lens.profile(id)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        ChipFlow { Tag(p.relationLabel(relation), relationColor(relation)); Text(p.name, style = MaterialTheme.typography.titleSmall, color = Film.bone) }
        Text(note, style = MaterialTheme.typography.bodySmall, color = Film.boneDim, modifier = Modifier.padding(top = 4.dp))
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
                FoldText("Copy a fix and paste it into your project (with clipboard sync, straight into Android Studio on your laptop). Then rebuild and audit again.")
                if (fixes.isEmpty()) EmptyHint("No code fixes for this check.", glyph = Glyph.BOOK)
                fixes.forEachIndexed { i, fix ->
                    CollapsibleCard(
                        fix.title, Modifier.reveal(i),
                        summary = listOfNotNull(fix.manifest?.let { "Manifest" }, fix.code?.let { "Code" }, fix.steps.takeIf { it.isNotEmpty() }?.let { "${it.size} steps" }).joinToString(" · "),
                        glyph = Glyph.BOOK, initiallyOpen = i == 0,
                    ) {
                        if (!fix.verified) Callout("Check the exact API for your SDK version in the vendor docs.", Modifier.padding(bottom = 8.dp))
                        fix.manifest?.let { m ->
                            Text("AndroidManifest.xml", style = MaterialTheme.typography.labelMedium, color = Film.boneDim, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
                            CodeBlock(m)
                            TextButton(onClick = { vm.copy("manifest", m) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy", color = Film.scan) }
                        }
                        fix.code?.let { c ->
                            CodeBlock(c)
                            TextButton(onClick = { vm.copy("code", c) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy", color = Film.scan) }
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
                SectionCard(Modifier.reveal(0), title = "Save to this phone", glyph = Glyph.SAMPLE) {
                    PrimaryAction("Save PDF to Downloads", { vm.exportPdf(b, share = false) })
                    Spacer(Modifier.height(10.dp))
                    SecondaryAction("Save Markdown", { vm.saveMarkdown(b) }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    SecondaryAction("Save interactive report (HTML)", { vm.exportHtml(b, share = false) }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    SecondaryAction("Export audit data (JSON)", { vm.exportAuditJson(b, share = false) }, Modifier.fillMaxWidth())
                    val evidence by vm.evidenceLine.collectAsState()
                    evidence?.takeIf { it.first == b.meta.id }?.let { (_, line) ->
                        Text(line, style = MaterialTheme.typography.labelMedium, color = Film.scan, modifier = Modifier.padding(top = 8.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    FoldText("Saved files land in Downloads/TraceVeil, ready to share or move to your laptop. The HTML report opens offline in any browser.")
                }
                SectionCard(Modifier.reveal(1), title = "Share", glyph = Glyph.ASK) {
                    SecondaryAction("Share PDF", { vm.exportPdf(b, share = true) }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    SecondaryAction("Share interactive report", { vm.exportHtml(b, share = true) }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    SecondaryAction("Share audit data (JSON)", { vm.exportAuditJson(b, share = true) }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Callout(
                        "Review audit data (JSON) before sharing it or committing it to a repository.",
                        detail = "Audit data (JSON) is this audit's full evidence (lookups, consent-screen text, device model) for TraceVeil CI on a laptop or build server.",
                    )
                }
                val lensSettings by vm.settings.collectAsState()
                val profile = remember(lensSettings.legalProfile) { vm.lensProfile() }
                CollapsibleCard("Report preview", summary = "The Markdown report under the ${vm.legal.profile(profile).name} lens, limits included", glyph = Glyph.REPORTS) {
                    val preview by produceState("", b.meta.id, profile) {
                        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { vm.markdown(b, profile) }
                    }
                    if (preview.isEmpty()) SkeletonLines(5, label = "Building the report")
                    else Text(preview, style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
