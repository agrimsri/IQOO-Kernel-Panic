package com.dpdpxray.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.dpdpxray.core.copilot.CopilotLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Speaks answers. Default: the phone's own text-to-speech with an installed offline voice. When [config] enables the
 * demo cloud voice, Sarvam is tried first and the phone voice is used if it fails. Both paths take transient audio focus
 * with normal routing (headphones stay in use) and stop when focus is lost, e.g. for a call.
 */
class AnswerVoice(private val context: Context, private val config: ApiConfig, private val onState: (VoiceOutputState) -> Unit) {
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
    private var player: MediaPlayer? = null

    val cloudVoice: Boolean get() = config.useApi

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
    fun localVoiceProblem(language: CopilotLanguage): String? {
        if (!ttsReady) return "The phone's text-to-speech engine is not ready."
        return if (selectLocalVoice(language)) null
        else "No offline ${if (language == CopilotLanguage.HI) "Hindi" else "English"} voice is installed on this phone; the answer is shown as text."
    }

    suspend fun speak(text: String, language: CopilotLanguage) {
        stop()
        val key = config.sarvamKey
        if (config.useApi && key != null) {
            onState(VoiceOutputState.Speaking(cloud = true))
            when (val r = withContext(Dispatchers.IO) { SarvamTts.synthesize(text, language, key) }) {
                is SarvamResult.Audio -> {
                    Log.i(TAG, "Sarvam TTS ok, request ${r.requestId ?: "?"}")
                    if (playWav(r.wav)) return
                }
                is SarvamResult.Failed -> {
                    Log.w(TAG, "Sarvam TTS failed: ${r.reason}")
                    onState(VoiceOutputState.Note("Cloud voice failed (${r.reason}); using the phone's voice."))
                }
            }
        }
        speakLocal(text, language)
    }

    private fun speakLocal(text: String, language: CopilotLanguage) {
        val problem = localVoiceProblem(language)
        if (problem != null) return onState(VoiceOutputState.Note(problem))
        if (!requestFocus()) return onState(VoiceOutputState.Note("Another app is using audio; the answer is shown as text."))
        onState(VoiceOutputState.Speaking(cloud = false))
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "copilot-answer")
    }

    private suspend fun playWav(wav: ByteArray): Boolean {
        val file = withContext(Dispatchers.IO) { File(context.cacheDir, "copilot-answer.wav").apply { writeBytes(wav) } }
        if (!requestFocus()) return false
        return runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(attributes)
                setDataSource(file.absolutePath)
                setOnCompletionListener { finished() }
                setOnErrorListener { _, _, _ -> finished(); true }
                prepare()
                start()
            }
        }.isSuccess
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
        runCatching { player?.release() }
        player = null
        audio.abandonAudioFocusRequest(focus)
        onState(VoiceOutputState.Idle)
    }

    fun stop() {
        runCatching { tts?.stop() }
        runCatching { player?.stop() }
        finished()
    }

    fun release() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
    }

    private companion object {
        const val TAG = "TraceVeilVoice"
    }
}

sealed interface VoiceOutputState {
    data object Idle : VoiceOutputState
    data class Speaking(val cloud: Boolean) : VoiceOutputState
    data class Note(val message: String) : VoiceOutputState
}
