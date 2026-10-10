package com.dpdpxray.app.npu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.ui.components.Callout
import com.dpdpxray.app.ui.components.ChipFlow
import com.dpdpxray.app.ui.components.FoldText
import com.dpdpxray.app.ui.components.LearnMore
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.RuleRow
import com.dpdpxray.app.ui.components.SecondaryAction
import com.dpdpxray.app.ui.components.SectionCard
import com.dpdpxray.app.ui.components.SkeletonLines
import com.dpdpxray.app.ui.components.Tag
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.FilmExtra
import com.dpdpxray.app.ui.theme.Radii
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * AI lab card for ConsentLens-1: Load (shows which hardware really runs it and why), Benchmark (median/p95 per string,
 * NPU vs CPU), and Try samples (fixed multilingual phrases with the model's role and confidence).
 */
@Composable
fun ConsentLensCard(engine: ConsentLensEngine, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(engine.status) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var bench by remember { mutableStateOf<ConsentLensEngine.BenchReport?>(null) }
    var samples by remember { mutableStateOf<List<ConsentLensEngine.Sample>>(emptyList()) }

    fun work(label: String, block: () -> Unit) {
        scope.launch {
            busy = label
            error = null
            runCatching { withContext(Dispatchers.Default) { block() } }.onFailure { error = "${it.javaClass.simpleName}: ${it.message}" }
            status = engine.status
            busy = null
        }
    }

    SectionCard(
        modifier, title = "ConsentLens (NPU)", glyph = Glyph.AI,
        trailing = { status?.let { s -> if (s.backend == ConsentLensEngine.Backend.NPU) Tag("NPU", Film.clear) else Tag("CPU", Film.warn) } },
    ) {
        FoldText("TraceVeil's own model labels one consent-screen string as Accept, Reject, Manage, purpose notice, privacy link, withdrawal info or other, in many languages. Its labels are AI-inferred evidence; the rules decide.")
        val meta = runCatching { engine.meta }.getOrNull()
        if (meta != null) {
            ChipFlow(Modifier.padding(top = 4.dp)) {
                Tag(meta.modelId, Film.scan)
                Tag(String.format(Locale.US, "%,d-byte INT8 TFLite", meta.bytes), Film.boneDim)
                Tag("${meta.opCount} ops", Film.boneDim)
                Tag("${meta.languages} languages in training", Film.boneDim)
            }
            Text(
                String.format(Locale.US, "Held-out-language macro-F1 %.2f (lexicon %.2f), from the training report", meta.heldOutMacroF1, meta.heldOutLexiconMacroF1),
                style = MaterialTheme.typography.labelMedium, color = Film.boneDim, modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryAction(if (busy == "load") "Loading…" else "Load", { work("load") { engine.load(interactive = true) } }, Modifier.weight(1f), enabled = busy == null)
            SecondaryAction(if (busy == "bench") "Running…" else "Benchmark", { work("bench") { bench = engine.benchmark(1000) } }, Modifier.weight(1f), enabled = busy == null)
            SecondaryAction(if (busy == "samples") "Running…" else "Try samples", { work("samples") { samples = engine.samples() } }, Modifier.weight(1f), enabled = busy == null)
        }
        if (busy == "bench" || busy == "samples") SkeletonLines(2, Modifier.padding(top = 12.dp), label = if (busy == "bench") "Running the benchmark" else "Running the samples")
        error?.let { Callout(it, Modifier.padding(top = 8.dp), color = Film.leak) }

        status?.let { s ->
            Spacer(Modifier.height(10.dp))
            val npu = s.backend == ConsentLensEngine.Backend.NPU
            RuleRow(if (npu) Film.clear else Film.warn) {
                Text(if (npu) "Running on the NPU" else "CPU fallback", style = MaterialTheme.typography.titleMedium)
                ChipFlow(Modifier.padding(top = 8.dp)) {
                    Tag(s.label, if (npu) Film.clear else Film.warn)
                    Tag("SoC ${s.socModel.ifBlank { "unknown" }}", Film.boneDim)
                    s.qnnVersion?.let { Tag("QNN $it", Film.boneDim) }
                    Tag("loaded in ${s.loadMs} ms", Film.boneDim)
                }
                val plan = s.planNodes?.let { "$it of ${s.modelOps} ops in the execution plan" } ?: "execution plan unavailable"
                Text(
                    when {
                        npu && s.fullyDelegated == true -> "All ${s.modelOps} ops delegated to QNN HTP (plan is 1 delegate node); nothing ran on the CPU."
                        npu && s.fullyDelegated == false -> "Partly delegated: $plan, so some ops still run on the CPU."
                        npu -> "Delegated to QNN HTP; $plan."
                        else -> "Reason: ${s.fallbackReason ?: s.policyReason}"
                    },
                    style = MaterialTheme.typography.bodyMedium, color = if (npu && s.fullyDelegated != false) Film.clear else Film.warn,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (!npu && s.fallbackReason?.contains("previous NPU start") == true) {
                SecondaryAction("Retry NPU", { work("load") { engine.load(retryNpu = true, interactive = true) } }, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = busy == null)
            }
        }

        bench?.let { b ->
            Spacer(Modifier.height(10.dp))
            RuleRow(Film.scan) {
                Text("Speed on this phone", style = MaterialTheme.typography.titleMedium)
                listOfNotNull(b.active, b.cpu).forEach { t -> TimingRow(t) }
                b.npuCpuAgree?.let { Note("NPU and CPU give the same role for $it of ${b.samples} sample phrases.") }
                Note("Matches the Python INT8 reference for ${b.matchesReference} of ${b.samples} sample phrases.")
                if (b.active == null) Note("The NPU is not running, so only the CPU was measured.")
            }
        }

        if (samples.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FoldText("Sample phrases were written for this card (not real app screens). Expected = our label; reference = the same INT8 model in Python.")
            samples.forEach { s ->
                Spacer(Modifier.height(6.dp))
                val right = s.prediction.role.name == s.demo.expected
                RuleRow(if (right) Film.clear else Film.high) {
                    Text("${s.demo.lang.uppercase(Locale.ROOT)} · ${s.demo.text}", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        String.format(Locale.US, "%s %.0f%% · expected %s · reference %s%s", s.prediction.role, 100 * s.prediction.confidence,
                            s.demo.expected, s.demo.referenceRole, if (s.demo.inTrainingData) " · also in training data" else ""),
                        style = MaterialTheme.typography.bodyMedium, color = Film.boneDim,
                    )
                }
            }
        }

        LearnMore(label = "How to check it really ran on the NPU") {
            Note("Run Load, then Benchmark, and read logcat tag TraceVeilNpu: backend=NPU with planNodes=1/${meta?.opCount ?: "N"} and fullyDelegated=true means LiteRT handed the whole graph to the Qualcomm QNN HTP delegate (the NPU interpreter has XNNPACK switched off, so a CPU fallback would show more plan nodes or backend=CPU). LiteRT normally also logs \"Replacing N out of N node(s) with delegate …\" when the delegate is applied.")
        }
    }
}

/** One backend's timing as figures: median and p95 per string, and how many runs they come from. */
@Composable
private fun TimingRow(t: ConsentLensEngine.Timing) {
    Text(t.backend, style = MaterialTheme.typography.labelLarge, color = Film.bone, modifier = Modifier.padding(top = 10.dp))
    ChipFlow(Modifier.padding(top = 6.dp)) {
        Metric(String.format(Locale.US, "%.3f ms", t.medianMs), "median per string", Film.clear)
        Metric(String.format(Locale.US, "%.3f ms", t.p95Ms), "p95 per string")
        Metric(String.format(Locale.US, "%,d", t.runs), "runs")
    }
}

/** A measured figure as a small tile: the number large, its unit and meaning underneath. Read as one item by TalkBack. */
@Composable
private fun Metric(value: String, label: String, color: Color = Film.bone) {
    Column(
        Modifier.clip(RoundedCornerShape(Radii.small)).background(FilmExtra.raisedHigh.copy(alpha = 0.7f))
            .padding(horizontal = 12.dp, vertical = 8.dp).semantics(mergeDescendants = true) {},
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = color)
        Text(label, style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
    }
}
