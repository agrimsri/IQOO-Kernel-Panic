package com.dpdpxray.app.voice

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.PrimaryAction
import com.dpdpxray.app.ui.components.RuleRow
import com.dpdpxray.app.ui.components.SecondaryAction
import com.dpdpxray.app.ui.components.SectionTitle
import com.dpdpxray.app.ui.components.Tag
import com.dpdpxray.app.ui.components.TopBar
import com.dpdpxray.app.ui.theme.Film
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

    Column(Modifier.fillMaxSize()) {
        TopBar("Ask this audit", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            when {
                s.loading -> return@Column Note("Opening the audit…")
                s.missing -> return@Column Note("This audit could not be opened.", Film.leak)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.label, style = MaterialTheme.typography.titleMedium)
                if (s.sample) Tag("SAMPLE", Film.high)
            }

            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CopilotLanguage.entries.forEach { l ->
                    val label = if (l == CopilotLanguage.EN) "English" else "हिन्दी"
                    if (l == s.language) PrimaryAction(label, {}, Modifier.weight(1f))
                    else SecondaryAction(label, { vm.setLanguage(l) }, Modifier.weight(1f))
                }
            }

            SectionTitle("Ask or tell it what to do")
            when (val mic = s.mic) {
                MicState.Ready -> PrimaryAction(if (s.listening) "Listening… tap to stop" else "Tap to speak", { vm.toggleListening() })
                MicState.NeedsPermission -> PrimaryAction("Allow the microphone", { permission.launch(Manifest.permission.RECORD_AUDIO) })
                is MicState.Unavailable -> {
                    PrimaryAction("Microphone unavailable", {}, enabled = false)
                    Note(mic.reason, Film.high)
                }
            }
            Note("Speech is recognised on this phone only. Nothing is recorded or kept.")
            Note(if (s.language == CopilotLanguage.HI) "जैसे: “इंस्टाग्राम की जाँच करो”, “क्या मिला?”, “रिपोर्ट खोलो”।" else "Try: “Check Instagram”, “What did you find?”, “Open reports”.")
            TypedInput(enabled = !s.answering, hindi = s.language == CopilotLanguage.HI, onSend = vm::askText)
            if (s.transcript.isNotBlank()) Text("“${s.transcript}”", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
            if (s.choices.isNotEmpty()) {
                Note(if (s.language == CopilotLanguage.HI) "कौन सा?" else "Which one?", Film.scan)
                s.choices.forEach { app -> SecondaryAction(app.label, { vm.pickChoice(app) }, Modifier.fillMaxWidth().padding(top = 6.dp), enabled = !s.answering) }
            }
            s.micMessage?.let { Note(it, Film.high) }
            s.packNote?.let { Note(it) }
            if (s.canDownloadPack) {
                SecondaryAction("Download speech pack", vm::downloadSpeechPack, Modifier.fillMaxWidth().padding(top = 8.dp))
                Note("Downloads a recognition model through your phone's speech provider. No microphone audio is sent by TraceVeil.")
            }
            SecondaryAction("Check again", vm::recheckMic, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !s.listening)

            SectionTitle(if (s.language == CopilotLanguage.HI) "कमांड" else "Commands")
            COMMAND_CHIPS.forEach { (en, hi) ->
                val text = if (s.language == CopilotLanguage.HI) hi else en
                SecondaryAction(text, { vm.askText(text) }, Modifier.fillMaxWidth().padding(top = 6.dp), enabled = !s.answering)
            }

            SectionTitle("Or tap a question")
            CopilotQuestions.chips.forEach { q ->
                SecondaryAction(if (s.language == CopilotLanguage.HI) q.hi else q.en, { vm.askChip(q) }, Modifier.fillMaxWidth().padding(top = 6.dp), enabled = !s.answering)
            }

            s.commandReply?.let { reply ->
                SectionTitle("Answer")
                RuleRow(Film.scan) { Text(reply, style = MaterialTheme.typography.bodyLarge) }
            }
            s.answer?.let { a ->
                SectionTitle("Answer")
                RuleRow(if (a.refused) Film.boneDim else Film.scan) {
                    Text(a.text, style = MaterialTheme.typography.bodyLarge)
                    a.citations.forEach { p -> Note("[${p.id}] ${p.source}: ${p.title}${if (p.verbatim) "" else " (summary)"}") }
                }
            }

            SectionTitle("Speech output")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Also speak typed and tapped answers", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (s.cloudVoice) "Online voice is on: each answer's text (not your audio or the audit) is sent to Sarvam AI to make speech."
                        else "Questions you ask by voice are always answered aloud. Uses the phone's own offline voice.",
                        style = MaterialTheme.typography.bodyMedium, color = if (s.cloudVoice) Film.high else Film.boneDim,
                    )
                }
                Switch(checked = s.speakAnswers, onCheckedChange = vm::setSpeakAnswers, colors = SwitchDefaults.colors(checkedTrackColor = Film.scan, checkedThumbColor = Film.base))
            }
            if (s.speaking || s.assistantSpeaking) SecondaryAction("Stop speaking", { vm.stopSpeaking() }, Modifier.fillMaxWidth().padding(top = 8.dp))
            s.voiceNote?.let { Note(it, Film.high) }
            SecondaryAction("Voice settings", vm::openVoiceSettings, Modifier.fillMaxWidth().padding(top = 8.dp))
            Note("For a clearer voice, install a higher-quality offline voice in your phone's text-to-speech settings. Available voices depend on the engine.")

            Note("Answers use only this audit's findings and at most three passages of the DPDP Act and Rules, so they work without the AI model. Not legal advice.")
            Note("Commands run here on the phone. “Check” starts a normal Quick check that keeps the app's data; you make the consent choice yourself, except on the LeakyShop demo apps. Command replies use the phone's offline voice.")
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
