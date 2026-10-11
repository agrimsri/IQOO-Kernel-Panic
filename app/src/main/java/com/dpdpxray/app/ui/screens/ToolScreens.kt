package com.dpdpxray.app.ui.screens

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.ai.AnswerCheckReport
import com.dpdpxray.app.ai.BackendKind
import com.dpdpxray.app.ai.LlmStatus
import com.dpdpxray.app.ai.ModelFiles
import com.dpdpxray.app.autoguard.AutoGuardSettingsCard
import com.dpdpxray.app.automation.DeveloperAutomationCard
import com.dpdpxray.app.npu.ConsentLensCard
import com.dpdpxray.app.ui.CameraCheckState
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ui.components.SwitchRow
import com.dpdpxray.app.ui.components.BreathingDot
import com.dpdpxray.app.ui.components.Callout
import com.dpdpxray.app.ui.components.ChipFlow
import com.dpdpxray.app.ui.components.CodeBlock
import com.dpdpxray.app.ui.components.CollapsibleCard
import com.dpdpxray.app.ui.components.DisclosureRow
import com.dpdpxray.app.ui.components.FoldText
import com.dpdpxray.app.ui.components.GroupHeader
import com.dpdpxray.app.ui.components.Hairline
import com.dpdpxray.app.ui.components.IconBadge
import com.dpdpxray.app.ui.components.KeyCaveat
import com.dpdpxray.app.ui.components.LearnMore
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.RuleRow
import com.dpdpxray.app.ui.components.SecondaryAction
import com.dpdpxray.app.ui.components.SectionCard
import com.dpdpxray.app.ui.components.SectionTitle
import com.dpdpxray.app.ui.components.SkeletonLines
import com.dpdpxray.app.ui.components.Tag
import com.dpdpxray.app.ui.components.TopBar
import com.dpdpxray.app.ui.components.cardSurface
import com.dpdpxray.app.ui.components.foldSpec
import com.dpdpxray.app.ui.components.leadSentence
import com.dpdpxray.app.ui.components.reveal
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.GradientAction
import com.dpdpxray.app.ui.guide.OutlineAction
import com.dpdpxray.app.ui.guide.Panel
import com.dpdpxray.app.ui.modes.ModeCopy
import com.dpdpxray.app.ui.modes.ModeSwitch
import com.dpdpxray.app.ui.theme.Brand
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.FilmExtra
import com.dpdpxray.app.ui.theme.Radii
import com.dpdpxray.app.voice.ApiEnv
import com.dpdpxray.app.voice.SpeechOutputChoice
import com.dpdpxray.app.voice.wake.HeyTraceVeilCard
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
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val statusColor = when (status) {
                is LlmStatus.Ready -> Film.clear
                is LlmStatus.Loading -> Film.scan
                LlmStatus.NotLoaded -> Film.warn
                LlmStatus.NoModel -> Film.boneDim
                is LlmStatus.Failed -> Film.leak
            }
            ModelStatusCard(status, statusColor, Modifier.reveal(0))

            SectionCard(Modifier.reveal(1), title = "Load the model", glyph = Glyph.PLAY) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackendKind.entries.forEach { k -> SecondaryAction(k.name, { vm.loadModel(k) }, Modifier.weight(1f), enabled = status !is LlmStatus.Loading) }
                }
                TextButton(onClick = { vm.unloadModel() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Unload to free memory", color = Film.boneDim) }
            }

            SectionCard(Modifier.reveal(2), title = "Speed on this phone", glyph = Glyph.CLOCK) {
                Note("Runs LiteRT-LM's built-in benchmark on each processor.")
                LearnMore(label = "About the NPU") {
                    Note("NPU needs a bundle compiled for this phone's chip (its file name must name it, e.g. sm8850); the GPU model is never used in its place.")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackendKind.entries.forEach { k ->
                        SecondaryAction(if (running == k) "Running…" else k.name, { vm.runBench(k) }, Modifier.weight(1f), enabled = running == null)
                    }
                }
                running?.let { k -> SkeletonLines(2, Modifier.padding(top = 12.dp), label = "Running the ${k.label} benchmark") }
                // With two or more good runs, the quickest writer gets a chip so the comparison reads at a glance.
                val fastest = remember(bench) { bench.filter { it.ok }.takeIf { it.size > 1 }?.maxByOrNull { it.decodeTps }?.backend }
                bench.sortedBy { it.backend.ordinal }.forEach { r ->
                    Spacer(Modifier.height(8.dp))
                    RuleRow(if (r.ok) Film.clear else Film.leak) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.backend.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (r.backend == fastest) Tag("Fastest", Film.clear)
                        }
                        if (r.ok) {
                            ChipFlow(Modifier.padding(top = 10.dp)) {
                                Metric(String.format(Locale.US, "%.1f", r.decodeTps), "tokens/s writing", Film.clear)
                                Metric(String.format(Locale.US, "%.0f", r.prefillTps), "tokens/s reading")
                                Metric(String.format(Locale.US, "%.2f s", r.ttftSeconds), "first token")
                                Metric(String.format(Locale.US, "%.1f s", r.initSeconds), "load")
                            }
                        } else {
                            Text(r.error ?: "Failed", style = MaterialTheme.typography.bodyMedium, color = Film.leak)
                        }
                    }
                }
                val headroom = vm.thermalHeadroom()
                if (!headroom.isNaN()) {
                    Spacer(Modifier.height(8.dp))
                    Note(String.format(Locale.US, "Thermal headroom: %.2f (1.0 means the phone is about to throttle)", headroom))
                }
            }

            ConsentLensCard(vm.graph.consentLens, Modifier.reveal(3))

            SectionCard(Modifier.reveal(4), title = "Answer check", glyph = Glyph.CHECK) {
                FoldText("Sends fixed test prompts (no audit data) with the app's current settings and with the old ones, shows what came back, and says which setting breaks answers on this phone. Thinking text is never shown.")
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
                if (status !is LlmStatus.Ready) KeyCaveat("Load the model first.", color = Film.boneDim)
                if (diagRunning) {
                    SkeletonLines(3, Modifier.padding(top = 12.dp), label = "Checking answers")
                } else diag?.let { report ->
                    Spacer(Modifier.height(8.dp))
                    report.conclusions.forEach { KeyCaveat(it, color = Film.scan) }
                    report.rows.forEach { r ->
                        Spacer(Modifier.height(8.dp))
                        RuleRow(if (r.ok) Film.clear else Film.leak) {
                            Text(r.label, style = MaterialTheme.typography.titleMedium)
                            Text(r.summary, style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
                        }
                    }
                    if (report.modelInfo.isNotBlank()) LearnMore(Modifier.padding(top = 4.dp), label = "Model details") { CodeBlock(report.modelInfo) }
                }
            }

            val semantic = vm.graph.semanticSearch
            SectionCard(
                Modifier.reveal(5), title = "Legal search", glyph = Glyph.BOOK,
                trailing = { Tag(if (semantic) "Semantic" else "Keyword", Film.scan) },
            ) {
                FoldText(if (semantic) "Semantic search with the on-device embedding model is active." else "Keyword search (BM25) over the DPDP Act and Rules. Add an EmbeddingGemma model to the folder for semantic search.")
            }

            CollapsibleCard("Install models", Modifier.reveal(6), summary = "Copy model files into this folder from your laptop. No root needed.", glyph = Glyph.APK) {
                CodeBlock(ModelFiles.describe(ctx))
                CodeBlock(push)
                TextButton(onClick = { vm.copy("adb", push) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy command", color = Film.scan) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** The AI lab's key card: a soft glow and a corner wash in the model's status colour, a status chip and one line. */
@Composable
private fun ModelStatusCard(status: LlmStatus, color: Color, modifier: Modifier = Modifier) {
    val (chip, line) = when (val s = status) {
        is LlmStatus.Ready -> "Ready" to "${s.model} is loaded on ${s.backend.label} (${s.loadMs / 1000.0} s to load)."
        is LlmStatus.Loading -> "Loading" to "Loading on ${s.backend.label}…"
        LlmStatus.NotLoaded -> "Not loaded" to "A model is installed but not loaded."
        LlmStatus.NoModel -> "No model" to "No model installed yet."
        is LlmStatus.Failed -> "Failed" to "Last load failed: ${s.message}"
    }
    val wash = remember(color) { Brush.linearGradient(listOf(color.copy(alpha = 0.16f), Color.Transparent)) }
    Row(
        modifier.fillMaxWidth().cardSurface(glow = color).background(wash).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(Glyph.AI, color, size = 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("On-device model", style = MaterialTheme.typography.labelMedium, color = Film.boneDim, modifier = Modifier.weight(1f))
                if (status is LlmStatus.Loading) {
                    BreathingDot(color)
                    Spacer(Modifier.width(8.dp))
                }
                Tag(chip, color)
            }
            Text(
                line, style = MaterialTheme.typography.bodyLarge, color = if (status is LlmStatus.Failed) Film.leak else Film.bone,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
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
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
            // The one key card on this screen: a cool glow, a warm corner wash and the brand action.
            Column(Modifier.reveal(0).fillMaxWidth().cardSurface(glow = Film.scan).background(Brand.heroWash).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Glyph.CAMERA, Film.scan, size = 48.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(
                        "Check a consent screen from a photo", style = MaterialTheme.typography.titleLarge, color = Film.bone,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                }
                ChipFlow(Modifier.padding(top = 12.dp)) {
                    Tag("On-device AI", Film.clear)
                    Tag("DPDP consent rules", Film.scan)
                }
                FoldText(
                    "Point the camera at any app's consent screen, even on someone else's phone. On-device AI checks it against DPDP consent rules in seconds.",
                    Modifier.padding(top = 10.dp),
                )
                Spacer(Modifier.height(12.dp))
                GradientAction("Take a photo", { takePhoto.launch(null) }, enabled = state !is CameraCheckState.Working, glyph = Glyph.CAMERA)
                OutlineAction(
                    "Choose a screenshot", { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    Modifier.padding(top = 10.dp), glyph = Glyph.SAMPLE,
                )
            }
            when (val s = state) {
                CameraCheckState.Working -> Panel(Modifier.padding(top = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BreathingDot(Film.scan)
                        Spacer(Modifier.width(10.dp))
                        Note("Reading the screen on this phone…", Film.scan)
                    }
                    SkeletonLines(4, Modifier.padding(top = 14.dp), label = "Reading the screen on this phone")
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
    Panel(Modifier.reveal(0)) {
        VerdictLine("Option switched on before you choose", a.preTicked == true, a.preTicked)
        VerdictLine(
            when (a.rejectOption) { RejectOption.VISIBLE -> "Reject is as easy as Accept"; RejectOption.HIDDEN -> "Reject is hidden behind another step"; RejectOption.NONE -> "No way to refuse"; RejectOption.UNKNOWN -> "Reject option unclear" },
            a.rejectOption == RejectOption.HIDDEN || a.rejectOption == RejectOption.NONE, a.rejectOption != RejectOption.UNKNOWN,
        )
        VerdictLine("One button covers several purposes", (a.purposesCount ?: 0) >= 2 && a.separateChoices == false, a.separateChoices)
        VerdictLine("Lists exactly which data is collected", a.itemisedData == false, a.itemisedData, inverted = true)
        VerdictLine("Explains how to withdraw consent", a.withdrawalInfo == false, a.withdrawalInfo, inverted = true)
        if (a.languageOptions != null || a.darkPatterns.isNotEmpty()) Hairline(Modifier.padding(vertical = 8.dp))
        a.languageOptions?.let { Note("Languages offered: ${it.ifEmpty { listOf("none shown") }.joinToString()}") }
        if (a.darkPatterns.isNotEmpty()) {
            Text("Dark patterns", style = MaterialTheme.typography.titleSmall, color = Film.high, modifier = Modifier.padding(top = 8.dp))
            ChipFlow(Modifier.padding(top = 6.dp)) { a.darkPatterns.forEach { Tag(it, Film.high) } }
        }
        if (a.summary.isNotBlank()) Text(a.summary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
        ChipFlow(Modifier.padding(top = 12.dp)) {
            Tag(String.format(Locale.US, "Model confidence %.0f%%", a.confidence * 100), Film.scan)
            a.latencyMs?.let { Tag("${it / 1000.0} s on device", Film.clear) }
        }
    }
}

@Composable
private fun VerdictLine(text: String, problem: Boolean, known: Any?, inverted: Boolean = false) {
    val color = when {
        known == null -> Film.boneDim
        problem -> Film.leak
        else -> Film.clear
    }
    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)).border(1.dp, color.copy(alpha = 0.45f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (known == null) "?" else if (problem) "✕" else "✓", color = color, style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.width(12.dp))
        Text(if (inverted && problem) "Does not: ${text.lowercase()}" else text, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

@Composable
fun SettingsScreen(vm: XrayViewModel, onBack: () -> Unit, onCheckup: (() -> Unit)? = null) {
    val s by vm.settings.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TopBar("Settings", onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(Modifier.reveal(0), title = ModeCopy.title(s.audience).en, glyph = Glyph.HOME) {
                ModeSwitch(s.audience, { a -> vm.updateSettings { it.copy(audience = a) } })
            }
            SectionCard(Modifier.reveal(1), title = "Display", glyph = Glyph.WATCHER) {
                Text("Appearance", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                val modes = listOf("system", "light", "dark")
                com.dpdpxray.app.ui.system.SegmentedControl(
                    listOf("System", "Light", "Dark"), modes.indexOf(s.themeMode).coerceAtLeast(0),
                    { i -> vm.updateSettings { it.copy(themeMode = modes[i]) } },
                )
                Hairline(Modifier.padding(top = 12.dp))
                SwitchRow("Presentation mode", "Larger text for projectors and screen sharing.", s.stageMode, { v -> vm.updateSettings { it.copy(stageMode = v) } })
            }
            SectionCard(Modifier.reveal(2), title = "Checks", glyph = Glyph.SEARCH) {
                SwitchRow("Prefer Fast Preview", "Developer mode: defaults single-run checks to the shorter, clearly labelled Preview. Full audits keep normal timing; choose the mode in audit setup.", s.fastDemo, { v -> vm.updateSettings { it.copy(fastDemo = v) } })
                Hairline()
                SwitchRow("Load AI when an audit starts", "Loads Gemma automatically if it is installed.", s.autoLoadModel, { v -> vm.updateSettings { it.copy(autoLoadModel = v) } })
            }
            SectionCard(Modifier.reveal(3), title = "Legal lens", glyph = Glyph.BOOK) {
                LegalLensSection(vm, s.legalProfile)
            }
            SectionCard(Modifier.reveal(4), title = "Voice and guidance", glyph = Glyph.SPEAKER_ON) {
                SwitchRow("Read every step automatically", "Reads each guide step aloud as you move through TraceVeil, with an installed offline voice. Off by default: tap the speaker on any step to hear just that step; nothing leaves the phone.", s.spokenGuidance, { v -> vm.updateSettings { it.copy(spokenGuidance = v) } })
                Hairline()
                SwitchRow("Speak APK Guard alerts", "Reads new-install alerts aloud with an installed offline voice. Off by default.", s.speakApkGuardAlerts, { v -> vm.updateSettings { it.copy(speakApkGuardAlerts = v) } })
            }
            // Auto-guard: background new-app check and weekly phone checkup (switches, status, disclosure).
            AutoGuardSettingsCard(onCheckup, Modifier.reveal(5))

            SectionCard(
                Modifier.reveal(6), title = "Your own domains", glyph = Glyph.PRIVATE_DNS,
                trailing = { if (s.firstPartyDomains.isNotEmpty()) Tag("${s.firstPartyDomains.size}", Film.scan) },
            ) {
                if (s.firstPartyDomains.isEmpty()) Note("None yet. Long-press a host on any timeline to add it.")
                s.firstPartyDomains.sorted().forEach { d ->
                    Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(d, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.updateSettings { it.copy(firstPartyDomains = it.firstPartyDomains - d) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove", color = Film.leak) }
                    }
                }
            }

            GroupHeader("Advanced", Modifier.reveal(7))
            // Both start folded; each header's one-line summary says whether it is on and what, if anything, leaves the phone.
            HeyTraceVeilCard(s.heyTraceVeil, { v -> vm.updateSettings { it.copy(heyTraceVeil = v) } }, Modifier.reveal(7))
            // TraceVeil Runner (off by default): see automation/DeveloperAutomationSection.kt.
            DeveloperAutomationCard(vm, Modifier.reveal(8))

            GroupHeader("Privacy and limits", Modifier.reveal(8))
            SectionCard(Modifier.reveal(8), title = "Privacy", glyph = Glyph.LOCK, accent = Film.clear, glow = Film.clear) {
                val ctx = LocalContext.current
                val onlineVoice = remember { SpeechOutputChoice.choose(ApiEnv.load(ctx)) == SpeechOutputChoice.ONLINE }
                // The first sentence stays visible; the chips name every network use, so nothing that leaves the phone is folded away.
                FoldText(
                    (if (onlineVoice) "Apart from the online voice described below, TraceVeil uploads nothing. " else "TraceVeil uploads nothing. ") +
                        "Its other network use is relaying the audited app's own DNS lookups during an audit, and the optional " +
                        "wake-word model download you start yourself. AI models are copied from a computer, never downloaded by the app. " +
                        "Reports, screenshots and AI analysis stay on this phone until you export them.",
                    style = MaterialTheme.typography.bodyLarge, color = Film.bone,
                )
                ChipFlow(Modifier.padding(top = 4.dp)) {
                    Tag("DNS relay during audits", Film.scan)
                    Tag("Wake-word download you start", Film.scan)
                    if (onlineVoice) Tag("Online voice: Sarvam AI", Film.high)
                }
                if (onlineVoice) Callout(
                    "Online voice is switched on for this build: Voice Copilot sends each spoken answer's text to Sarvam AI to make speech.",
                    Modifier.padding(top = 12.dp),
                    color = Film.high,
                    detail = "Speech recognition and answers still stay on this phone.",
                )
            }
            BackupRestoreCard(vm, Modifier.reveal(8))
            SectionCard(Modifier.reveal(8), title = "Not legal advice", glyph = Glyph.BOOK, accent = Film.warn) {
                FoldText("Results are an automated technical assessment. Legal passages come from the selected legal lens (India's DPDP Act 2023 and Rules 2025, the EU GDPR and ePrivacy Directive, UK GDPR and PECR, California's CCPA, US COPPA, Brazil's LGPD or Singapore's PDPA) and are context, not a verdict.")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

