package com.dpdpxray.app.netproof

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.RuleRow
import com.dpdpxray.app.ui.theme.Film

/** Measured upload card for Results or the report. Build [proof] with [NetworkProofCalculator.compute]. */
@Composable
fun NetworkProofCard(proof: NetworkProof, modifier: Modifier = Modifier) {
    val color = when ((proof as? NetworkProof.Measured)?.status) {
        ProofStatus.WITHIN_TOLERANCE -> Film.clear
        ProofStatus.ABOVE_TOLERANCE -> Film.leak
        ProofStatus.INCONSISTENT, ProofStatus.UNCERTAIN -> Film.high
        null -> Film.boneDim
    }
    val lines = NetworkProofText.lines(proof)
    RuleRow(color, modifier) {
        Text("Network Proof", style = MaterialTheme.typography.titleMedium)
        lines.dropLast(1).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Note(lines.last())
    }
}
