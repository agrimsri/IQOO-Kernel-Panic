package com.dpdpxray.app.netproof

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.ui.components.ChipFlow
import com.dpdpxray.app.ui.components.KeyCaveat
import com.dpdpxray.app.ui.components.LearnMore
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.RuleRow
import com.dpdpxray.app.ui.components.Tag
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.FilmExtra
import com.dpdpxray.app.ui.theme.Radii
import java.util.Locale

/**
 * Measured upload card for Results or the report. Build [proof] with [NetworkProofCalculator.compute].
 * The three figures, the status sentence and "an estimate, not proof" stay visible; every line of
 * [NetworkProofText] (formula, tolerance, limits) is one tap away, word for word.
 */
@Composable
fun NetworkProofCard(proof: NetworkProof, modifier: Modifier = Modifier) {
    val status = (proof as? NetworkProof.Measured)?.status
    val color = when (status) {
        ProofStatus.WITHIN_TOLERANCE -> Film.clear
        ProofStatus.ABOVE_TOLERANCE -> Film.leak
        ProofStatus.INCONSISTENT, ProofStatus.UNCERTAIN -> Film.high
        null -> Film.boneDim
    }
    val chip = when (status) {
        ProofStatus.WITHIN_TOLERANCE -> "Within tolerance"
        ProofStatus.ABOVE_TOLERANCE -> "Above tolerance"
        ProofStatus.INCONSISTENT -> "Counters disagree"
        ProofStatus.UNCERTAIN -> "Uncertain"
        null -> "Unavailable"
    }
    val lines = NetworkProofText.lines(proof)
    RuleRow(color, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Network Proof", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Tag(chip, color)
        }
        when (proof) {
            is NetworkProof.Unavailable -> {
                lines.dropLast(1).forEach { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
                Note(lines.last())
            }
            is NetworkProof.Measured -> {
                ChipFlow(Modifier.padding(top = 10.dp)) {
                    Metric(bytes(proof.totalTxBytes), "TraceVeil's upload")
                    Metric(bytes(proof.dnsBytesOnWire), "DNS relayed")
                    Metric(bytes(proof.remainderBytes), "remainder (± ${n(proof.toleranceBytes)})", color)
                }
                // The status sentence: the second-to-last line of the card wording.
                Text(lines[lines.size - 2], style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
                KeyCaveat("The remainder is an estimate, not proof.", Modifier.padding(top = 4.dp), color = Film.boneDim)
                LearnMore(label = "Figures and limits", openLabel = "Hide figures and limits") {
                    lines.dropLast(2).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Note(lines.last())
                }
            }
        }
    }
}

private fun n(v: Long) = String.format(Locale.US, "%,d", v)

private fun bytes(v: Long) = "${n(v)} bytes"

/** A measured figure as a small tile: the number large, its meaning underneath. Read as one item by TalkBack. */
@Composable
private fun Metric(value: String, label: String, color: Color = Film.bone) {
    Column(
        Modifier.clip(RoundedCornerShape(Radii.small)).background(FilmExtra.raisedHigh.copy(alpha = 0.7f))
            .padding(horizontal = 12.dp, vertical = 8.dp).semantics(mergeDescendants = true) {},
    ) {
        Text(value, style = MaterialTheme.typography.titleSmall, color = color)
        Text(label, style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
    }
}
