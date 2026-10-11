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
import com.dpdpxray.app.notices.OpenSourceNotices
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
import com.dpdpxray.app.ui.guide.guideLang
import com.dpdpxray.app.ui.system.Group
import com.dpdpxray.app.ui.system.GroupDivider
import com.dpdpxray.app.ui.system.ListRow
import com.dpdpxray.app.ui.system.SegmentedControl
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Box
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

/** The Reports tab: every saved check, newest first, with a band filter. */
@Composable
fun ReportsScreen(vm: XrayViewModel, onOpen: (String) -> Unit, onRecheck: (String) -> Unit = {}) {
    val audits by vm.audits.collectAsState()
    // Update Watch: checked apps whose installed version changed since their last check (same list as Home).
    val updated by vm.updatedApps.collectAsState()
    val lang = guideLang()
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val bands = listOf(null, ScoreBand.AT_RISK, ScoreBand.NEEDS_WORK, ScoreBand.READY)
    val shown = remember(audits, filter) { bands[filter].let { b -> if (b == null) audits else audits.filter { it.band == b } } }
    Column(Modifier.fillMaxSize()) {
        TopBar("Reports", onBack = null)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (updated.isNotEmpty()) item(key = "updated") { UpdatedSinceCard(updated, onRecheck, Modifier.padding(bottom = 16.dp)) }
            if (audits.isNotEmpty()) item(key = "filter") {
                SegmentedControl(
                    listOf("All", ScoreBand.AT_RISK.label, ScoreBand.NEEDS_WORK.label, ScoreBand.READY.label), filter, { filter = it },
                    Modifier.padding(bottom = 8.dp),
                )
            }
            if (audits.isEmpty()) item(key = "empty") {
                EmptyState(
                    "No checks yet",
                    "Your checks will appear here.",
                    Modifier.reveal(1),
                    action = "See a sample" to { vm.openSample(onOpen) },
                )
            } else if (shown.isEmpty()) item(key = "none") {
                EmptyState("Nothing here", "No saved check is ${ScoreBand.entries.first { it == bands[filter] }.label.lowercase()}.")
            }
            itemsIndexed(shown, key = { _, a -> a.id }) { _, a ->
                Box(Modifier.animateItem().cardSurface(Radii.row)) { CheckRow(a, lang) { onOpen(a.id) } }
            }
            // Back up / restore: also reachable on a new phone, when the list above is still empty.
            item(key = "backup") { BackupRestoreCard(vm, Modifier.padding(top = 24.dp)) }
        }
    }
}

/** The More tab: tools, app settings, help and what TraceVeil is, as plain grouped lists. */
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
    /** "Open-source notices"; null hides the row. */
    onNotices: (() -> Unit)? = null,
) {
    val audits by vm.audits.collectAsState()
    val llm by vm.llmStatus.collectAsState()
    var showSamples by remember { mutableStateOf(false) }
    val latest = audits.firstOrNull()
    Column(Modifier.fillMaxSize()) {
        TopBar("More", onBack = null)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Group(Modifier.reveal(0), title = "Tools") {
                MoreRow("Camera check", "Review a photo of a consent screen", Glyph.CAMERA, onCamera)
                if (latest != null) {
                    GroupDivider(54.dp)
                    MoreRow("Ask about my last check", latest.targetLabel, Glyph.ASK, { onVoice(latest.id) })
                }
                GroupDivider(54.dp)
                MoreRow("See a sample", "A labelled example result", Glyph.SAMPLE, { vm.openSample(onOpen) })
                GroupDivider(54.dp)
                MoreRow("More samples", "Synthetic examples, not a live audit", Glyph.REPORTS, { showSamples = true })
            }
            Group(Modifier.reveal(1), title = "App") {
                MoreRow("Settings", "Mode, text size, voice, your domains and privacy", Glyph.SETTINGS, onSettings)
                GroupDivider(54.dp)
                MoreRow("AI lab", aiStatusLine(llm), Glyph.AI, onLab)
            }
            Group(Modifier.reveal(2), title = "Help") {
                MoreRow("How it works", "Replay the short introduction", Glyph.BOOK, onHowItWorks)
            }
            Group(
                Modifier.reveal(3), title = "About TraceVeil",
                footer = "Checks run on this phone. Results are an automated technical assessment, not legal advice.",
            ) {
                if (onTrust != null) {
                    MoreRow("Why you can trust TraceVeil", "What stays on the phone, its permissions and network use", Glyph.LOCK, onTrust)
                    GroupDivider(54.dp)
                }
                if (onNotices != null) {
                    MoreRow(OpenSourceNotices.TITLE, OpenSourceNotices.ROW_DETAIL, Glyph.BOOK, onNotices)
                    GroupDivider(54.dp)
                }
                ListRow("Version", trailing = { Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodyMedium, color = Film.boneDim) })
            }
        }
    }
    if (showSamples) SampleFixturesDialog(vm, onOpen) { showSamples = false }
}

/** A More row: a quiet glyph, a title with one line under it, and a chevron. */
@Composable
private fun MoreRow(title: String, subtitle: String?, glyph: Glyph, onClick: () -> Unit) {
    ListRow(title, subtitle = subtitle, leading = { GlyphIcon(glyph, Film.boneDim, size = 24.dp) }, onClick = onClick)
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
