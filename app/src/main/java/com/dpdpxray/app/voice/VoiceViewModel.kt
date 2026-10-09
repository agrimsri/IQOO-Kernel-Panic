package com.dpdpxray.app.voice

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dpdpxray.app.graph
import com.dpdpxray.core.copilot.AuditEvidence
import com.dpdpxray.core.copilot.CopilotLanguage
import com.dpdpxray.core.copilot.CopilotQuestion
import com.dpdpxray.core.copilot.GroundedAnswer
import com.dpdpxray.core.copilot.GroundedAnswerer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class VoiceUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val label: String = "",
    val sample: Boolean = false,
    val language: CopilotLanguage = CopilotLanguage.EN,
    val mic: MicState = MicState.Unavailable("Checking the microphone…"),
    val listening: Boolean = false,
    val transcript: String = "",
    val micMessage: String? = null,
    val answering: Boolean = false,
    val answer: GroundedAnswer? = null,
    val speakAnswers: Boolean = true,
    val speaking: Boolean = false,
    val voiceNote: String? = null,
)

/** State for [VoiceCopilotScreen]. Reads one saved audit; answers come from [GroundedAnswerer], never from a model. */
class VoiceViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph
    private val recognizer = OnDeviceRecognizer(app)
    private var evidence: AuditEvidence? = null
    private var installed: List<String>? = null
    private var permissionDenied = false

    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private val voice: SpeechOutput = AnswerVoice(app) { s -> _state.update { s.applyTo(it) } }

    fun load(auditId: String) {
        if (evidence != null) return
        viewModelScope.launch {
            val bundle = graph.repository.load(auditId)
            if (bundle == null) {
                _state.update { it.copy(loading = false, missing = true) }
                return@launch
            }
            evidence = copilotEvidence(bundle.meta, bundle.sessions, bundle.result)
            _state.update { it.copy(loading = false, label = bundle.meta.targetLabel, sample = bundle.meta.sample) }
            refreshMic()
        }
    }

    fun setLanguage(language: CopilotLanguage) {
        if (language == _state.value.language) return
        cancelListening()
        voice.stop()
        _state.update { it.copy(language = language, answer = null, transcript = "", micMessage = null) }
        refreshMic()
    }

    fun setSpeakAnswers(on: Boolean) {
        if (!on) voice.stop()
        _state.update { it.copy(speakAnswers = on) }
    }

    /** Re-checks permission and language packs, e.g. after resuming from Settings. */
    fun refreshMic() {
        installed = null
        recomputeMic()
        recognizer.checkInstalledLanguages(_state.value.language) { langs ->
            installed = langs
            recomputeMic()
        }
    }

    fun onPermissionResult(granted: Boolean) {
        permissionDenied = !granted
        recomputeMic()
    }

    private fun recomputeMic() {
        val ctx = getApplication<Application>()
        val granted = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val evaluated = MicAvailability.evaluate(Build.VERSION.SDK_INT, recordAudioDeclared(), granted, recognizer.onDeviceAvailable, installed, _state.value.language)
        _state.update { it.copy(mic = if (evaluated == MicState.NeedsPermission && permissionDenied) MicAvailability.denied() else evaluated) }
    }

    private fun recordAudioDeclared(): Boolean = runCatching {
        val ctx = getApplication<Application>()
        val info = if (Build.VERSION.SDK_INT >= 33) {
            ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
        }
        info.requestedPermissions.orEmpty().contains(Manifest.permission.RECORD_AUDIO)
    }.getOrDefault(false)

    fun toggleListening() {
        if (_state.value.listening) {
            recognizer.stop()
            return
        }
        if (_state.value.mic != MicState.Ready) return
        voice.stop()
        val started = recognizer.start(_state.value.language, object : OnDeviceRecognizer.Listener {
            override fun onPartial(text: String) = _state.update { it.copy(transcript = text) }
            override fun onFinal(text: String) {
                _state.update { it.copy(transcript = text) }
                ask(text)
            }
            override fun onError(code: Int) = _state.update { it.copy(micMessage = MicAvailability.errorReason(code)) }
            override fun onEnd() = _state.update { it.copy(listening = false) }
        })
        _state.update {
            if (started) it.copy(listening = true, transcript = "", micMessage = null)
            else it.copy(micMessage = "The on-device recognizer could not start. Tap a question instead.")
        }
    }

    fun askChip(q: CopilotQuestion) {
        cancelListening()
        val text = if (_state.value.language == CopilotLanguage.HI) q.hi else q.en
        _state.update { it.copy(transcript = text) }
        ask(text)
    }

    private fun ask(question: String) {
        val e = evidence ?: return
        val language = _state.value.language
        viewModelScope.launch {
            _state.update { it.copy(answering = true) }
            // The embedding retriever may run a model, so retrieval stays off the main thread.
            val answer = withContext(Dispatchers.Default) { GroundedAnswerer(graph.corpus, graph.retriever).answer(question, e, language) }
            _state.update { it.copy(answering = false, answer = answer, voiceNote = null) }
            if (_state.value.speakAnswers) voice.speak(answer.spokenText, language)
        }
    }

    fun stopSpeaking() = voice.stop()

    /** Called when the screen pauses or leaves: no listening or speech continues in the background. */
    fun pause() {
        cancelListening()
        voice.stop()
    }

    private fun cancelListening() {
        recognizer.release()
        _state.update { it.copy(listening = false) }
    }

    override fun onCleared() {
        recognizer.release()
        voice.release()
    }
}
