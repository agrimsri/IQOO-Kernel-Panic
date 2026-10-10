package com.dpdpxray.app.voice.wake

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException

/**
 * Offline keyword spotting with Vosk's grammar-restricted recogniser over the phone microphone.
 *
 * Audio is read in 100 ms buffers that are handed straight to the recogniser and then overwritten; nothing is written
 * to storage, logged or sent anywhere. Only final results are reported, never partial guesses. Vosk's own
 * `SpeechService` is not used because its recording thread throws an uncaught exception when a read fails, which would
 * crash the app; here every failure becomes [Listener.onError].
 *
 * Callbacks arrive on the main thread. [stop] returns immediately; the worker releases the microphone, the recogniser
 * and the model when its current read finishes.
 */
internal class VoskWakeEngine(
    private val modelDir: File,
    private val grammar: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady()
        fun onResult(json: String)
        fun onError(detail: String)
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var resetRequested = false
    private var worker: Thread? = null

    fun start() {
        if (worker != null) return
        running = true
        worker = Thread(::run, "HeyTraceVeil-wake").also { it.start() }
    }

    /** While paused, audio is read and discarded; on resume the recogniser starts from a clean slate. */
    fun setPaused(value: Boolean) {
        if (paused && !value) resetRequested = true
        paused = value
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    @SuppressLint("MissingPermission") // the service checks RECORD_AUDIO before creating the engine
    private fun run() {
        var model: Model? = null
        var recognizer: Recognizer? = null
        var record: AudioRecord? = null
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            model = Model(modelDir.absolutePath)
            if (!running) return
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat(), grammar).apply { setWords(true) }
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) throw IOException("this phone cannot record 16 kHz mono audio")
            val chunk = SAMPLE_RATE / 10
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, chunk * 2 * 2),
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) throw IOException("the microphone could not be opened; another app may be using it")
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IOException("the microphone did not start recording")
            if (!running) return
            main.post { if (running) listener.onReady() }

            val buffer = ShortArray(chunk)
            while (running && !Thread.currentThread().isInterrupted) {
                val n = record.read(buffer, 0, buffer.size)
                if (n < 0) throw IOException("microphone read failed (code $n)")
                if (n == 0 || paused) continue
                if (resetRequested) {
                    recognizer.reset()
                    resetRequested = false
                }
                if (recognizer.acceptWaveForm(buffer, n)) {
                    val json = recognizer.result
                    main.post { if (running) listener.onResult(json) }
                }
            }
        } catch (t: Throwable) {
            // Includes a missing native library (UnsatisfiedLinkError) or a model that fails to load.
            if (running) {
                val detail = t.message ?: t.javaClass.simpleName
                main.post { if (running) listener.onError(detail) }
            }
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
            runCatching { recognizer?.close() }
            runCatching { model?.close() }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}
