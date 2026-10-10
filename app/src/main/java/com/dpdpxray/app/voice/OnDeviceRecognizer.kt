package com.dpdpxray.app.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import com.dpdpxray.core.copilot.CopilotLanguage
import java.util.Locale

/**
 * Push-to-talk over Android's on-device recognizer only ([SpeechRecognizer.createOnDeviceSpeechRecognizer]); the
 * generic recognizer is never used because it may send audio to a server. No continuous listening, no recordings kept,
 * model downloads require an explicit user action and download a language pack, not microphone audio.
 * Must be used from the main thread; [release] on pause or when the screen goes away.
 *
 * Connections are never reused. The phone shuts its speech service down when it is idle, and a recognizer created
 * earlier then holds a dead connection, so the next listen fails with ERROR_SERVER_DISCONNECTED (code 11). So each
 * listening session builds its own recognizer and destroys it when it ends; a support check or a download request
 * builds a short-lived one; and a disconnected session is retried once with a fresh recognizer.
 */
class OnDeviceRecognizer(private val context: Context) {
    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(code: Int)
        fun onEnd()
    }

    private val main = Handler(Looper.getMainLooper())
    private var session: SpeechRecognizer? = null
    private var packs: SpeechPacks? = null
    private val supportGate = VoiceAnswerGate()

    fun choice(language: CopilotLanguage): SpeechPackChoice =
        SpeechPackPolicy.choose(language, packs, Locale.getDefault().toLanguageTag())

    val onDeviceAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** Reports the installed on-device language packs (Android 13+), or null when the phone cannot say. */
    fun checkInstalledLanguages(language: CopilotLanguage, onResult: (List<String>?) -> Unit) {
        checkLanguageSupport(language) { onResult(it?.installed) }
    }

    fun checkLanguageSupport(language: CopilotLanguage, onResult: (SpeechPacks?) -> Unit) {
        val request = supportGate.begin()
        packs = null
        if (Build.VERSION.SDK_INT < 33 || !onDeviceAvailable) return onResult(null)
        checkSupport33(language) { result ->
            if (!supportGate.isCurrent(request)) return@checkSupport33
            packs = result
            onResult(result)
        }
    }

    /** Uses a recognizer of its own that is destroyed as soon as the answer arrives, or after [SUPPORT_TIMEOUT_MS] if none does. */
    @RequiresApi(33)
    private fun checkSupport33(language: CopilotLanguage, onResult: (SpeechPacks?) -> Unit) {
        val r = create() ?: return onResult(null)
        var done = false
        var timeout: Runnable? = null
        fun finish(result: SpeechPacks?) {
            if (done) return
            done = true
            timeout?.let(main::removeCallbacks)
            destroyLater(r)
            onResult(result)
        }
        timeout = Runnable { finish(null) }.also { main.postDelayed(it, SUPPORT_TIMEOUT_MS) }
        runCatching {
            r.checkRecognitionSupport(intent(choice(language).tag), context.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) = finish(
                    SpeechPacks(support.installedOnDeviceLanguages, support.supportedOnDeviceLanguages, support.pendingOnDeviceLanguages),
                )
                override fun onError(error: Int) = finish(null)
            })
        }.onFailure { finish(null) }
    }

    /** Only call from an explicit Download button. The recognition provider may request download approval. */
    fun requestModelDownload(language: CopilotLanguage): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val r = create() ?: return false
        val requested = runCatching { r.triggerModelDownload(intent(choice(language).tag)) }.isSuccess
        // The request is handed to the speech service asynchronously, so the recognizer is kept briefly before it is released.
        main.postDelayed({ runCatching { r.destroy() } }, DOWNLOAD_HOLD_MS)
        return requested
    }

    fun start(language: CopilotLanguage, listener: Listener): Boolean = begin(language, listener, attempt = 0)

    private fun begin(language: CopilotLanguage, listener: Listener, attempt: Int): Boolean {
        discardSession()
        val r = create() ?: return false
        session = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                if (session === r) first(partialResults)?.let(listener::onPartial)
            }
            override fun onResults(results: Bundle?) {
                if (session !== r) return // a session this screen already abandoned
                first(results)?.let(listener::onFinal) ?: listener.onError(SpeechRecognizer.ERROR_NO_MATCH)
                end(r, listener)
            }
            override fun onError(error: Int) {
                if (session !== r) return
                if (SpeechErrors.shouldRetry(error, attempt)) {
                    Log.i(TAG, "speech service disconnected (code $error); retrying once with a fresh recognizer")
                    discardSession()
                    if (begin(language, listener, attempt + 1)) return
                }
                Log.i(TAG, "listening ended with recognizer error code $error")
                listener.onError(error)
                end(r, listener)
            }
        })
        val started = runCatching { r.startListening(intent(choice(language).tag)) }.isSuccess
        if (!started) discardSession()
        return started
    }

    fun stop() {
        runCatching { session?.stopListening() }
    }

    fun release() {
        supportGate.invalidate()
        discardSession()
    }

    private fun end(r: SpeechRecognizer, listener: Listener) {
        if (session === r) {
            session = null
            destroyLater(r)
        }
        listener.onEnd()
    }

    private fun discardSession() {
        val r = session ?: return
        session = null
        runCatching { r.cancel() }
        destroyLater(r)
    }

    private fun create(): SpeechRecognizer? =
        if (!onDeviceAvailable) null else runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()

    /** Destroying from inside one of the recognizer's own callbacks is avoided by posting it. */
    private fun destroyLater(r: SpeechRecognizer) {
        main.post { runCatching { r.destroy() } }
    }

    private fun intent(languageTag: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    private fun first(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private companion object {
        const val TAG = "TraceVeilVoice"
        const val SUPPORT_TIMEOUT_MS = 10_000L
        const val DOWNLOAD_HOLD_MS = 8_000L
    }
}
