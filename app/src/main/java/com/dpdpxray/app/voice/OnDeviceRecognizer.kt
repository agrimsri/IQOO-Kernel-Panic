package com.dpdpxray.app.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import com.dpdpxray.core.copilot.CopilotLanguage
import java.util.Locale

/**
 * Push-to-talk over Android's on-device recognizer only ([SpeechRecognizer.createOnDeviceSpeechRecognizer]); the
 * generic recognizer is never used because it may send audio to a server. No continuous listening, no recordings kept,
 * model downloads require an explicit user action and download a language pack, not microphone audio.
 * Must be used from the main thread; [release] on pause or when the screen goes away.
 */
class OnDeviceRecognizer(private val context: Context) {
    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(code: Int)
        fun onEnd()
    }

    private var recognizer: SpeechRecognizer? = null
    private var packs: SpeechPacks? = null

    fun choice(language: CopilotLanguage): SpeechPackChoice =
        SpeechPackPolicy.choose(language, packs, Locale.getDefault().toLanguageTag())

    val onDeviceAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** Reports the installed on-device language packs (Android 13+), or null when the phone cannot say. */
    fun checkInstalledLanguages(language: CopilotLanguage, onResult: (List<String>?) -> Unit) {
        checkLanguageSupport(language) { onResult(it?.installed) }
    }

    fun checkLanguageSupport(language: CopilotLanguage, onResult: (SpeechPacks?) -> Unit) {
        if (Build.VERSION.SDK_INT < 33 || !onDeviceAvailable) return onResult(null)
        checkSupport33(language) { result ->
            packs = result
            onResult(result)
        }
    }

    @RequiresApi(33)
    private fun checkSupport33(language: CopilotLanguage, onResult: (SpeechPacks?) -> Unit) {
        val r = obtain() ?: return onResult(null)
        runCatching {
            r.checkRecognitionSupport(intent(choice(language).tag), context.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) = onResult(
                    SpeechPacks(support.installedOnDeviceLanguages, support.supportedOnDeviceLanguages, support.pendingOnDeviceLanguages),
                )
                override fun onError(error: Int) = onResult(null)
            })
        }.onFailure { onResult(null) }
    }

    /** Only call from an explicit Download button. The recognition provider may request download approval. */
    fun requestModelDownload(language: CopilotLanguage): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val r = obtain() ?: return false
        return runCatching { r.triggerModelDownload(intent(choice(language).tag)) }.isSuccess
    }

    fun start(language: CopilotLanguage, listener: Listener): Boolean {
        val r = obtain() ?: return false
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                first(partialResults)?.let(listener::onPartial)
            }
            override fun onResults(results: Bundle?) {
                first(results)?.let(listener::onFinal) ?: listener.onError(SpeechRecognizer.ERROR_NO_MATCH)
                listener.onEnd()
            }
            override fun onError(error: Int) {
                listener.onError(error)
                listener.onEnd()
            }
        })
        return runCatching { r.startListening(intent(choice(language).tag)) }.isSuccess
    }

    fun stop() {
        runCatching { recognizer?.stopListening() }
    }

    fun release() {
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun obtain(): SpeechRecognizer? {
        if (!onDeviceAvailable) return null
        return recognizer ?: runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()?.also { recognizer = it }
    }

    private fun intent(languageTag: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    private fun first(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
}
