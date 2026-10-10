package com.dpdpxray.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import com.dpdpxray.core.copilot.CopilotLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Optional online voice (Sarvam Bulbul). Only built when [ApiConfig.useApi] is on; the answer text, never the audit
 * evidence or microphone audio, is sent. If the request fails the user is told and the offline [local] voice speaks, so
 * the fallback goes cloud -> phone, never phone -> cloud. Same audio focus and routing rules as the offline voice.
 */
class CloudSpeechOutput(
    private val context: Context,
    private val apiKey: String,
    private val local: SpeechOutput,
    private val onState: (VoiceOutputState) -> Unit,
) : SpeechOutput {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change -> if (change < 0) stop() }
        .build()
    private var player: MediaPlayer? = null

    override val online = true

    override fun problemFor(language: CopilotLanguage): String? = null

    override suspend fun speak(text: String, language: CopilotLanguage) {
        stop()
        onState(VoiceOutputState.Speaking)
        when (val r = withContext(Dispatchers.IO) { SarvamTts.synthesize(text, language, apiKey) }) {
            is SarvamResult.Audio -> {
                Log.i(TAG, "Sarvam TTS ok, request ${r.requestId ?: "?"}")
                if (playWav(r.wav)) return
                onState(VoiceOutputState.Note("Cloud voice could not play; using the phone's voice."))
            }
            is SarvamResult.Failed -> {
                Log.w(TAG, "Sarvam TTS failed: ${r.reason}")
                onState(VoiceOutputState.Note("Cloud voice failed (${r.reason}); using the phone's voice."))
            }
        }
        local.speak(text, language)
    }

    private suspend fun playWav(wav: ByteArray): Boolean {
        val file = withContext(Dispatchers.IO) { File(context.cacheDir, "copilot-answer.wav").apply { writeBytes(wav) } }
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        return runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(attributes)
                setDataSource(file.absolutePath)
                setOnCompletionListener { finished() }
                setOnErrorListener { _, _, _ -> finished(); true }
                prepare()
                start()
            }
        }.onFailure { finished() }.isSuccess
    }

    private fun finished() {
        runCatching { player?.release() }
        player = null
        audio.abandonAudioFocusRequest(focus)
        onState(VoiceOutputState.Idle)
        runCatching { File(context.cacheDir, "copilot-answer.wav").delete() }
    }

    override fun stop() {
        runCatching { player?.stop() }
        finished()
        local.stop()
    }

    override fun release() {
        stop()
        local.release()
    }

    private companion object {
        const val TAG = "TraceVeilVoice"
    }
}
