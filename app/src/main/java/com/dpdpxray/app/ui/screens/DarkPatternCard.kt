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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.ui.components.CollapsibleCard
import com.dpdpxray.app.ui.components.Hairline
import com.dpdpxray.app.ui.components.KeyCaveat
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.Tag
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.theme.Brand
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.core.darkpattern.DarkPatternScanner
import com.dpdpxray.core.darkpattern.DarkPatternSignal
import com.dpdpxray.core.darkpattern.DarkPatternText
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.EvidenceType

/** The dark-pattern signals for one audit, read from its saved sessions. Never part of the findings or the score. */
data class DarkPatternView(val signals: List<DarkPatternSignal>, val screensChecked: Int) {
    val summary: String get() = DarkPatternText.summary(signals, screensChecked)
    val everydayLine: String? get() = DarkPatternText.everydayLine(signals, screensChecked)

    companion object {
        /** A broken or unexpected tree must never break the Results screen: it reads as nothing checked. */
        fun of(sessions: List<AuditSession>) = runCatching {
            DarkPatternView(DarkPatternScanner.scan(sessions), DarkPatternScanner.screensChecked(sessions))
        }.getOrDefault(DarkPatternView(emptyList(), 0))
    }
}

/**
 * Developer mode, on Results, right after the findings: "Dark-pattern signals (India CCPA 2023)". One row per pattern
 * with its Annexure 1 number, an Observed / AI-inferred chip, the evidence lines and the plans they came from. Opens by
 * itself when there is a signal; says "None seen in this capture" otherwise.
 */
@Composable
fun DarkPatternCard(sessions: List<AuditSession>, modifier: Modifier = Modifier) {
    val view = remember(sessions) { DarkPatternView.of(sessions) }
    val found = view.signals.isNotEmpty()
    CollapsibleCard(
        DarkPatternText.CARD_TITLE,
        modifier,
        summary = view.summary,
        summaryColor = if (found) Film.warn else Film.boneDim,
        glyph = Glyph.WARN,
        accent = if (found) Film.warn else Film.scan,
        initiallyOpen = found,
    ) {
        if (!found) Note("${view.summary}.")
        view.signals.forEachIndexed { i, s ->
            if (i > 0) Hairline()
            DarkPatternRow(s)
        }
        if (view.signals.any { it.evidenceType == EvidenceType.INFERRED }) Note(DarkPatternText.AI_NOTE)
        KeyCaveat(DarkPatternText.FOOTER, color = Film.boneDim)
    }
}

@Composable
private fun DarkPatternRow(s: DarkPatternSignal) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(DarkPatternText.rowTitle(s), style = MaterialTheme.typography.titleSmall, color = Film.bone, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Tag(DarkPatternText.chip(s), if (s.evidenceType == EvidenceType.OBSERVED) Film.scan else Brand.rose)
        }
        Text(DarkPatternText.resembles(s), style = MaterialTheme.typography.bodySmall, color = Film.boneDim)
        s.evidence.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = Film.bone) }
        if (s.plans.isNotEmpty()) Text(DarkPatternText.plans(s), style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
    }
}
