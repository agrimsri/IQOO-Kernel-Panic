package com.dpdpxray.app.voice

import android.speech.SpeechRecognizer

/** What each [SpeechRecognizer] error means to the user, and which one is worth a silent retry. */
object SpeechErrors {
    private const val CHIPS = "Tap a question instead."

    fun reason(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Sorry, I did not catch that. Try again or tap a question."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission was not granted. $CHIPS"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "The on-device speech pack is missing. Use Download speech pack, then Check again. $CHIPS"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
            "This recognition service does not support that language. Try another language or check speech settings. $CHIPS"
        SpeechRecognizer.ERROR_AUDIO -> "The microphone could not be read. $CHIPS"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech recognizer is busy. Try again in a moment."
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
            "The phone's speech service disconnected (it shuts itself down when idle). Tap the microphone to try again."
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "The speech service is handling too many requests. Wait a moment, then try again."
        SpeechRecognizer.ERROR_CLIENT -> "Listening was interrupted. Tap the microphone to try again."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
            "The speech service reported a connection problem. TraceVeil only uses the on-device recognizer, so try again or tap a question."
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "The phone could not say which speech packs are installed. Tap Check again."
        SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> "The speech pack download could not be followed. Tap Check again later."
        else -> "Speech recognition stopped (code $code). $CHIPS"
    }

    /**
     * A disconnected service is the one error that is the stale connection's fault rather than the user's: the phone
     * shuts its speech service down when idle, and a recognizer built earlier then points at nothing. A fresh recognizer
     * fixes it, so it is retried once and never more.
     */
    fun shouldRetry(code: Int, attempt: Int): Boolean = code == SpeechRecognizer.ERROR_SERVER_DISCONNECTED && attempt == 0
}
