package com.dpdpxray.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.audit.AuditProgress
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ui.observedAliasLabel
import com.dpdpxray.app.ui.components.BreathingDot
import com.dpdpxray.app.ui.components.Callout
import com.dpdpxray.app.ui.components.ChipFlow
import com.dpdpxray.app.ui.components.KeyCaveat
import com.dpdpxray.app.ui.components.RecordingDot
import com.dpdpxray.app.ui.components.SkeletonCard
import com.dpdpxray.app.ui.components.SkeletonLines
import com.dpdpxray.app.ui.components.cardSurface
import com.dpdpxray.app.ui.components.reveal
import com.dpdpxray.app.ui.components.ConsentLine
import com.dpdpxray.app.ui.components.HostText
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.SecondaryAction
import com.dpdpxray.app.ui.components.Tag
import com.dpdpxray.app.ui.guide.Bi
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.GlyphIcon
import com.dpdpxray.app.ui.guide.GradientAction
import com.dpdpxray.app.ui.guide.guideLang
import com.dpdpxray.app.ui.system.Notice
import com.dpdpxray.app.ui.system.NoticeKind
import com.dpdpxray.app.ui.guide.GuideFlow
import com.dpdpxray.app.ui.guide.GuideStep
import com.dpdpxray.app.ui.guide.GuideTone
import com.dpdpxray.app.ui.guide.LivePhase
import com.dpdpxray.app.ui.theme.Brand
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.FilmExtra
import com.dpdpxray.app.ui.theme.LocalReduceMotion
import com.dpdpxray.app.ui.theme.Motion
import com.dpdpxray.app.ui.theme.categoryColor
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.NetEvent
import java.util.Locale

private sealed interface Row2 {
    data class Net(val e: NetEvent) : Row2
    data class Choice(val e: ConsentEvent) : Row2
}

@Composable
fun LiveAuditScreen(vm: XrayViewModel, onDone: (String) -> Unit, onBack: () -> Unit) {
    val progress by vm.progress.collectAsState()
    val net by vm.liveNet.collectAsState()
    val consent by vm.liveConsent.collectAsState()
    val developer = vm.settings.collectAsState().value.audience == com.dpdpxray.app.data.Audience.DEVELOPER

    LaunchedEffect(progress) {
        (progress as? AuditProgress.Done)?.let {
            vm.acknowledgeProgress()
            onDone(it.auditId)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        when (val p = progress) {
            is AuditProgress.Failed -> {
                Text("The check stopped", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 32.dp).semantics { heading() })
                Callout(p.message, Modifier.padding(vertical = 12.dp), color = Film.leak)
                Notice(GuideCopy.failed.pick(guideLang()), Modifier.padding(bottom = 8.dp), kind = NoticeKind.CAUTION)
                GradientAction("Back to Home", { vm.acknowledgeProgress(); onBack() }, Modifier.padding(top = 20.dp))
                return@Column
            }
            is AuditProgress.Running -> {
                Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    // A breathing red dot: the check is recording this app's lookups right now.
                    RecordingDot(Film.leak, Modifier.semantics { contentDescription = "Recording" })
                    Spacer(Modifier.width(6.dp))
                    Text(
                        p.config.targetLabel, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                }
                if (p.config.fastPreview) Callout("8-second observation; delayed traffic may be missed. Silent and Reject are not tested.", Modifier.padding(top = 8.dp), fold = true)
                // The plans really are a sequence, so they are shown as numbered frames.
                RunFrames(p.config.plans, p.planIndex, Modifier.padding(top = 12.dp))
                // Narrated in the background by GuideNarrator, so this banner only shows the step.
                run {
                    val lang = guideLang()
                    val step = GuideFlow.live(p)
                    Text(step.title.pick(lang), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                    if (step.body.pick(lang).isNotBlank()) Text(step.body.pick(lang), style = MaterialTheme.typography.bodyMedium, color = Film.boneDim, modifier = Modifier.padding(top = 2.dp))
                }
                val classified = net.map(vm::classify)
                val choice = consent.firstOrNull { it.kind == ConsentKind.ACCEPT || it.kind == ConsentKind.REJECT }
                val trackers = classified.filter { it.category.isTracking }
                val before = trackers.count { choice == null || it.tNanos < choice.tNanos }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Counter(classified.size, if (classified.size == 1) "lookup" else "lookups", Film.bone, Modifier.weight(1f))
                    Counter(trackers.size, "to trackers", if (trackers.isEmpty()) Film.bone else Film.high, Modifier.weight(1f))
                    Counter(before, "before any choice", if (before > 0) Film.leak else Film.bone, Modifier.weight(1f))
                }
                // The key caveat stays in view while numbers climb.
                KeyCaveat("A lookup is an attempt to contact an address, not proof data was sent.", Modifier.padding(top = 6.dp), color = Film.boneDim)
                // The orchestrator's own wording stays visible for developers; the banner above is the plain version.
                if (developer && LivePhase.of(p.step) != LivePhase.OTHER) {
                    Text("Status: ${p.step}", style = MaterialTheme.typography.labelMedium, color = Film.boneDim, modifier = Modifier.padding(top = 10.dp))
                }
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Live lookups", style = MaterialTheme.typography.titleSmall, color = Film.bone, modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.width(8.dp))
                    BreathingDot(Film.scan, size = 7.dp)
                }
                Timeline(classified, consent, p.sessionStartNanos, Modifier.weight(1f))
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SecondaryAction("Mark consent now", { vm.markConsentNow() }, Modifier.weight(1f))
                    SecondaryAction("Stop check", { vm.cancelAudit(); onBack() }, Modifier.weight(1f))
                }
            }
            else -> {
                Row(Modifier.padding(top = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Film.scan, strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Getting ready…", style = MaterialTheme.typography.titleMedium)
                }
                Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SkeletonLines(2)
                    SkeletonCard()
                }
            }
        }
    }
}

