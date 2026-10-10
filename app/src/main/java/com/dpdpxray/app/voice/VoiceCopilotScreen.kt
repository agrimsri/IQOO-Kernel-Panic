package com.dpdpxray.app.voice

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpdpxray.app.ui.components.BreathingDot
import com.dpdpxray.app.ui.components.ChipFlow
import com.dpdpxray.app.ui.components.FoldText
import com.dpdpxray.app.ui.components.Hairline
import com.dpdpxray.app.ui.components.IconBadge
import com.dpdpxray.app.ui.components.KeyCaveat
import com.dpdpxray.app.ui.components.LearnMore
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.RecordingDot
import com.dpdpxray.app.ui.components.RuleRow
import com.dpdpxray.app.ui.components.SecondaryAction
import com.dpdpxray.app.ui.components.SectionTitle
import com.dpdpxray.app.ui.components.SkeletonLines
import com.dpdpxray.app.ui.components.SwitchRow
import com.dpdpxray.app.ui.components.Tag
import com.dpdpxray.app.ui.components.TopBar
import com.dpdpxray.app.ui.components.cardSurface
import com.dpdpxray.app.ui.components.pressClickable
import com.dpdpxray.app.ui.components.reveal
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.GlyphIcon
import com.dpdpxray.app.ui.guide.GradientAction
import com.dpdpxray.app.ui.theme.Brand
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.FilmExtra
import com.dpdpxray.app.ui.theme.LocalReduceMotion
import com.dpdpxray.app.ui.theme.Motion
import com.dpdpxray.core.copilot.CopilotLanguage
import com.dpdpxray.core.copilot.CopilotQuestions

/**
 * Voice Copilot: ask one saved audit a question by push-to-talk or by tapping a question. Mount with
 * `composable("voice/{id}") { e -> VoiceCopilotScreen(e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() }) }`.
 */
