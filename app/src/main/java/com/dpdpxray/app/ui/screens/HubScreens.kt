package com.dpdpxray.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.BuildConfig
import com.dpdpxray.app.ai.LlmStatus
import com.dpdpxray.app.data.AuditMeta
import com.dpdpxray.app.data.SampleAudit
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ui.components.ChipFlow
import com.dpdpxray.app.ui.components.FoldText
import com.dpdpxray.app.ui.components.cardSurface
import com.dpdpxray.app.ui.components.pressClickable
import com.dpdpxray.app.ui.components.reveal
import com.dpdpxray.app.ui.components.Hairline
import com.dpdpxray.app.ui.components.NavRow
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.SectionCard
import com.dpdpxray.app.ui.components.Tag
import com.dpdpxray.app.ui.components.TopBar
import com.dpdpxray.app.ui.guide.EmptyState
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.GlyphIcon
import com.dpdpxray.app.ui.guide.RecentChecks
import com.dpdpxray.app.ui.guide.ScoreChip
import com.dpdpxray.app.ui.modes.ModeCopy
import com.dpdpxray.app.ui.modes.ModeSwitch
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.FilmExtra
import com.dpdpxray.app.ui.theme.Radii
import com.dpdpxray.app.ui.theme.bandColor
import com.dpdpxray.core.model.ScoreBand
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The Reports tab: every saved check, newest first, with a one-line count per score band. */
@Composable
fun ReportsScreen(vm: XrayViewModel, onOpen: (String) -> Unit) {
    val audits by vm.audits.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopBar("Reports", onBack = null)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "intro") {
                Column(Modifier.reveal(0), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FoldText("Checks saved on this phone, newest first. Open one for its findings, evidence and exports.")
                    val bands = ScoreBand.entries.mapNotNull { b -> audits.count { it.band == b }.takeIf { it > 0 }?.let { b to it } }
                    if (bands.isNotEmpty()) ChipFlow {
                        bands.forEach { (band, n) -> Tag("$n ${band.label}", bandColor(band)) }
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }
            if (audits.isEmpty()) item(key = "empty") {
                EmptyState(
                    "No checks yet",
                    "Your checks will appear here.",
                    Modifier.reveal(1),
                    action = "See a sample" to { vm.openSample(onOpen) },
                )
            }
            itemsIndexed(audits, key = { _, a -> a.id }) { i, a -> RecentCheckCard(a, Modifier.reveal(1 + i).animateItem()) { onOpen(a.id) } }
        }
    }
}

/** The More tab: tools that are not on Home, app settings and the AI lab, and what TraceVeil is. */
@Composable
fun MoreScreen(
    vm: XrayViewModel,
    onSettings: () -> Unit,
    onLab: () -> Unit,
    onCamera: () -> Unit,
    onVoice: (String) -> Unit,
    onHowItWorks: () -> Unit,
    onOpen: (String) -> Unit,
    onTrust: (() -> Unit)? = null,
) {
    val audits by vm.audits.collectAsState()
    val llm by vm.llmStatus.collectAsState()
    val settings by vm.settings.collectAsState()
    var showSamples by remember { mutableStateOf(false) }
    val latest = audits.firstOrNull()
    Column(Modifier.fillMaxSize()) {
        TopBar("More", onBack = null)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionCard(Modifier.reveal(0), title = ModeCopy.title(settings.audience).en, glyph = Glyph.HOME) {
                ModeSwitch(settings.audience, { a -> vm.updateSettings { it.copy(audience = a) } })
            }
            SectionCard(Modifier.reveal(1), title = "Tools") {
                NavRow("Camera check", "Review a photo of a consent screen", onCamera, glyph = Glyph.CAMERA)
                if (latest != null) {
                    Hairline()
                    NavRow("Ask about my last check", "By voice or tap, about ${latest.targetLabel}", { onVoice(latest.id) }, glyph = Glyph.ASK)
                }
                Hairline()
                NavRow("See a sample", "A labelled example result; no setup", { vm.openSample(onOpen) }, glyph = Glyph.SAMPLE)
                Hairline()
                NavRow("More samples", "Synthetic examples, not a live audit", { showSamples = true }, glyph = Glyph.REPORTS)
                Hairline()
                NavRow("How it works", "Replay the short introduction", onHowItWorks, glyph = Glyph.BOOK)
            }
            SectionCard(Modifier.reveal(2), title = "App") {
                NavRow("Settings", "Text size, check timing, voice, your domains and privacy", onSettings, glyph = Glyph.SETTINGS)
                Hairline()
                NavRow("AI lab", aiStatusLine(llm), onLab, glyph = Glyph.AI)
            }
            SectionCard(Modifier.reveal(3), title = "About TraceVeil", glyph = Glyph.INFO) {
                Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = Film.bone)
                Note("Checks run on this phone. Results are an automated technical assessment, not legal advice.")
                if (onTrust != null) {
                    Hairline(Modifier.padding(vertical = 8.dp))
                    NavRow("Why you can trust TraceVeil", "What stays on the phone, its permissions, network use and identity", onTrust, glyph = Glyph.LOCK, accent = Film.clear)
                }
            }
        }
    }
    if (showSamples) SampleFixturesDialog(vm, onOpen) { showSamples = false }
}

