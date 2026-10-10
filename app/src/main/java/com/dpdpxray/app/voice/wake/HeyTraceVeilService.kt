package com.dpdpxray.app.voice.wake

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dpdpxray.app.MainActivity
import com.dpdpxray.app.R
import com.dpdpxray.app.audit.AuditProgress
import com.dpdpxray.app.graph
import com.dpdpxray.app.voice.OnDeviceRecognizer
import com.dpdpxray.app.voice.assistant.AssistantAction
import com.dpdpxray.app.voice.assistant.AssistantCopy
import com.dpdpxray.app.voice.assistant.AssistantSource
import com.dpdpxray.app.voice.assistant.Said
import com.dpdpxray.app.voice.assistant.VoiceAssistant
import com.dpdpxray.core.copilot.CopilotLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * EXPERIMENTAL, opt-in "Hey TraceVeil" listener.
 *
 * - Started only by [WakeController.start] from a Settings tap while TraceVeil is on screen; never on boot, never
 *   restarted by the system (START_NOT_STICKY), never exported.
 * - A microphone foreground service with a persistent notification for the whole session; tapping it stops listening.
 * - Offline keyword spotting only ([VoskWakeEngine]); audio is never stored or uploaded.
 * - Runs for the length chosen in Settings (15 minutes, 1 hour, or until the user stops it). Stops on low battery,
 *   when the user stops it, or when TraceVeil is swiped away from recent apps. It keeps running with the screen off.
 * - Lets go of the microphone while the voice copilot is open or a voice-started check runs, then resumes by itself.
 * - On a detection it says "Yes?", releases the microphone, hears ONE command with the phone's on-device speech
 *   recogniser ([OnDeviceRecognizer]), acts on it through [VoiceAssistant], replies aloud and resumes listening.
 *   Screen work happens on TraceVeil's screen; from the background TraceVeil asks to come forward, and if Android
 *   does not allow it, a notification asks the user to tap to continue.
 */