@Composable
fun VoiceCopilotScreen(auditId: String, onBack: () -> Unit, vm: VoiceViewModel = viewModel()) {
    val s by vm.state.collectAsState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.onPermissionResult(it) }
    LaunchedEffect(auditId) { vm.load(auditId) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> vm.pause()
                Lifecycle.Event.ON_RESUME -> vm.refreshMic()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            vm.pause()
        }
    }

    val hindi = s.language == CopilotLanguage.HI
    Column(Modifier.fillMaxSize()) {
        TopBar("Ask this audit", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
            when {
                s.loading -> {
                    Note("Opening the audit…")
                    SkeletonLines(3, Modifier.padding(top = 12.dp), label = "Opening the audit")
                    return@Column
                }
                s.missing -> return@Column Note("This audit could not be opened.", Film.leak)
            }

            // The key card: which audit, which language, and the microphone. It glows red while the microphone listens.
            Column(
                Modifier.reveal(0).fillMaxWidth().cardSurface(glow = if (s.listening) Film.leak else Film.scan)
                    .background(Brand.heroWash).padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconBadge(Glyph.ASK, Brand.coral, size = 44.dp)
                    Text(s.label, style = MaterialTheme.typography.titleLarge, color = Film.bone, modifier = Modifier.weight(1f))
                    if (s.sample) Tag("SAMPLE", Film.high)
                }
                LanguageSwitch(s.language, vm::setLanguage, Modifier.padding(top = 14.dp))

                SectionTitle("Ask or tell it what to do")
                when (val mic = s.mic) {
                    MicState.Ready -> GradientAction(
                        if (s.listening) "Listening… tap to stop" else "Tap to speak", { vm.toggleListening() },
                        glyph = if (s.listening) null else Glyph.ASK,
                    )
                    MicState.NeedsPermission -> GradientAction("Allow the microphone", { permission.launch(Manifest.permission.RECORD_AUDIO) })
                    is MicState.Unavailable -> {
                        GradientAction("Microphone unavailable", {}, enabled = false)
                        Note(mic.reason, Film.high)
                    }
                }
                // Privacy stays in view; while the microphone is open a red dot breathes beside it.
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (s.listening) RecordingDot(Film.leak, dot = 8.dp) else GlyphIcon(Glyph.LOCK, Film.clear, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Note("Speech is recognised on this phone only. Nothing is recorded or kept.", Film.clear)
                }
            }

            Spacer(Modifier.height(16.dp))
            Note(
                if (hindi) "जैसे: “इंस्टाग्राम की जाँच करो”, “क्या मिला?”, “रिपोर्ट खोलो”।" else "Try: “Check Instagram”, “What did you find?”, “Open reports”.",
                Film.boneDim,
            )
            TypedInput(enabled = !s.answering, hindi = hindi, onSend = vm::askText)
            if (s.transcript.isNotBlank()) Text("“${s.transcript}”", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
            if (s.choices.isNotEmpty()) {
                Note(if (hindi) "कौन सा?" else "Which one?", Film.scan)
                s.choices.forEach { app -> SecondaryAction(app.label, { vm.pickChoice(app) }, Modifier.fillMaxWidth().padding(top = 6.dp), enabled = !s.answering) }
            }
            s.micMessage?.let { Note(it, Film.high) }
            s.packNote?.let { Note(it) }
            if (s.canDownloadPack) {
                SecondaryAction("Download speech pack", vm::downloadSpeechPack, Modifier.fillMaxWidth().padding(top = 8.dp))
                FoldText("Downloads a recognition model through your phone's speech provider. No microphone audio is sent by TraceVeil.")
            }
            SecondaryAction("Check again", vm::recheckMic, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !s.listening)

            if (s.answering) {
                Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    BreathingDot(Film.scan)
                    Spacer(Modifier.width(10.dp))
                    Note(if (hindi) "सोच रहा है…" else "Thinking…", Film.scan)
                }
                SkeletonLines(3, Modifier.padding(top = 12.dp), label = if (hindi) "सोच रहा है" else "Thinking")
            }

            SectionTitle(if (hindi) "कमांड" else "Commands")
            ChipFlow {
                COMMAND_CHIPS.forEach { (en, hi) ->
                    val text = if (hindi) hi else en
                    AskChip(text, enabled = !s.answering) { vm.askText(text) }
                }
            }
            FoldText(
                "Commands run here on the phone. “Check” starts a normal Quick check that keeps the app's data; you make the consent choice yourself, except on the LeakyShop demo apps. Command replies use the phone's offline voice.",
                Modifier.padding(top = 8.dp),
            )

            SectionTitle("Or tap a question")
            ChipFlow {
                CopilotQuestions.chips.forEach { q ->
                    AskChip(if (hindi) q.hi else q.en, enabled = !s.answering) { vm.askChip(q) }
                }
            }

            s.commandReply?.let { reply ->
                SectionTitle("Answer")
                RuleRow(Film.scan, Modifier.reveal(0)) { Text(reply, style = MaterialTheme.typography.bodyLarge) }
            }
            s.answer?.let { a ->
                SectionTitle("Answer")
                RuleRow(if (a.refused) Film.boneDim else Film.scan, Modifier.reveal(0)) {
                    Text(a.text, style = MaterialTheme.typography.bodyLarge)
                    if (a.citations.isNotEmpty()) {
                        LearnMore(Modifier.padding(top = 4.dp), label = "Sources (${a.citations.size})", openLabel = "Hide sources") {
                            a.citations.forEach { p -> Note("[${p.id}] ${p.source}: ${p.title}${if (p.verbatim) "" else " (summary)"}") }
                        }
                    }
                }
            }

            SectionTitle("Speech output")
            SwitchRow("Also speak typed and tapped answers", null, s.speakAnswers, vm::setSpeakAnswers)
            // Online voice sends text off the phone, so its line is never folded.
            if (s.cloudVoice) {
                KeyCaveat("Online voice is on: each answer's text (not your audio or the audit) is sent to Sarvam AI to make speech.", color = Film.high)
            } else {
                FoldText("Questions you ask by voice are always answered aloud. Uses the phone's own offline voice.")
            }
            if (s.speaking || s.assistantSpeaking) SecondaryAction("Stop speaking", { vm.stopSpeaking() }, Modifier.fillMaxWidth().padding(top = 8.dp))
            s.voiceNote?.let { Note(it, Film.high) }
            SecondaryAction("Voice settings", vm::openVoiceSettings, Modifier.fillMaxWidth().padding(top = 8.dp))
            FoldText(
                "For a clearer voice, install a higher-quality offline voice in your phone's text-to-speech settings. Available voices depend on the engine.",
                Modifier.padding(top = 8.dp),
            )

            Hairline(Modifier.padding(top = 24.dp, bottom = 12.dp))
            Tag("Not legal advice", Film.warn)
            FoldText(
                "Answers use only this audit's findings and at most three passages of the DPDP Act and Rules, so they work without the AI model. Not legal advice.",
                Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** Commands offered as chips, in English and Hindi; they go through the same parser as speech. */
private val COMMAND_CHIPS = listOf(
    "Read my last result" to "मेरा पिछला नतीजा बताओ",
    "Check my phone" to "मेरे फ़ोन की जाँच करो",
    "What can you do?" to "तुम क्या कर सकते हो?",
)

/** English / Hindi as one segmented switch. Picking the current language does nothing, as before. */
@Composable
private fun LanguageSwitch(current: CopilotLanguage, onPick: (CopilotLanguage) -> Unit, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(Film.base.copy(alpha = 0.55f)).border(1.dp, FilmExtra.hairline, shape)
            .padding(4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CopilotLanguage.entries.forEach { l ->
            val selected = l == current
            val fill by animateColorAsState(
                if (selected) Film.scan else Color.Transparent, Motion.orSnap(reduce, tween(Motion.MEDIUM, easing = Motion.emphasized)), label = "lang",
            )
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).background(fill)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { if (!selected) onPick(l) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (l == CopilotLanguage.EN) "English" else "हिन्दी", style = MaterialTheme.typography.labelLarge,
                    color = if (selected) Film.base else Film.bone,
                )
            }
        }
    }
}

/** A question or command as a compact pill that wraps with its neighbours; 48dp tall, shrinks slightly when pressed. */
@Composable
private fun AskChip(text: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.heightIn(min = 48.dp)
            .pressClickable(enabled = enabled, shape = shape, onClick = onClick)
            .background(Film.scan.copy(alpha = if (enabled) 0.10f else 0.04f))
            .border(1.dp, Film.scan.copy(alpha = if (enabled) 0.45f else 0.18f), shape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (enabled) Film.bone else Film.boneDim)
    }
}

@Composable
private fun TypedInput(enabled: Boolean, hindi: Boolean, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val send = {
        if (text.isNotBlank()) onSend(text)
        text = ""
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true, enabled = enabled,
            placeholder = { Text(if (hindi) "सवाल या कमांड लिखें" else "Type a question or a command") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Film.scan, unfocusedBorderColor = Film.line, cursorColor = Film.scan),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        SecondaryAction(if (hindi) "भेजें" else "Send", { send() }, enabled = enabled && text.isNotBlank())
    }
}