/** One line on the optional on-device model, shared by Home's readiness panel and the More tab. */
internal fun aiStatusLine(llm: LlmStatus): String = when (val s = llm) {
    is LlmStatus.Ready -> "${s.model} on ${s.backend.label}"
    is LlmStatus.Loading -> "Loading on ${s.backend.label}…"
    LlmStatus.NotLoaded -> "Model found; it loads when an audit starts"
    LlmStatus.NoModel -> "No model yet. Screen checks use the accessibility tree only"
    is LlmStatus.Failed -> s.message
}

/** The synthetic sample variants (not a live audit). Shared by Home and the More tab. */
@Composable
internal fun SampleFixturesDialog(vm: XrayViewModel, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sample evidence") },
        text = {
            Column {
                Text("Synthetic examples, not a live audit. Example IPs do not identify real server locations.")
                SampleAudit.Variant.entries.filter { it != SampleAudit.Variant.BASELINE }.forEach { variant ->
                    TextButton(onClick = {
                        onDismiss()
                        vm.openSample(variant, onOpen)
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text(variant.label) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = Film.raised,
    )
}

/**
 * One saved check: score badge in its band colour, app name, band and finding count, date, and any coverage warning
 * as a small chip. Reads as one button to TalkBack.
 */
@Composable
internal fun RecentCheckCard(meta: AuditMeta, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val color = bandColor(meta.band)
    val shape = RoundedCornerShape(Radii.tile)
    Row(
        modifier.fillMaxWidth().heightIn(min = 80.dp)
            .pressClickable(shape = shape, onClick = onClick)
            .cardSurface(Radii.tile)
            .background(remember(color) { Brush.horizontalGradient(0f to color.copy(alpha = 0.08f), 0.4f to Color.Transparent) })
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScoreChip(RecentChecks.scoreText(meta), color)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(meta.targetLabel, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (meta.sample) {
                    Spacer(Modifier.width(8.dp))
                    Tag("Sample", Film.boneDim)
                }
            }
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = color)) { append(meta.band.label) }
                    append(" · ")
                    append(if (meta.findingCount == 1) "1 finding" else "${meta.findingCount} findings")
                },
                style = MaterialTheme.typography.bodyMedium, color = Film.boneDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.CLOCK, Film.boneDim, size = 14.dp)
                Spacer(Modifier.width(6.dp))
                Text(SimpleDateFormat("d MMM, HH:mm", Locale.US).format(Date(meta.createdAtMs)), style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
            }
            RecentChecks.coverageWarning(meta)?.let { warning -> Tag(warning, Film.warn, Modifier.padding(top = 4.dp)) }
        }
        Spacer(Modifier.width(8.dp))
        GlyphIcon(Glyph.CHEVRON_RIGHT, Film.boneDim, size = 18.dp)
    }
}