private fun planName(plan: AuditPlan) = when (plan) {
    AuditPlan.SILENT -> "Don't touch"
    AuditPlan.REJECT -> "Reject"
    AuditPlan.ACCEPT -> "Accept"
}

/** One frame per run: finished runs are ticked, the current one is outlined in the brand gradient. */
@Composable
private fun RunFrames(plans: List<AuditPlan>, current: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().semantics { contentDescription = "Run ${current + 1} of ${plans.size}: ${planName(plans[current.coerceIn(plans.indices)])}" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        plans.forEachIndexed { i, plan ->
            val shape = RoundedCornerShape(12.dp)
            val done = i < current
            val now = i == current
            val fill by animateColorAsState(
                when { now -> Film.fill; else -> Color.Transparent }, tween(300), label = "frame",
            )
            val weight by animateFloatAsState(if (now) 1.4f else 1f, tween(300), label = "frame-weight")
            Row(
                Modifier.weight(weight).heightIn(min = 48.dp).clip(shape).background(fill).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (done) GlyphIcon(Glyph.CHECK, Film.clear, size = 16.dp)
                else Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = if (now) Brand.coral else Film.boneDim)
                Spacer(Modifier.width(6.dp))
                Text(planName(plan), style = MaterialTheme.typography.labelLarge, color = if (now) Film.bone else Film.boneDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Counter(value: Int, label: String, color: Color, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    Column(modifier.fillMaxHeight().cardSurface(14.dp).padding(horizontal = 12.dp, vertical = 10.dp)) {
        // The number rolls up as it changes (instant with reduced motion); TalkBack reads the current value.
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                if (reduce) EnterTransition.None togetherWith ExitTransition.None
                else (slideInVertically(tween(Motion.MEDIUM, easing = Motion.enter)) { it / 2 } + fadeIn(tween(Motion.MEDIUM)))
                    .togetherWith(slideOutVertically(tween(Motion.SHORT, easing = Motion.exit)) { -it / 2 } + fadeOut(tween(Motion.SHORT)))
            },
            label = "counter",
        ) { v -> Text("$v", style = MaterialTheme.typography.headlineMedium, color = color) }
        Text(label, style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
    }
}

@Composable
private fun Timeline(events: List<NetEvent>, consent: List<ConsentEvent>, start: Long, modifier: Modifier) {
    val rows: List<Row2> = (events.map { Row2.Net(it) } + consent.filter { it.kind == ConsentKind.ACCEPT || it.kind == ConsentKind.REJECT }.map { Row2.Choice(it) })
        .sortedBy { if (it is Row2.Net) it.e.tNanos else (it as Row2.Choice).e.tNanos }
    val choiceEvent = consent.firstOrNull { it.kind == ConsentKind.ACCEPT || it.kind == ConsentKind.REJECT }
    val choiceAt = choiceEvent?.tNanos
    val choiceKind = choiceEvent?.kind
    val state = rememberLazyListState()
    LaunchedEffect(rows.size) { if (rows.isNotEmpty()) state.animateScrollToItem(rows.lastIndex) }
    LazyColumn(modifier.fillMaxWidth().padding(top = 8.dp), state = state) {
        if (consent.any { it.kind == ConsentKind.SCREEN_APPEARED || it.kind == ConsentKind.MANUAL_MARK }) {
            item { Note("Consent screen detected and sent to on-device review", Film.scan) }
        }
        if (rows.isEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    BreathingDot(Film.scan)
                    Spacer(Modifier.width(10.dp))
                    Note("No lookups yet. They appear here as the app tries to reach addresses.")
                }
            }
        }
        items(rows) { r ->
            // Each new lookup slides up and fades in once as it arrives; rows scrolled back into view do not replay.
            Box(Modifier.reveal(0)) {
                when (r) {
                    is Row2.Choice -> ConsentLine(
                        "${if (r.e.kind == ConsentKind.ACCEPT) "Accepted" else "Refused"} at ${secs(r.e.tNanos - start)} s  (${r.e.nodeText ?: ""})",
                        if (r.e.kind == ConsentKind.ACCEPT) Film.scan else Film.warn,
                    )
                    is Row2.Net -> TimelineRow(r.e, start, beforeConsent = choiceAt == null || r.e.tNanos < choiceAt, afterRefusal = choiceKind == ConsentKind.REJECT)
                }
            }
        }
    }
}

@Composable
fun TimelineRow(e: NetEvent, start: Long, beforeConsent: Boolean, afterRefusal: Boolean = false) {
    val leak = (beforeConsent || afterRefusal) && e.category.isTracking
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(secs(e.tNanos - start).padStart(5), style = MaterialTheme.typography.labelMedium, color = Film.boneDim, modifier = Modifier.width(44.dp))
        StatusDotColor(categoryColor(e.category))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            HostText(e.hostname, if (leak) Film.leak else Film.bone)
            observedAliasLabel(e)?.let { Tag(it, Film.warn) }
            Text(
                listOfNotNull(e.entity, e.category.name.lowercase().replace('_', ' ')).joinToString(", "),
                style = MaterialTheme.typography.labelMedium, color = Film.boneDim,
            )
        }
    }
}

@Composable
private fun StatusDotColor(color: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier.size(8.dp).clip(CircleShape).background(color),
    )
}

fun secs(nanos: Long) = String.format(Locale.US, "%.1f", nanos.coerceAtLeast(0) / 1e9)

/** Words for the live screen's stopped state, in both guidance languages. */
private object GuideCopy {
    val failed = Bi(
        "Fix the setup, then try again. Go back to Home and finish the setup steps, then tap Check an app again.",
        "सेटअप ठीक करें, फिर दोबारा कोशिश करें। होम पर जाएँ और सेटअप के चरण पूरे करें, फिर ऐप जाँचें दोबारा दबाएँ।",
    )
}
