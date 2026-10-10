package com.dpdpxray.app.ui.screens

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.clip
import com.dpdpxray.app.ui.components.Callout
import com.dpdpxray.app.ui.components.CollapsibleCard
import com.dpdpxray.app.ui.components.Hairline
import com.dpdpxray.app.ui.components.IconBadge
import com.dpdpxray.app.ui.components.LearnMore
import com.dpdpxray.app.ui.components.SectionCard
import com.dpdpxray.app.ui.components.SwitchRow
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.theme.Radii
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.ai.BackendKind
import com.dpdpxray.app.ai.AnswerCheckReport
import com.dpdpxray.app.ai.LlmStatus
import com.dpdpxray.app.ai.ModelFiles
import com.dpdpxray.app.ui.CameraCheckState
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ui.components.CodeBlock
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.PrimaryAction
import com.dpdpxray.app.ui.components.RuleRow
import com.dpdpxray.app.ui.components.SecondaryAction
import com.dpdpxray.app.ui.components.SectionTitle
import com.dpdpxray.app.ui.components.TopBar
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.voice.ApiEnv
import com.dpdpxray.app.voice.SpeechOutputChoice
import com.dpdpxray.app.voice.wake.HeyTraceVeilSection
import com.dpdpxray.core.model.RejectOption
import com.dpdpxray.core.model.ScreenAudit
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun AiLabScreen(vm: XrayViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val status by vm.llmStatus.collectAsState()
    val bench by vm.bench.collectAsState()
    val running by vm.benchRunning.collectAsState()
    val push = "adb push gemma-4-E2B-it.litertlm ${ModelFiles.dir(ctx).absolutePath}/"

    Column(Modifier.fillMaxSize()) {
        TopBar("AI lab", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val statusColor = when (status) {
                is LlmStatus.Ready -> Film.clear
                is LlmStatus.Loading -> Film.scan
                LlmStatus.NotLoaded -> Film.warn
                LlmStatus.NoModel -> Film.boneDim
                is LlmStatus.Failed -> Film.leak
            }
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Glyph.AI, statusColor, size = 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("On-device model", style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
                        Text(
                            when (val s = status) {
                                is LlmStatus.Ready -> "${s.model} is loaded on ${s.backend.label} (${s.loadMs / 1000.0} s to load)."
                                is LlmStatus.Loading -> "Loading on ${s.backend.label}…"
                                LlmStatus.NotLoaded -> "A model is installed but not loaded."
                                LlmStatus.NoModel -> "No model installed yet."
                                is LlmStatus.Failed -> "Last load failed: ${s.message}"
                            },
                            style = MaterialTheme.typography.bodyLarge, color = if (status is LlmStatus.Failed) Film.leak else Film.bone,
                        )
                    }
                }
            }

            SectionCard(title = "Load the model", glyph = Glyph.PLAY) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackendKind.entries.forEach { k -> SecondaryAction(k.name, { vm.loadModel(k) }, Modifier.weight(1f), enabled = status !is LlmStatus.Loading) }
                }
                TextButton(onClick = { vm.unloadModel() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Unload to free memory", color = Film.boneDim) }
            }

            SectionCard(title = "Speed on this phone", glyph = Glyph.CLOCK) {
                Note("Runs LiteRT-LM's built-in benchmark on each processor.")
                LearnMore(label = "About the NPU") {
                    Note("NPU needs a bundle compiled for this phone's chip (its file name must name it, e.g. sm8850); the GPU model is never used in its place.")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackendKind.entries.forEach { k ->
                        SecondaryAction(if (running == k) "Running…" else k.name, { vm.runBench(k) }, Modifier.weight(1f), enabled = running == null)
                    }
                }
                bench.sortedBy { it.backend.ordinal }.forEach { r ->
                    Spacer(Modifier.height(8.dp))
                    RuleRow(if (r.ok) Film.clear else Film.leak) {
                        Text(r.backend.label, style = MaterialTheme.typography.titleMedium)
                        if (r.ok) Text(
                            String.format(Locale.US, "%.1f tokens/s writing, %.0f tokens/s reading, first token in %.2f s, load %.1f s", r.decodeTps, r.prefillTps, r.ttftSeconds, r.initSeconds),
                            style = MaterialTheme.typography.bodyMedium, color = Film.boneDim,
                        ) else Text(r.error ?: "Failed", style = MaterialTheme.typography.bodyMedium, color = Film.leak)
                    }
                }
                val headroom = vm.thermalHeadroom()
                if (!headroom.isNaN()) {
                    Spacer(Modifier.height(8.dp))
                    Note(String.format(Locale.US, "Thermal headroom: %.2f (1.0 means the phone is about to throttle)", headroom))
                }
            }

            SectionCard(title = "Answer check", glyph = Glyph.CHECK) {
                Note("Sends fixed test prompts (no audit data) with the app's current settings and with the old ones, shows what came back, and says which setting breaks answers on this phone. Thinking text is never shown.")
                val lastProblem by vm.graph.llm.lastProblem.collectAsState()
                lastProblem?.let { Callout("Last problem: $it", Modifier.padding(top = 8.dp), color = Film.high) }
                val scope = rememberCoroutineScope()
                var diag by remember { mutableStateOf<AnswerCheckReport?>(null) }
                var diagRunning by remember { mutableStateOf(false) }
                SecondaryAction(
                    if (diagRunning) "Checking…" else "Run answer check",
                    { scope.launch { diagRunning = true; diag = vm.graph.llm.diagnose(); diagRunning = false } },
                    Modifier.fillMaxWidth().padding(top = 10.dp), enabled = !diagRunning && status is LlmStatus.Ready,
                )
                if (status !is LlmStatus.Ready) Note("Load the model first.")
                diag?.let { report ->
                    Spacer(Modifier.height(8.dp))
                    report.conclusions.forEach { Note(it, Film.scan) }
                    report.rows.forEach { r ->
                        Spacer(Modifier.height(8.dp))
                        RuleRow(if (r.ok) Film.clear else Film.leak) {
                            Text(r.label, style = MaterialTheme.typography.titleMedium)
                            Text(r.summary, style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
                        }
                    }
                    if (report.modelInfo.isNotBlank()) CodeBlock(report.modelInfo, Modifier.padding(top = 8.dp))
                }
            }

            SectionCard(title = "Legal search", glyph = Glyph.BOOK) {
                Note(if (vm.graph.semanticSearch) "Semantic search with the on-device embedding model is active." else "Keyword search (BM25) over the DPDP Act and Rules. Add an EmbeddingGemma model to the folder for semantic search.")
            }

            CollapsibleCard("Install models", summary = "Copy model files into this folder from your laptop. No root needed.", glyph = Glyph.APK) {
                CodeBlock(ModelFiles.describe(ctx))
                CodeBlock(push)
                TextButton(onClick = { vm.copy("adb", push) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy command", color = Film.scan) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun CameraCheckScreen(vm: XrayViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val state by vm.cameraResult.collectAsState()
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp -> bmp?.let { vm.cameraCheck(it) } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { u ->
            runCatching { ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, u)) { d, _, _ -> d.isMutableRequired = true } }
                .getOrNull()?.let { vm.cameraCheck(it.copy(android.graphics.Bitmap.Config.ARGB_8888, false)) }
        }
    }
    LaunchedEffect(Unit) { vm.resetCamera() }

    Column(Modifier.fillMaxSize()) {
        TopBar("Camera check", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Glyph.CAMERA, Film.scan, size = 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Text("Check a consent screen from a photo", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                }
                Text(
                    "Point the camera at any app's consent screen, even on someone else's phone. On-device AI checks it against DPDP consent rules in seconds.",
                    style = MaterialTheme.typography.bodyMedium, color = Film.boneDim, modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
                )
                PrimaryAction("Take a photo", { takePhoto.launch(null) }, enabled = state !is CameraCheckState.Working)
                SecondaryAction("Choose a screenshot", { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.fillMaxWidth().padding(top = 10.dp))
            }
            when (val s = state) {
                CameraCheckState.Working -> Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Film.scan, strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Note("Reading the screen on this phone…", Film.scan)
                }
                is CameraCheckState.Error -> Callout(s.message, Modifier.padding(top = 16.dp), color = Film.leak)
                is CameraCheckState.Done -> {
                    val bmp = remember(s.imagePath) { BitmapFactory.decodeFile(s.imagePath)?.asImageBitmap() }
                    if (bmp != null) Image(
                        bmp, "Photo that was checked", contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(max = 300.dp).clip(RoundedCornerShape(Radii.tile)),
                    )
                    Verdict(s.audit)
                }
                CameraCheckState.Idle -> {}
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Verdict(a: ScreenAudit) {
    SectionTitle(if (a.isConsentScreen) "What the AI sees" else "This does not look like a consent screen")
    if (!a.isConsentScreen) return
    VerdictLine("Option switched on before you choose", a.preTicked == true, a.preTicked)
    VerdictLine(
        when (a.rejectOption) { RejectOption.VISIBLE -> "Reject is as easy as Accept"; RejectOption.HIDDEN -> "Reject is hidden behind another step"; RejectOption.NONE -> "No way to refuse"; RejectOption.UNKNOWN -> "Reject option unclear" },
        a.rejectOption == RejectOption.HIDDEN || a.rejectOption == RejectOption.NONE, a.rejectOption != RejectOption.UNKNOWN,
    )
    VerdictLine("One button covers several purposes", (a.purposesCount ?: 0) >= 2 && a.separateChoices == false, a.separateChoices)
    VerdictLine("Lists exactly which data is collected", a.itemisedData == false, a.itemisedData, inverted = true)
    VerdictLine("Explains how to withdraw consent", a.withdrawalInfo == false, a.withdrawalInfo, inverted = true)
    a.languageOptions?.let { Note("Languages offered: ${it.ifEmpty { listOf("none shown") }.joinToString()}") }
    if (a.darkPatterns.isNotEmpty()) Note("Dark patterns: ${a.darkPatterns.joinToString()}", Film.high)
    if (a.summary.isNotBlank()) Text(a.summary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
    Note(String.format(Locale.US, "Model confidence %.0f%%%s", a.confidence * 100, a.latencyMs?.let { ", ${it / 1000.0} s on device" } ?: ""))
}

@Composable
private fun VerdictLine(text: String, problem: Boolean, known: Any?, inverted: Boolean = false) {
    val color = when {
        known == null -> Film.boneDim
        problem -> Film.leak
        else -> Film.clear
    }
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (known == null) "?" else if (problem) "✕" else "✓", color = color, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 10.dp))
        Text(if (inverted && problem) "Does not: ${text.lowercase()}" else text, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

@Composable
fun SettingsScreen(vm: XrayViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopBar("Settings", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SectionCard(title = "Display", glyph = Glyph.WATCHER) {
                SwitchRow("Presentation mode", "Larger text for projectors and screen sharing.", s.stageMode, { v -> vm.updateSettings { it.copy(stageMode = v) } })
            }
            SectionCard(title = "Checks", glyph = Glyph.SEARCH) {
                SwitchRow("Prefer Fast Preview", "Defaults single-run checks to the shorter, clearly labelled Preview. Full audits keep normal timing; choose the mode in audit setup.", s.fastDemo, { v -> vm.updateSettings { it.copy(fastDemo = v) } })
                Hairline()
                SwitchRow("Load AI when an audit starts", "Loads Gemma automatically if it is installed.", s.autoLoadModel, { v -> vm.updateSettings { it.copy(autoLoadModel = v) } })
            }
            SectionCard(title = "Voice and guidance", glyph = Glyph.SPEAKER_ON) {
                SwitchRow("Spoken guidance", "Reads each guide step aloud with an installed offline voice. Off by default; nothing leaves the phone.", s.spokenGuidance, { v -> vm.updateSettings { it.copy(spokenGuidance = v) } })
                Hairline()
                SwitchRow("Speak APK Guard alerts", "Reads new-install alerts aloud with an installed offline voice. Off by default.", s.speakApkGuardAlerts, { v -> vm.updateSettings { it.copy(speakApkGuardAlerts = v) } })
            }

            SectionCard(title = "Your own domains", glyph = Glyph.PRIVATE_DNS) {
                if (s.firstPartyDomains.isEmpty()) Note("None yet. Long-press a host on any timeline to add it.")
                s.firstPartyDomains.sorted().forEach { d ->
                    Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(d, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.updateSettings { it.copy(firstPartyDomains = it.firstPartyDomains - d) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove", color = Film.leak) }
                    }
                }
            }

            SectionCard(title = "Hey TraceVeil (experimental)", glyph = Glyph.ASK) {
                HeyTraceVeilSection(s.heyTraceVeil, { v -> vm.updateSettings { it.copy(heyTraceVeil = v) } }, showTitle = false)
            }

            SectionCard(title = "Privacy", glyph = Glyph.LOCK, accent = Film.clear) {
                val ctx = LocalContext.current
                val onlineVoice = remember { SpeechOutputChoice.choose(ApiEnv.load(ctx)) == SpeechOutputChoice.ONLINE }
                Note(
                    (if (onlineVoice) "Apart from the online voice described below, TraceVeil uploads nothing. " else "TraceVeil uploads nothing. ") +
                        "Its other network use is relaying the audited app's own DNS lookups during an audit, and downloads you start " +
                        "yourself (the optional AI and wake-word models). " +
                        "Reports, screenshots and AI analysis stay on this phone until you export them.",
                )
                if (onlineVoice) Callout(
                    "Online voice is switched on for this build: Voice Copilot sends each spoken answer's text to Sarvam AI to make speech. " +
                        "Speech recognition and answers still stay on this phone.",
                    Modifier.padding(top = 10.dp),
                    color = Film.high,
                )
            }
            SectionCard(title = "Not legal advice", glyph = Glyph.BOOK) {
                Note("Results are an automated technical assessment with citations to the DPDP Act 2023 and DPDP Rules 2025.")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
