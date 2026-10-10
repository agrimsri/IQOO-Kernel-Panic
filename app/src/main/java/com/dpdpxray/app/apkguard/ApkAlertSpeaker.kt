package com.dpdpxray.app.apkguard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.dpdpxray.app.voice.LocalVoicePicker
import com.dpdpxray.app.voice.VoiceInfo
import com.dpdpxray.core.copilot.CopilotLanguage
import java.util.Locale

/**
 * Speaks a new-install alert once with the phone's own text-to-speech, using an installed offline voice only (the
 * same [LocalVoicePicker] rule as Voice Copilot). If no offline voice exists, it stays silent: the notification is the
 * alert. Used only when the user switched on "Speak APK Guard alerts".
 */
object ApkAlertSpeaker {
    private val main = Handler(Looper.getMainLooper())
    private var current: TextToSpeech? = null

    fun speak(context: Context, english: String, hindi: String) {
        val app = context.applicationContext
        main.post {
            runCatching { current?.shutdown() }
            val audio = app.getSystemService(AudioManager::class.java)
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).build()
            var engine: TextToSpeech? = null
            fun finish() {
                runCatching { audio?.abandonAudioFocusRequest(focus) }
                main.post {
                    runCatching { engine?.shutdown() }
                    if (current === engine) current = null
                }
            }
            engine = TextToSpeech(app) { status ->
                val tts = engine ?: return@TextToSpeech
                if (status != TextToSpeech.SUCCESS) return@TextToSpeech finish()
                val preferHindi = Locale.getDefault().language == "hi"
                val order = if (preferHindi) listOf(CopilotLanguage.HI, CopilotLanguage.EN) else listOf(CopilotLanguage.EN)
                val language = order.firstOrNull { selectOfflineVoice(tts, it) } ?: return@TextToSpeech finish()
                tts.setAudioAttributes(attributes)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = finish()
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = finish()
                    override fun onError(utteranceId: String?, errorCode: Int) = finish()
                })
                if (audio?.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return@TextToSpeech finish()
                tts.speak(if (language == CopilotLanguage.HI) hindi else english, TextToSpeech.QUEUE_FLUSH, null, "apkguard-alert")
            }
            current = engine
        }
    }

    private fun selectOfflineVoice(engine: TextToSpeech, language: CopilotLanguage): Boolean {
        val voices = runCatching { engine.voices }.getOrNull().orEmpty()
        val infos = voices.map {
            VoiceInfo(it.name, it.locale.toLanguageTag(), it.isNetworkConnectionRequired,
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features.orEmpty(), it.quality)
        }
        val pick = LocalVoicePicker.pick(infos, language) ?: return false
        return voices.firstOrNull { it.name == pick.name }?.let { engine.setVoice(it) == TextToSpeech.SUCCESS } ?: false
    }
}
