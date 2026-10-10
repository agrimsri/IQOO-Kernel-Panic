package com.dpdpxray.app.ui.guide

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dpdpxray.app.audit.AuditProgress
import com.dpdpxray.app.data.XraySettings
import com.dpdpxray.app.graph
import com.dpdpxray.app.voice.LocalVoicePicker
import com.dpdpxray.app.voice.VoiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Something that can read a guide step aloud. */
interface GuideSpeaker {
    /** Reads [text] unless the same [key] was just read in [lang]. Replaces anything guidance was saying. */
    fun say(key: String, text: String, lang: GuideLang)

    /** Stops guidance speech. With a [key], stops only if that step is the one being read. */
    fun stop(key: String? = null)
}

/**
 * Spoken guidance through the phone speaker, using the phone's own text-to-speech with an installed offline voice
 * only (picked by the copilot's [LocalVoicePicker]); with no offline voice for the language it stays silent and says
 * so in [note]. It makes no network request, never reads captured data (callers pass fixed step text), and binds the
 * TTS engine only when guidance is first spoken.
 *
 * It never talks over the voice copilot: [suppressed] is set while the copilot screen is open, and guidance takes
 * transient, duckable audio focus and stops as soon as any other speech (such as a copilot answer) takes focus.
 */
class GuideVoice(context: Context) : GuideSpeaker {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change -> if (change < 0) main.post { stop() } }
        .build()
    private val dedupe = SpeechDedupe(REPEAT_GAP_MS)

    private val _note = MutableStateFlow<String?>(null)
    /** Why guidance is text only right now (no offline voice, audio busy); null when speech works. */
    val note: StateFlow<String?> = _note.asStateFlow()

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Narration? = null
    private var currentKey: String? = null

    /** True while the voice copilot owns the screen; guidance stays silent and stops immediately. */
    var suppressed: Boolean = false
        set(value) {
            field = value
            if (value) stop()
        }

    override fun say(key: String, text: String, lang: GuideLang) {
        if (suppressed || text.isBlank()) return
        if (!dedupe.shouldSpeak(key, lang, SystemClock.elapsedRealtime())) return
        val n = Narration(key, text, lang)
        if (!ready) {
            pending = n
            engine()
            return
        }
        speakNow(n)
    }

    /** Lets the next step be read even if it was read recently, e.g. right after guidance is switched on. */
    fun forgetRecent() = dedupe.reset()

    private fun engine(): TextToSpeech = tts ?: TextToSpeech(app) { status ->
        main.post {
            ready = status == TextToSpeech.SUCCESS
            if (!ready) {
                _note.value = "The phone's text-to-speech engine is not ready; guidance is shown as text."
                pending = null
                // Drop the failed engine so a later step can try again instead of queueing forever.
                runCatching { tts?.shutdown() }
                tts = null
                return@post
            }
            tts?.setAudioAttributes(attributes)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { main.post { finished(utteranceId) } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { main.post { finished(utteranceId) } }
                override fun onError(utteranceId: String?, errorCode: Int) { main.post { finished(utteranceId) } }
            })
            pending?.let { pending = null; if (!suppressed) speakNow(it) }
        }
    }.also { tts = it }

    private fun speakNow(n: Narration) {
        val engine = tts ?: return
        if (!selectOfflineVoice(engine, n.lang)) {
            _note.value = "No offline ${if (n.lang == GuideLang.HI) "Hindi" else "English"} voice is installed; guidance is shown as text."
            return
        }
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            _note.value = "Another app is using audio; guidance is shown as text."
            return
        }
        _note.value = null
        currentKey = n.key
        engine.speak(n.text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_PREFIX + n.key)
    }

    private fun selectOfflineVoice(engine: TextToSpeech, lang: GuideLang): Boolean {
        val voices = runCatching { engine.voices }.getOrNull().orEmpty()
        val infos = voices.map {
            VoiceInfo(it.name, it.locale.toLanguageTag(), it.isNetworkConnectionRequired,
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features.orEmpty(), it.quality)
        }
        val pick = LocalVoicePicker.pick(infos, lang.copilot) ?: return false
        return voices.firstOrNull { it.name == pick.name }?.let { engine.setVoice(it) == TextToSpeech.SUCCESS } ?: false
    }

    private fun finished(utteranceId: String?) {
        if (utteranceId == null || utteranceId == UTTERANCE_PREFIX + currentKey) {
            currentKey = null
            audio.abandonAudioFocusRequest(focus)
        }
    }

    override fun stop(key: String?) {
        if (key != null && key != currentKey && key != pending?.key) return
        pending = null
        currentKey = null
        runCatching { tts?.stop() }
        audio.abandonAudioFocusRequest(focus)
    }

    fun release() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    private companion object {
        const val REPEAT_GAP_MS = 45_000L
        const val UTTERANCE_PREFIX = "guide-"
    }
}

/** Reads the running check's steps aloud, also while the checked app is in front of TraceVeil. */
object GuideNarrator {
    suspend fun run(progress: StateFlow<AuditProgress>, settings: StateFlow<XraySettings>, speaker: GuideSpeaker) {
        var last: String? = null
        combine(progress, settings) { p, s -> GuideFlow.narration(p, s.spokenGuidance, GuideLang.of(s.guideLanguage)) }
            .distinctUntilChanged()
            .collect { n ->
                if (n != null) {
                    speaker.say(n.key, n.text, n.lang)
                    last = n.key
                } else {
                    // The check ended, was stopped, or guidance was switched off: finish nothing stale.
                    last?.let { speaker.stop(it) }
                    last = null
                }
            }
    }
}

/**
 * Owns the guide speaker and the live narrator for the app's lifetime in this task, so a rotation or theme change
 * neither cuts a sentence off nor reads the same step again.
 */
class GuideViewModel(app: Application) : AndroidViewModel(app) {
    val voice = GuideVoice(app)

    init {
        viewModelScope.launch { GuideNarrator.run(app.graph.orchestrator.progress, app.graph.settings.settings, voice) }
    }

    override fun onCleared() = voice.release()
}