class HeyTraceVeilService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var engine: VoskWakeEngine? = null
    private var recognizer: OnDeviceRecognizer? = null
    private var batteryLowSignal = false
    private var batteryReceiver: BroadcastReceiver? = null
    private var running = false
    private var stopRequests: Job? = null
    private var holdWatch: Job? = null
    private var command: Job? = null
    private var duration: WakeDuration = WakeDuration.HOUR_1
    private var lastTitle: String? = null
    private var externalHolds: Set<WakeHold> = emptySet()

    private val tickRunnable = Runnable { tick() }
    private val startTimeout = Runnable {
        if (WakeRuntime.state.value == WakeState.Starting) transition(WakeEvent.EngineFailed("the offline recogniser did not start within 60 seconds"))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent)
            ACTION_STOP -> finish(intent.getStringExtra(EXTRA_REASON)?.let { runCatching { WakeStopReason.valueOf(it) }.getOrNull() } ?: WakeStopReason.USER)
            else -> if (!running) stopSelf() // a null intent would mean a system restart: never resume listening on our own
        }
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent?) {
        if (running || WakeRuntime.state.value.active) return
        val models = WakeModelManager.get(this)
        val modelDir = models.modelDir
        val problem = WakeArming.problem(
            modelReady = modelDir != null && models.isReady(),
            micGranted = WakeController.micGranted(this),
            notificationsAllowed = WakeController.notificationsAllowed(this),
            battery = WakeController.battery(this),
            copilotOpen = WakeRuntime.copilotOpen,
        )
        if (problem != null || modelDir == null) {
            WakeRuntime.refuse(problem ?: WakeStopReason.MODEL_MISSING)
            stopSelf()
            return
        }
        createChannels()
        duration = WakeDuration.fromName(intent?.getStringExtra(EXTRA_DURATION))
            ?: WakeDuration.effective(WakePreferences.get(this).state.value.duration, WakeController.battery(this).charging)
        val endsAt = duration.limitMs?.let { System.currentTimeMillis() + it }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_LISTENING, listeningNotification(endsAt, WakeCopy.notificationTitle(paused = false)), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // SecurityException or ForegroundServiceStartNotAllowedException: e.g. the app was no longer in the foreground.
            Log.i(TAG, "microphone foreground service refused: ${e.javaClass.simpleName}")
            WakeRuntime.refuse(WakeStopReason.NOT_ALLOWED_NOW, e.message)
            stopSelf()
            return
        }
        running = true
        WakeRuntime.apply(WakeEvent.Arm)
        WakeRuntime.setEndsAt(endsAt)
        WakeRuntime.setDuration(duration)
        registerBattery()
        val assistant = VoiceAssistant.get(this).also { it.warmUp() }
        recognizer = OnDeviceRecognizer(this).also { r ->
            // Learn which on-device language packs are installed, so a command uses the right one.
            r.checkLanguageSupport(commandLanguage()) {}
        }
        stopRequests = scope.launch { WakeRuntime.stopRequests.collect { finish(it) } }
        engine = VoskWakeEngine(modelDir, WakePhrase.grammarJson(), engineListener).also { it.start() }
        main.postDelayed(startTimeout, START_TIMEOUT_MS)
        holdWatch = scope.launch {
            combine(WakeRuntime.copilotOpenFlow, WakeRuntime.uiVisibleFlow, graph.orchestrator.progress, assistant.busy) { copilot, visible, progress, busy ->
                buildSet {
                    // Only while the copilot is on screen: it releases the microphone as soon as it is paused.
                    if (copilot && visible) add(WakeHold.COPILOT)
                    if (progress is AuditProgress.Running || busy) add(WakeHold.ASSISTANT)
                }
            }.distinctUntilChanged().collect { externalHolds = it; applyHolds() }
        }
        scheduleTick()
    }

    private val engineListener = object : VoskWakeEngine.Listener {
        override fun onReady() {
            transition(WakeEvent.EngineReady(SystemClock.elapsedRealtime()))
            applyHolds() // the copilot may have opened while the model was loading
            scheduleTick()
        }

        override fun onResult(json: String) {
            if (!WakeRuntime.state.value.processesAudio) return
            val sensitivity = WakePreferences.get(this@HeyTraceVeilService).state.value.sensitivity
            val heard = WakePhrase.detect(json, sensitivity)
            // Calibration aid: the outcome and confidence only, never what was said.
            if (heard.reason != WakeHeard.Reason.NO_MATCH) Log.i(TAG, "wake ${heard.reason} conf=${heard.confidence} mode=$sensitivity")
            WakeRuntime.record(heard, sensitivity, System.currentTimeMillis())
            if (!heard.accepted) return
            val state = transition(WakeEvent.Heard(SystemClock.elapsedRealtime()))
            if (state is WakeState.Paused && WakeHold.COMMAND in state.holds) startCommand()
        }

        override fun onError(detail: String) {
            Log.i(TAG, "wake engine stopped: $detail")
            transition(WakeEvent.EngineFailed(detail))
        }
    }

    /** Applies one event and makes the engine and service follow the resulting state. */
    private fun transition(event: WakeEvent): WakeState {
        val before = WakeRuntime.state.value
        val state = WakeRuntime.apply(event)
        engine?.setPaused(!state.processesAudio)
        engine?.setMicrophone(state.micOpen)
        // The watchdog (or a stop) ended the command: stop hearing it and free the speech recogniser.
        if (before is WakeState.Paused && WakeHold.COMMAND in before.holds && !(state is WakeState.Paused && WakeHold.COMMAND in state.holds)) {
            command?.takeIf { it.isActive && event !is WakeEvent.CommandDone }?.cancel()
        }
        if (state is WakeState.Stopped) teardown() else updateNotification(state)
        return state
    }

    private fun applyHolds() {
        if (!running) return
        transition(WakeEvent.Holds(externalHolds, SystemClock.elapsedRealtime()))
        scheduleTick() // a released hold resumes listening after a short pause
    }

    // --- one command after the wake phrase ---------------------------------------------------------------------------

    private fun commandLanguage(): CopilotLanguage =
        if (runCatching { graph.settings.settings.value.guideLanguage }.getOrNull() == "hi") CopilotLanguage.HI else CopilotLanguage.EN

    private fun startCommand() {
        command?.cancel()
        command = scope.launch {
            try {
                runCommand()
            } finally {
                withContext(NonCancellable) {
                    recognizer?.release()
                    if (running) {
                        transition(WakeEvent.CommandDone(SystemClock.elapsedRealtime()))
                        scheduleTick()
                    }
                }
            }
        }
    }

    private suspend fun runCommand() {
        val assistant = VoiceAssistant.get(this)
        val hindiUi = commandLanguage() == CopilotLanguage.HI
        assistant.cue(hindiUi)
        // The command, plus at most one answer to "Which one?".
        repeat(MAX_TURNS) {
            val heard = listen(hindiUi)
            val text = (heard as? Heard.Text)?.text
            if (text == null) {
                Log.i(TAG, "command not heard: ${(heard as Heard.Failed).why}")
                assistant.say(heard.reply, hindiUi)
                return
            }
            val turn = assistant.handle(text, AssistantSource.WAKE, preferHindi = hindiUi)
            turn.waiting?.let(::postContinueNotification)
            assistant.say(turn.reply, turn.hindi)
            if (turn.stopListening) {
                finish(WakeStopReason.USER)
                return
            }
            if (turn.choices.isEmpty()) return
        }
    }

    private sealed interface Heard {
        data class Text(val text: String) : Heard
        data class Failed(val reply: Said, val why: String) : Heard
    }

    /** Hears one utterance in the UI language; a missing Hindi pack falls back to English. */
    private suspend fun listen(hindi: Boolean): Heard {
        val r = recognizer ?: return Heard.Failed(AssistantCopy.noRecognizer, "no recogniser")
        if (!r.onDeviceAvailable) return Heard.Failed(AssistantCopy.noRecognizer, "no on-device recogniser")
        val first = listenOnce(r, if (hindi) CopilotLanguage.HI else CopilotLanguage.EN)
        if (hindi && first is Heard.Failed && first.why in LANGUAGE_ERRORS) return listenOnce(r, CopilotLanguage.EN)
        return first
    }

    private suspend fun listenOnce(r: OnDeviceRecognizer, language: CopilotLanguage): Heard =
        withTimeoutOrNull(LISTEN_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val started = r.start(language, object : OnDeviceRecognizer.Listener {
                    override fun onPartial(text: String) = Unit
                    override fun onFinal(text: String) {
                        if (cont.isActive) cont.resume(Heard.Text(text))
                    }
                    override fun onError(code: Int) {
                        if (cont.isActive) cont.resume(failure(code))
                    }
                    override fun onEnd() {
                        if (cont.isActive) cont.resume(Heard.Failed(AssistantCopy.notCaught, "ended"))
                    }
                })
                if (!started && cont.isActive) cont.resume(Heard.Failed(AssistantCopy.micUnavailable, "start failed"))
                cont.invokeOnCancellation { main.post { r.release() } }
            }
        } ?: run {
            r.release()
            Heard.Failed(AssistantCopy.notCaught, "timeout")
        }

    private fun failure(code: Int): Heard.Failed = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Heard.Failed(AssistantCopy.notCaught, "code $code")
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
            Heard.Failed(PACK_MISSING, LANGUAGE_ERROR_PREFIX + code)
        else -> Heard.Failed(AssistantCopy.micUnavailable, "code $code")
    }

    private fun continueIntent(): Intent = Intent(this, MainActivity::class.java)
        .setAction(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** The command needs TraceVeil's screen and Android did not let it come forward: the user taps to continue. */
    @SuppressLint("MissingPermission") // checked by notificationsAllowed() just below; a late revocation is caught
    private fun postContinueNotification(action: AssistantAction) {
        if (!WakeController.notificationsAllowed(this)) return
        val open = PendingIntent.getActivity(this, REQUEST_OPEN, continueIntent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CHANNEL_HEARD)
            .setSmallIcon(R.drawable.ic_wake_mic)
            .setContentTitle("Hey TraceVeil heard you")
            .setContentText(AssistantCopy.continueTitle(action))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setTimeoutAfter(HEARD_TIMEOUT_MS)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_HEARD, n)
        } catch (_: SecurityException) {
            // Notification permission was withdrawn after arming; the spoken reply still told the user what to do.
        }
    }

    // --- session upkeep ----------------------------------------------------------------------------------------------

    private fun tick() {
        if (!running) return
        val battery = WakeController.battery(this).let { if (batteryLowSignal) it.copy(systemLow = true) else it }
        transition(WakeEvent.Tick(SystemClock.elapsedRealtime(), battery, duration.limitMs))
        scheduleTick()
    }

    private fun scheduleTick() {
        main.removeCallbacks(tickRunnable)
        if (!running) return
        val state = WakeRuntime.state.value
        val startedAt = when (state) {
            is WakeState.Listening -> state.startedAtMs
            is WakeState.Responding -> state.startedAtMs
            is WakeState.Paused -> state.startedAtMs
            else -> null
        }
        val now = SystemClock.elapsedRealtime()
        val untilLimit = startedAt?.let { WakeSessionPolicy.remainingMs(it, now, duration.limitMs) } ?: TICK_MS
        val untilResume = (state as? WakeState.Responding)?.let { it.resumeAtMs - now } ?: TICK_MS
        main.postDelayed(tickRunnable, minOf(TICK_MS, untilLimit, untilResume).coerceAtLeast(MIN_TICK_MS))
    }

    /** Re-posts the listening notification only when its title changes (listening, hearing a command, paused). */
    @SuppressLint("MissingPermission") // notificationsAllowed() was checked when arming; a late revocation is caught
    private fun updateNotification(state: WakeState) {
        if (!running) return
        val title = when {
            state is WakeState.Paused && WakeHold.COMMAND in state.holds -> WakeCopy.HEARING_TITLE
            else -> WakeCopy.notificationTitle(paused = state is WakeState.Paused)
        }
        if (lastTitle == title) return
        lastTitle = title
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_LISTENING, listeningNotification(WakeRuntime.endsAtWallMs.value, title))
        } catch (_: SecurityException) {
        }
    }

    private fun listeningNotification(endsAtWallMs: Long?, title: String): Notification {
        val stop = PendingIntent.getService(
            this, REQUEST_STOP,
            Intent(this, HeyTraceVeilService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_REASON, WakeStopReason.USER.name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.ic_wake_mic)
            .setContentTitle(title)
            .setContentText(WakeCopy.notificationText(limited = endsAtWallMs != null))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(stop)
            .addAction(0, "Stop listening", stop)
        if (endsAtWallMs != null) {
            builder.setWhen(endsAtWallMs).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        } else {
            builder.setShowWhen(false)
        }
        return builder.build()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LISTENING, getString(R.string.wake_channel_listening), NotificationManager.IMPORTANCE_LOW)
                .apply { description = getString(R.string.wake_channel_listening_description) },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_HEARD, getString(R.string.wake_channel_heard), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = getString(R.string.wake_channel_heard_description) },
        )
    }

    private fun registerBattery() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                batteryLowSignal = intent.action == Intent.ACTION_BATTERY_LOW
                tick()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
        }
        runCatching { ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
            .onSuccess { batteryReceiver = receiver }
    }

    private fun finish(reason: WakeStopReason) {
        if (WakeRuntime.state.value.active) transition(WakeEvent.Stop(reason)) else teardown()
    }

    /** Releases the microphone first, then everything else; safe to call more than once. */
    private fun teardown() {
        engine?.stop()
        engine = null
        command?.cancel()
        command = null
        recognizer?.release()
        recognizer = null
        main.removeCallbacks(tickRunnable)
        main.removeCallbacks(startTimeout)
        stopRequests?.cancel()
        stopRequests = null
        holdWatch?.cancel()
        holdWatch = null
        lastTitle = null
        batteryReceiver?.let { runCatching { unregisterReceiver(it) } }
        batteryReceiver = null
        WakeRuntime.setEndsAt(null)
        if (running) {
            running = false
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        finish(WakeStopReason.APP_CLOSED)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (WakeRuntime.state.value.active) WakeRuntime.apply(WakeEvent.Stop(WakeStopReason.SYSTEM))
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.dpdpxray.app.wake.START"
        const val ACTION_STOP = "com.dpdpxray.app.wake.STOP"
        const val EXTRA_REASON = "reason"
        const val EXTRA_DURATION = "duration"
        private const val TAG = "HeyTraceVeil"
        private const val CHANNEL_LISTENING = "hey_traceveil_listening"
        private const val CHANNEL_HEARD = "hey_traceveil_heard"
        private const val NOTIFICATION_LISTENING = 4101
        private const val NOTIFICATION_HEARD = 4102
        private const val REQUEST_STOP = 4103
        private const val REQUEST_OPEN = 4104
        private const val TICK_MS = 15_000L
        private const val MIN_TICK_MS = 500L
        private const val START_TIMEOUT_MS = 60_000L
        private const val HEARD_TIMEOUT_MS = 2 * 60_000L
        private const val LISTEN_TIMEOUT_MS = 15_000L
        private const val MAX_TURNS = 2
        private const val LANGUAGE_ERROR_PREFIX = "language "
        private val LANGUAGE_ERRORS = setOf(
            LANGUAGE_ERROR_PREFIX + SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            LANGUAGE_ERROR_PREFIX + SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        )
        private val PACK_MISSING = Said(
            "The phone's on-device speech pack for this language is missing. Open the voice copilot and tap Download speech pack.",
            "इस भाषा का ऑन-डिवाइस स्पीच पैक फ़ोन में नहीं है। वॉइस कोपायलट खोलकर Download speech pack दबाइए।",
        )
    }
}
