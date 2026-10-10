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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dpdpxray.app.MainActivity
import com.dpdpxray.app.R
import com.dpdpxray.app.audit.AuditProgress
import com.dpdpxray.app.graph
import com.dpdpxray.app.voice.AnswerVoice
import com.dpdpxray.core.copilot.CopilotLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

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
 * - On a detection it speaks a short offline reply and opens the copilot directly only while TraceVeil is visible;
 *   otherwise it posts a high-priority notification the user taps (Android blocks background activity starts).
 */
class HeyTraceVeilService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var engine: VoskWakeEngine? = null
    private var voice: AnswerVoice? = null
    private var batteryLowSignal = false
    private var batteryReceiver: BroadcastReceiver? = null
    private var running = false
    private var stopRequests: Job? = null
    private var holdWatch: Job? = null
    private var duration: WakeDuration = WakeDuration.HOUR_1
    private var lastNotified: Boolean? = null

    private val tickRunnable = Runnable { tick() }
    private val resumeRunnable = Runnable {
        transition(WakeEvent.CommandDone(SystemClock.elapsedRealtime()))
        scheduleTick()
    }
    private var externalHolds: Set<WakeHold> = emptySet()
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
            ServiceCompat.startForeground(this, NOTIFICATION_LISTENING, listeningNotification(endsAt, paused = false), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
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
        voice = AnswerVoice(this) { } // the phone's offline text-to-speech; no network voice is used here
        stopRequests = scope.launch { WakeRuntime.stopRequests.collect { finish(it) } }
        engine = VoskWakeEngine(modelDir, WakePhrase.grammarJson(), engineListener).also { it.start() }
        main.postDelayed(startTimeout, START_TIMEOUT_MS)
        holdWatch = scope.launch {
            combine(WakeRuntime.copilotOpenFlow, graph.orchestrator.progress) { copilot, progress ->
                buildSet {
                    if (copilot) add(WakeHold.COPILOT)
                    if (progress is AuditProgress.Running) add(WakeHold.ASSISTANT)
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
            if (state is WakeState.Paused) {
                respond()
                main.removeCallbacks(resumeRunnable)
                main.postDelayed(resumeRunnable, REPLY_PAUSE_MS)
            }
        }

        override fun onError(detail: String) {
            Log.i(TAG, "wake engine stopped: $detail")
            transition(WakeEvent.EngineFailed(detail))
        }
    }

    /** Applies one event and makes the engine and service follow the resulting state. */
    private fun transition(event: WakeEvent): WakeState {
        val state = WakeRuntime.apply(event)
        engine?.setPaused(!state.processesAudio)
        engine?.setMicrophone(state.micOpen)
        if (state is WakeState.Stopped) teardown() else updateNotification(state)
        return state
    }

    private fun applyHolds() {
        if (running) transition(WakeEvent.Holds(externalHolds, SystemClock.elapsedRealtime()))
    }

    /** Re-posts the listening notification only when it switches between listening and paused. */
    @SuppressLint("MissingPermission") // notificationsAllowed() was checked when arming; a late revocation is caught
    private fun updateNotification(state: WakeState) {
        if (!running) return
        val paused = state is WakeState.Paused
        if (lastNotified == paused) return
        lastNotified = paused
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_LISTENING, listeningNotification(WakeRuntime.endsAtWallMs.value, paused))
        } catch (_: SecurityException) {
        }
    }

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
        val untilLimit = startedAt?.let { WakeSessionPolicy.remainingMs(it, SystemClock.elapsedRealtime(), duration.limitMs) } ?: TICK_MS
        main.postDelayed(tickRunnable, minOf(TICK_MS, untilLimit).coerceAtLeast(MIN_TICK_MS))
    }

    private fun respond() {
        val latest = runCatching { graph.repository.audits.value.firstOrNull() }.getOrNull()
        val launch = WakeResponse.launch(WakeRuntime.uiVisible, WakeController.notificationsAllowed(this))
        val reply = when {
            latest == null -> "Yes? You have no saved checks yet."
            launch == WakeLaunch.DIRECT -> "Yes? Opening your last check."
            else -> "Yes? Tap the TraceVeil notification to see your last check."
        }
        voice?.let { v -> scope.launch { v.speak(reply, CopilotLanguage.EN) } }
        when (launch) {
            WakeLaunch.DIRECT -> runCatching { startActivity(copilotIntent()) }.onFailure { postHeardNotification(latest != null) }
            WakeLaunch.NOTIFICATION -> postHeardNotification(latest != null)
            WakeLaunch.NONE -> Unit
        }
    }

    private fun copilotIntent(): Intent = Intent(this, MainActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .putExtra(AssistantFeatures.EXTRA_FEATURE, AssistantFeature.ASK_LAST_CHECK.id)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    @SuppressLint("MissingPermission") // checked by notificationsAllowed() just below; a late revocation is caught
    private fun postHeardNotification(hasLatest: Boolean) {
        if (!WakeController.notificationsAllowed(this)) return
        val open = PendingIntent.getActivity(this, REQUEST_OPEN, copilotIntent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CHANNEL_HEARD)
            .setSmallIcon(R.drawable.ic_wake_mic)
            .setContentTitle("Hey TraceVeil heard you")
            .setContentText(if (hasLatest) "Tap to open your last check." else "Tap to open TraceVeil. You have no saved checks yet.")
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

    private fun listeningNotification(endsAtWallMs: Long?, paused: Boolean): Notification {
        val stop = PendingIntent.getService(
            this, REQUEST_STOP,
            Intent(this, HeyTraceVeilService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_REASON, WakeStopReason.USER.name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.ic_wake_mic)
            .setContentTitle(WakeCopy.notificationTitle(paused))
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
        main.removeCallbacks(tickRunnable)
        main.removeCallbacks(resumeRunnable)
        main.removeCallbacks(startTimeout)
        stopRequests?.cancel()
        stopRequests = null
        holdWatch?.cancel()
        holdWatch = null
        lastNotified = null
        batteryReceiver?.let { runCatching { unregisterReceiver(it) } }
        batteryReceiver = null
        // Let a reply that is already being spoken finish, then free the text-to-speech engine.
        voice?.let { v -> main.postDelayed({ v.release() }, REPLY_PAUSE_MS) }
        voice = null
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
        private const val REPLY_PAUSE_MS = 6_000L
    }
}
