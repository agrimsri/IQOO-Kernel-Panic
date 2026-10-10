package com.dpdpxray.app.voice.assistant

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import com.dpdpxray.app.voice.AnswerVoice
import com.dpdpxray.app.voice.VoiceOutputState
import com.dpdpxray.core.copilot.CopilotLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The assistant's spoken replies through the phone's own offline text-to-speech ([AnswerVoice]; no online voice, so
 * no reply text leaves the phone). [say] returns when the reply has finished, so the microphone is not reopened
 * while TraceVeil is still talking. A Hindi reply falls back to English when no offline Hindi voice is installed.
 */
class AssistantVoice(context: Context) {
    private val out = MutableStateFlow<VoiceOutputState>(VoiceOutputState.Idle)
    private val voice = AnswerVoice(context.applicationContext) { out.value = it }

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** Why the last reply was text only, if it was. */
    @Volatile var lastProblem: String? = null
        private set

    /** Speaks [said] in Hindi when wanted and possible, otherwise English. Returns false when nothing was spoken. */
    suspend fun say(said: Said, preferHindi: Boolean): Boolean {
        awaitReady()
        val hindi = preferHindi && voice.problemFor(CopilotLanguage.HI) == null
        return speak(said.pick(hindi), if (hindi) CopilotLanguage.HI else CopilotLanguage.EN)
    }

    /** "Yes?" after the wake word; a short beep instead when no offline voice can speak. */
    suspend fun cue(preferHindi: Boolean) {
        if (!say(AssistantCopy.cue, preferHindi)) beep()
    }

    fun stop() = voice.stop()

    fun release() = voice.release()

    private suspend fun awaitReady() {
        withTimeoutOrNull(READY_WAIT_MS) { while (!voice.ready) delay(100) }
    }

    private suspend fun speak(text: String, language: CopilotLanguage): Boolean {
        _speaking.value = true
        try {
            voice.speak(text, language)
            val now = out.value
            if (now !is VoiceOutputState.Speaking) {
                lastProblem = (now as? VoiceOutputState.Note)?.message
                return false
            }
            lastProblem = null
            withTimeoutOrNull(maxSpeechMs(text)) { out.first { it !is VoiceOutputState.Speaking } }
            return true
        } catch (e: CancellationException) {
            voice.stop()
            throw e
        } finally {
            _speaking.value = false
        }
    }

    private suspend fun beep() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS)
            delay(BEEP_MS + 150L)
            tone.release()
        }
    }

    private fun maxSpeechMs(text: String): Long = (3_000L + text.length * 120L).coerceAtMost(60_000L)

    private companion object {
        const val READY_WAIT_MS = 3_000L
        const val BEEP_MS = 180
    }
}
