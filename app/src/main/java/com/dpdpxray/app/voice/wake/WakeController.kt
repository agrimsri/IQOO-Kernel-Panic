package com.dpdpxray.app.voice.wake

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

/** Process-wide wake-word state, written only by [HeyTraceVeilService] and read by Settings and the copilot guard. */
object WakeRuntime {
    private val _state = MutableStateFlow(WakeState.initial)
    val state: StateFlow<WakeState> = _state.asStateFlow()

    /** Wall-clock time the current session stops by itself, or null when not listening. */
    private val _endsAtWallMs = MutableStateFlow<Long?>(null)
    val endsAtWallMs: StateFlow<Long?> = _endsAtWallMs.asStateFlow()

    private val _stopRequests = MutableSharedFlow<WakeStopReason>(extraBufferCapacity = 4)
    internal val stopRequests: SharedFlow<WakeStopReason> = _stopRequests.asSharedFlow()

    /** The length of the current or most recent session, or null before the first one. */
    private val _duration = MutableStateFlow<WakeDuration?>(null)
    val duration: StateFlow<WakeDuration?> = _duration.asStateFlow()

    /** Accepted and rejected wake-phrase decisions since TraceVeil started: outcomes and confidences only. */
    private val _events = MutableStateFlow(WakeEventLog())
    val events: StateFlow<WakeEventLog> = _events.asStateFlow()

    private val _uiVisible = MutableStateFlow(false)
    /** True while a TraceVeil activity is started (visible); set by [HeyTraceVeilHooks]. */
    val uiVisibleFlow: StateFlow<Boolean> = _uiVisible.asStateFlow()
    var uiVisible: Boolean
        get() = _uiVisible.value
        set(value) { _uiVisible.value = value }

    private val _copilotOpen = MutableStateFlow(false)
    /** True while the voice copilot screen is the current destination; the wake word lets go of the microphone. */
    val copilotOpenFlow: StateFlow<Boolean> = _copilotOpen.asStateFlow()
    var copilotOpen: Boolean
        get() = _copilotOpen.value
        set(value) { _copilotOpen.value = value }

    internal fun apply(event: WakeEvent): WakeState = _state.updateAndGet { WakeMachine.next(it, event) }

    internal fun record(heard: WakeHeard, sensitivity: WakeSensitivity, atWallMs: Long) {
        _events.update { it.record(heard, sensitivity, atWallMs) }
    }

    internal fun setDuration(value: WakeDuration?) {
        _duration.value = value
    }

    /** Records why a start was refused, without touching an active session. */
    internal fun refuse(reason: WakeStopReason, detail: String? = null) {
        _state.updateAndGet { if (it.active) it else WakeState.Stopped(reason, detail) }
    }

    internal fun setEndsAt(wallMs: Long?) {
        _endsAtWallMs.value = wallMs
    }

    internal fun requestStop(reason: WakeStopReason) {
        _stopRequests.tryEmit(reason)
    }
}

/** Entry points used by the UI. Starting is only ever triggered by a user tap while TraceVeil is on screen. */
object WakeController {
    fun micGranted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun notificationsAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    /** Runtime permissions still needed before listening can start (requested from the Settings button). */
    fun missingPermissions(context: Context): Array<String> = buildList {
        if (!micGranted(context)) add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    fun battery(context: Context): BatteryReading {
        // ACTION_BATTERY_CHANGED is a sticky system broadcast: reading it registers nothing.
        val i = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
            ?: return BatteryReading(null, charging = false, systemLow = false)
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 ||
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val low = Build.VERSION.SDK_INT >= 28 && i.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW, false)
        return BatteryReading(if (level >= 0 && scale > 0) level * 100 / scale else null, charging, low)
    }

    fun armProblem(context: Context): WakeStopReason? = WakeArming.problem(
        modelReady = WakeModelManager.get(context).isReady(),
        micGranted = micGranted(context),
        notificationsAllowed = notificationsAllowed(context),
        battery = battery(context),
        copilotOpen = WakeRuntime.copilotOpen,
    )

    /**
     * Starts listening; call only from an explicit user tap while TraceVeil is in the foreground. A plain started
     * service is promoted to a microphone foreground service inside the service, so a refused start never leaves
     * a pending foreground-service obligation behind. Returns why it could not start, or null.
     */
    fun start(context: Context, duration: WakeDuration): WakeStopReason? {
        armProblem(context)?.let {
            WakeRuntime.refuse(it)
            return it
        }
        return runCatching {
            context.startService(
                Intent(context, HeyTraceVeilService::class.java)
                    .setAction(HeyTraceVeilService.ACTION_START)
                    .putExtra(HeyTraceVeilService.EXTRA_DURATION, duration.name),
            )
            null
        }.getOrElse {
            WakeRuntime.refuse(WakeStopReason.NOT_ALLOWED_NOW, it.message)
            WakeStopReason.NOT_ALLOWED_NOW
        }
    }

    /** Asks a running session to stop; does nothing (and starts nothing) when not listening. */
    fun stop(reason: WakeStopReason) {
        if (WakeRuntime.state.value.active) WakeRuntime.requestStop(reason)
    }
}
