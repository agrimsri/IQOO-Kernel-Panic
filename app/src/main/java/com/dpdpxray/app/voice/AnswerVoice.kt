package com.dpdpxray.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.dpdpxray.core.copilot.CopilotLanguage

/**
 * Speaks answers with the phone's own text-to-speech and an installed offline voice only. It makes no network request
 * itself; the optional online voice is a separate [CloudSpeechOutput]. Takes transient audio focus with normal routing (headphones stay in use) and stops when
 * focus is lost, e.g. for a call.
 */
class AnswerVoice(private val context: Context, private val onState: (VoiceOutputState) -> Unit) : SpeechOutput {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change -> if (change < 0) stop() }
        .build()

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override val online = false

    /** True once the phone's text-to-speech engine has finished binding. */
    val ready: Boolean get() = ttsReady

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            tts?.setAudioAttributes(attributes)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = finished()
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finished()
                override fun onError(utteranceId: String?, errorCode: Int) = finished()
            })
        }
    }

    /** Null when an installed offline voice exists for [language]; otherwise why the answer will be text only. */
    override fun problemFor(language: CopilotLanguage): String? {
        if (!ttsReady) return "The phone's text-to-speech engine is not ready."
        return if (selectLocalVoice(language)) null
        else "No offline ${if (language == CopilotLanguage.HI) "Hindi" else "English"} voice is installed on this phone; the answer is shown as text."
    }

    override suspend fun speak(text: String, language: CopilotLanguage) {
        stop()
        val problem = problemFor(language)
        if (problem != null) return onState(VoiceOutputState.Note(problem))
        if (!requestFocus()) return onState(VoiceOutputState.Note("Another app is using audio; the answer is shown as text."))
        onState(VoiceOutputState.Speaking)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "copilot-answer")
    }

    private fun selectLocalVoice(language: CopilotLanguage): Boolean {
        val engine = tts ?: return false
        val voices = runCatching { engine.voices }.getOrNull().orEmpty()
        val infos = voices.map {
            VoiceInfo(it.name, it.locale.toLanguageTag(), it.isNetworkConnectionRequired,
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features.orEmpty(), it.quality)
        }
        val pick = LocalVoicePicker.pick(infos, language) ?: return false
        return voices.firstOrNull { it.name == pick.name }?.let { engine.setVoice(it) == TextToSpeech.SUCCESS } ?: false
    }

    private fun requestFocus() = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    private fun finished() {
        audio.abandonAudioFocusRequest(focus)
        onState(VoiceOutputState.Idle)
    }

    override fun stop() {
        runCatching { tts?.stop() }
        finished()
    }

    override fun release() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
    }

}

/** Replaceable speech provider. [AnswerVoice] is the offline default; any other provider must be an explicit, separate mode. */
interface SpeechOutput {
    /** True only for a provider that sends text off the phone. */
    val online: Boolean

    /** Null when this provider can speak [language]; otherwise why the answer will be text only. */
    fun problemFor(language: CopilotLanguage): String?

    suspend fun speak(text: String, language: CopilotLanguage)
    fun stop()
    fun release()
}

sealed interface VoiceOutputState {
    data object Idle : VoiceOutputState
    data object Speaking : VoiceOutputState
    data class Note(val message: String) : VoiceOutputState

    fun applyTo(s: VoiceUiState): VoiceUiState = when (this) {
        Idle -> s.copy(speaking = false)
        Speaking -> s.copy(speaking = true)
        is Note -> s.copy(speaking = false, voiceNote = message)
    }
}
