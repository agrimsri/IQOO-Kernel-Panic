package com.dpdpxray.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ai.NetIntelligence
import com.dpdpxray.app.ui.components.CollapsibleCard
import com.dpdpxray.app.ui.components.GroupHeader
import com.dpdpxray.app.ui.components.KeyCaveat
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.Tag
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.Phase
import com.dpdpxray.core.netcapture.FindingSource
import com.dpdpxray.core.netcapture.NetAssessment
import com.dpdpxray.core.netcapture.NetConfidence
import com.dpdpxray.core.netcapture.NetFinding
import com.dpdpxray.core.netcapture.RiskLevel

/**
 * The connection-level answer to "did this app misbehave after I said no?": a risk level, the few findings that matter,
 * and per-finding evidence on demand. Raw flows stay in the saved audit and the debug export, never on this screen.
 * Shown only for audits that ran the full-relay capture, so older audits look exactly as before.
 */
@Composable
fun NetworkFindingsSection(sessions: List<AuditSession>, vm: XrayViewModel) {
    if (sessions.none { it.captureDepth == "full-relay" }) return
    val settings by vm.settings.collectAsState()
    val catalog = remember(vm, settings.firstPartyDomains) { vm.graph.catalog() }
    val key = sessions.joinToString { it.id }
    val first = remember(key, catalog) { NetIntelligence.rules(sessions, catalog) }
    val a by produceState(first, key, catalog) { value = NetIntelligence.refine(first, vm.graph.llm) }

    GroupHeader("Network behaviour") {
        Tag(riskLabel(a), riskColor(a.level))
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(summaryLine(a), style = MaterialTheme.typography.bodyLarge, color = Film.bone)
        val after = a.traffic.firstOrNull { it.phase == Phase.AFTER_REJECT }
        if (after != null) Note("After Reject: ${after.connections} connection(s), ${kb(after.bytesUp)} sent, ${kb(after.bytesDown)} received.")
        a.findings.take(MAX_SHOWN).forEach { FindingCard(it) }
        if (a.findings.size > MAX_SHOWN) Note("${a.findings.size - MAX_SHOWN} lower-priority finding(s) are in the exported report.")
        a.limitations.firstOrNull()?.let { KeyCaveat(it, color = Film.boneDim) }
        if (a.limitations.size > 1) CollapsibleCard("What this view cannot see", glyph = Glyph.INFO) { a.limitations.forEach { Note("• $it") } }
    }
}

@Composable
private fun FindingCard(f: NetFinding) {
    CollapsibleCard(
        f.title,
        summary = "${f.destination} · ${phaseLabel(f.phase)} · ${f.action.label}",
        summaryColor = riskColor(f.severity),
        accent = riskColor(f.severity),
        glyph = Glyph.WATCHER,
    ) {
        Text(f.whatHappened, style = MaterialTheme.typography.bodyMedium, color = Film.bone)
        Note("Why it matters: ${f.whyItMatters}")
        f.evidence.forEach { Note("• $it") }
        Note("Confidence: ${confLabel(f.confidence)}. ${f.limitation}", Film.warn)
        if (f.source == FindingSource.MODEL_ASSISTED) Note("Explanation worded by the on-device model; the facts above come from the captured traffic.", Film.boneDim)
    }
}

private const val MAX_SHOWN = 5

private fun summaryLine(a: NetAssessment): String = when {
    a.level == RiskLevel.UNKNOWN -> "Not enough connection data to judge this app."
    a.findings.isEmpty() -> "No suspicious connections in the captured traffic."
    else -> "${a.findings.size} suspicious finding(s), most serious first."
}

private fun riskLabel(a: NetAssessment) = when (a.level) {
    RiskLevel.HIGH -> "High risk"; RiskLevel.MEDIUM -> "Medium risk"; RiskLevel.LOW -> "Low risk"; RiskLevel.CLEAN -> "Nothing found"; RiskLevel.UNKNOWN -> "Unknown"
}

private fun riskColor(l: RiskLevel): Color = when (l) {
    RiskLevel.HIGH -> Film.leak; RiskLevel.MEDIUM -> Film.warn; RiskLevel.CLEAN -> Film.clear; else -> Film.boneDim
}

private fun confLabel(c: NetConfidence) = c.name.lowercase().replaceFirstChar { it.uppercase() }

private fun phaseLabel(p: Phase) = when (p) {
    Phase.BEFORE_CONSENT -> "before choice"; Phase.AFTER_ACCEPT -> "after Accept"; Phase.AFTER_REJECT -> "after Reject"; Phase.NO_CONSENT_SEEN -> "no consent seen"
}

private fun kb(b: Long) = if (b < 1024) "$b B" else "%.1f KB".format(b / 1024.0)
