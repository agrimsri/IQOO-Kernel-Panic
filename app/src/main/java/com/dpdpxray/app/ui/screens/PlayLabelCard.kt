package com.dpdpxray.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.data.AuditBundle
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ui.components.Callout
import com.dpdpxray.app.ui.components.ChipFlow
import com.dpdpxray.app.ui.components.CollapsibleCard
import com.dpdpxray.app.ui.components.Hairline
import com.dpdpxray.app.ui.components.KeyCaveat
import com.dpdpxray.app.ui.components.LearnMore
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.RuleRow
import com.dpdpxray.app.ui.components.SecondaryAction
import com.dpdpxray.app.ui.components.Tag
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.categoryColor
import com.dpdpxray.core.attribution.SdkGroup
import com.dpdpxray.core.datasafety.DemoLabels
import com.dpdpxray.core.datasafety.LabelPoint
import com.dpdpxray.core.datasafety.ObservedSdk
import com.dpdpxray.core.datasafety.PlayLabelCheck
import com.dpdpxray.core.datasafety.PlayLabelComparator
import com.dpdpxray.core.datasafety.PlayLabelRecord
import com.dpdpxray.core.datasafety.PlayLabelReport
import com.dpdpxray.core.datasafety.SdkExpectations

/**
 * Developer mode, on Results, under "Which SDK caused this?": the app's Google Play Data safety label (pasted, read from a
 * text file, or the LeakyShop demo label) set against the catalogued SDK hosts this check looked up. Points to review,
 * collection first, each with its caveat; the vendor sources behind every SDK expectation fold away. Nothing is fetched:
 * the label comes only from the person. Separate from the score.
 */
@Composable
internal fun PlayLabelCard(vm: XrayViewModel, b: AuditBundle, groups: List<SdkGroup>, modifier: Modifier = Modifier) {
    val record = b.playLabel
    val check = remember(b.meta.id, record, groups) { record?.let { vm.playLabelCheck(it, groups) } }
    var pasting by remember(b.meta.id) { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.attachPlayLabelFile(b, uri)
    }
    val reviewCount = check?.toReview?.size ?: 0

    CollapsibleCard(
        PlayLabelCopy.TITLE, modifier,
        summary = check?.summary ?: PlayLabelCopy.EMPTY_SUMMARY,
        summaryColor = if (reviewCount > 0) Film.warn else Film.boneDim,
        glyph = Glyph.BOOK,
        initiallyOpen = record != null,
    ) {
        KeyCaveat(PlayLabelCopy.CAVEAT, color = Film.scan)
        if (record == null || check == null) {
            Note(PlayLabelCopy.EMPTY_HINT)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryAction(PlayLabelCopy.PASTE, { pasting = true }, Modifier.weight(1f))
                SecondaryAction(PlayLabelCopy.OPEN_FILE, { picker.launch(arrayOf("text/*")) }, Modifier.weight(1f))
            }
            if (PlayLabelCopy.demoOffered(b.meta.targetPackage, b.meta.sample)) {
                SecondaryAction(PlayLabelCopy.DEMO, { vm.attachPlayLabel(b, DemoLabels.LEAKYSHOP, PlayLabelRecord.DEMO) }, Modifier.fillMaxWidth())
            }
        } else {
            LabelSummary(record, check)
            if (!check.label.readable) {
                Callout(PlayLabelComparator.UNREADABLE)
            } else {
                if (check.toReview.isEmpty()) Note("No points to review in this comparison.")
                else Text("Points to review (${check.toReview.size})", style = MaterialTheme.typography.titleSmall, color = Film.bone)
                check.toReview.forEachIndexed { i, p -> PointRow(i + 1, p) }
                check.points.filter { it.informational }.forEach { PointRow(null, it) }
                check.notCompared.forEach { Note(it) }
                if (check.observed.isNotEmpty()) {
                    LearnMore(label = "Sources behind the SDK expectations (${check.observed.size})", openLabel = "Hide sources") {
                        check.observed.forEach { SourceRow(it) }
                        Note(SdkExpectations.MAPPING_NOTE)
                    }
                }
            }
            LearnMore(label = "About this check", openLabel = "Hide") {
                Note(PlayLabelComparator.ABOUT)
                Note("Play Console Help: \"${PlayLabelReport.PLAY_REVIEW_QUOTE}\" ${PlayLabelReport.PLAY_HELP_URL}")
                Note(PlayLabelReport.RESEARCH_CONTEXT)
            }
            Hairline(Modifier.padding(vertical = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryAction(PlayLabelCopy.REPLACE, { pasting = true }, Modifier.weight(1f))
                SecondaryAction(PlayLabelCopy.REMOVE, { vm.removePlayLabel(b) }, Modifier.weight(1f))
            }
        }
    }

    if (pasting) PasteLabelDialog(
        onDismiss = { pasting = false },
        onUse = { text -> vm.attachPlayLabel(b, text, PlayLabelRecord.PASTED); pasting = false },
    )
}

