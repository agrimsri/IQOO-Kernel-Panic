package com.dpdpxray.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.autoguard.UpdateWatchCopy
import com.dpdpxray.app.data.AuditComparisonResult
import com.dpdpxray.app.data.UpdateDiffWording
import com.dpdpxray.app.data.VersionUpdate
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ui.components.KeyCaveat
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.guide.LinkAction
import com.dpdpxray.app.ui.components.FoldText
import com.dpdpxray.app.ui.components.Hairline
import com.dpdpxray.app.ui.components.SectionCard
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.PillButton
import com.dpdpxray.app.ui.guide.ScoreChip
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.bandColor
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.update.UpdatedApp

/**
 * Update Watch on Home and Reports: checked apps whose installed version differs from their latest check. Each row
 * offers "Re-check", which opens the app picker with that app selected; nothing starts until the person taps Start.
 * Shown only when there is at least one such app.
 */
@Composable
fun UpdatedSinceCard(items: List<UpdatedApp>, onRecheck: (String) -> Unit, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    SectionCard(modifier, title = UpdateWatchCopy.cardTitle(items.size), glyph = Glyph.RESET, accent = Film.warn) {
        FoldText(UpdateWatchCopy.CARD_NOTE)
        items.forEachIndexed { i, item ->
            if (i > 0) Hairline(Modifier.padding(vertical = 4.dp))
            UpdatedRow(item, onRecheck)
        }
    }
}

@Composable
private fun UpdatedRow(item: UpdatedApp, onRecheck: (String) -> Unit) {
    val color = bandColor(item.lastBand)
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        ScoreChip(UpdateWatchCopy.scoreChip(item), color, Modifier.semantics { contentDescription = UpdateWatchCopy.lastCheckLine(item) }, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.label, style = MaterialTheme.typography.titleMedium, color = Film.bone, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(UpdateWatchCopy.versionLine(item), style = MaterialTheme.typography.bodyMedium, color = Film.scan, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(UpdateWatchCopy.lastCheckLine(item), style = MaterialTheme.typography.labelMedium, color = Film.boneDim, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        PillButton(UpdateWatchCopy.RECHECK, { onRecheck(item.packageName) })
    }
}

/**
 * The body of the result's "Before / after" card when the app was updated between the previous check and this one:
 * versions, score change, what the old check recorded and this one did not ("Fixed in this update"), what is new, what
 * is still there, the honest comparison line and the caveat. Emitted into the card's column.
 */
@Composable
internal fun UpdateDiff(update: VersionUpdate, change: AuditComparisonResult.Available, appLabel: String, vm: XrayViewModel) {
    Text(UpdateDiffWording.title(update), style = MaterialTheme.typography.titleMedium, color = Film.bone)
    Text(
        "Score ${UpdateDiffWording.score(change)}", style = MaterialTheme.typography.bodyLarge,
        color = if (change.scoreDelta >= 0) Film.clear else Film.warn,
    )
    DiffList(UpdateDiffWording.FIXED, change.noLongerRecorded, Film.clear)
    DiffList(UpdateDiffWording.NEW, change.newlyRecorded, Film.warn)
    DiffList(UpdateDiffWording.STILL, change.stillRecorded, Film.leak)
    Note(UpdateDiffWording.honest(update, change.previousTimeMs))
    KeyCaveat(UpdateDiffWording.CAVEAT)
    LinkAction(
        "Share the update report",
        { vm.shareText("TraceVeil update report: $appLabel", UpdateDiffWording.markdown(appLabel, update, change)) },
        glyph = Glyph.REPORTS,
    )
}

@Composable
private fun DiffList(title: String, checks: Set<CheckId>, color: Color) {
    Text(
        "$title (${checks.size})", style = MaterialTheme.typography.titleSmall, color = Film.bone,
        modifier = Modifier.padding(top = 4.dp).semantics { heading() },
    )
    UpdateDiffWording.lines(checks).forEach { line ->
        Text("• $line", style = MaterialTheme.typography.bodyMedium, color = if (checks.isEmpty()) Film.boneDim else color)
    }
}