/** Everyday mode: one calm line when a label is attached; null (nothing shown) otherwise. */
@Composable
internal fun playLabelEverydayLine(vm: XrayViewModel, b: AuditBundle, groups: List<SdkGroup>): String? {
    val record = b.playLabel
    return remember(b.meta.id, record, groups) { record?.let { PlayLabelCopy.everydayLine(vm.playLabelCheck(it, groups)) } }
}

/** What the attached label says, line by line, with where it came from. */
@Composable
private fun LabelSummary(record: PlayLabelRecord, check: PlayLabelCheck) {
    ChipFlow {
        Tag(PlayLabelCopy.SEPARATE_TAG, Film.scan)
        if (record.source == PlayLabelRecord.DEMO) Tag(PlayLabelCopy.DEMO_TAG, Film.warn)
        else Tag(if (record.source == PlayLabelRecord.FILE) "From a text file" else "Pasted text", Film.boneDim)
    }
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("The label says", style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
        PlayLabelReport.labelLines(check.label).forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = Film.bone) }
        check.label.notes.forEach { Note(it, Film.warn) }
    }
}

/** One point: number, neutral sentence, its caveat. Information-only notes are muted. */
@Composable
private fun PointRow(number: Int?, p: LabelPoint) {
    RuleRow(if (p.informational) Film.boneDim else Film.warn) {
        ChipFlow {
            Tag(if (p.informational) "For information" else "Point to review $number", if (p.informational) Film.boneDim else Film.warn)
            p.categories.forEach { Tag(it.label, Film.scan) }
        }
        Text(p.text, style = MaterialTheme.typography.bodyMedium, color = Film.bone, modifier = Modifier.padding(top = 8.dp))
        p.caveat?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Film.boneDim, modifier = Modifier.padding(top = 6.dp)) }
    }
}

/** One contacted SDK: when it was seen, what its documentation says, and where that was read. */
@Composable
private fun SourceRow(o: ObservedSdk) {
    val e = o.expectation
    RuleRow(categoryColor(o.category)) {
        Text(o.entity, style = MaterialTheme.typography.titleSmall, color = Film.bone)
        Text(
            "Seen ${o.relations.joinToString(", ")} · ${o.lookups} ${if (o.lookups == 1) "lookup" else "lookups"}",
            style = MaterialTheme.typography.labelMedium, color = Film.boneDim,
        )
        if (e.verified) {
            e.types.forEach { t ->
                Text("${t.category.label}: \"${t.quote}\"", style = MaterialTheme.typography.bodySmall, color = Film.bone, modifier = Modifier.padding(top = 4.dp))
            }
            e.sharesQuote?.let { Text("Sharing: \"$it\"", style = MaterialTheme.typography.bodySmall, color = Film.bone, modifier = Modifier.padding(top = 4.dp)) }
            Text("${e.sourceTitle} · read ${e.checked}", style = MaterialTheme.typography.labelMedium, color = Film.scan, modifier = Modifier.padding(top = 4.dp))
            e.sourceUrl?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Film.boneDim) }
            e.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Film.boneDim, modifier = Modifier.padding(top = 4.dp)) }
        } else {
            Text(
                "No vendor page checked: ${SdkExpectations.UNVERIFIED} (${PlayLabelComparator.join(e.categories.map { it.label })}).",
                style = MaterialTheme.typography.bodySmall, color = Film.warn, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** A multi-line box for the label text (long-press to paste). Nothing is sent anywhere. */
@Composable
private fun PasteLabelDialog(onDismiss: () -> Unit, onUse: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(PlayLabelCopy.PASTE_TITLE) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Note(PlayLabelCopy.PASTE_HINT)
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(com.dpdpxray.core.datasafety.DataSafetyParser.MAX_CHARS) },
                    placeholder = { Text("No data shared with third parties\nThis app may collect these data types\n…") },
                    minLines = 6, maxLines = 14,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Film.scan, unfocusedBorderColor = Film.line, cursorColor = Film.scan),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onUse(text) }, enabled = text.isNotBlank()) { Text("Compare") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = Film.raised,
    )
}
