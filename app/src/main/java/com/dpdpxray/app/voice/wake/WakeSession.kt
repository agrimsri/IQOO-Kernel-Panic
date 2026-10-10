package com.dpdpxray.app.voice.wake

/** A battery snapshot; [percent] is null when the phone does not report one. */
data class BatteryReading(val percent: Int?, val charging: Boolean, val systemLow: Boolean)

enum class WakeStopReason {
    USER, TIMEOUT, LOW_BATTERY, COPILOT_MIC, ENGINE_ERROR, MODEL_MISSING, PERMISSION_MISSING, NOTIFICATIONS_OFF,
    NOT_ALLOWED_NOW, APP_CLOSED, OPTED_OUT, SYSTEM;

    companion object {
        fun explain(reason: WakeStopReason): String = when (reason) {
            USER -> "You stopped listening."
            TIMEOUT -> "Stopped automatically after 15 minutes."
            LOW_BATTERY -> "Stopped because the battery is low (15% or less and not charging)."
            COPILOT_MIC -> "Stopped so the voice copilot can use the microphone."
            ENGINE_ERROR -> "The offline wake-word engine could not run on this phone."
            MODEL_MISSING -> "The wake-word model is not on this phone yet."
            PERMISSION_MISSING -> "Microphone permission is needed to listen for the wake word."
            NOTIFICATIONS_OFF -> "Notifications must be allowed so you can always see, and stop, the listening."
            NOT_ALLOWED_NOW -> "Android did not allow the microphone to start. Open TraceVeil and try again."
            APP_CLOSED -> "Stopped because TraceVeil was closed from recent apps."
            OPTED_OUT -> "Hey TraceVeil was switched off."
            SYSTEM -> "Android stopped the listening service."
        }
    }
}

/** Auto-stop rules: a session never outlives 15 minutes and never runs on a low, discharging battery. */
object WakeSessionPolicy {
    const val MAX_SESSION_MS = 15 * 60_000L
    const val LOW_BATTERY_PERCENT = 15

    fun elapsedMs(startedAtMs: Long, nowMs: Long): Long = (nowMs - startedAtMs).coerceAtLeast(0)

    fun remainingMs(startedAtMs: Long, nowMs: Long): Long = (MAX_SESSION_MS - elapsedMs(startedAtMs, nowMs)).coerceAtLeast(0)

    fun batteryLow(b: BatteryReading): Boolean =
        !b.charging && (b.systemLow || (b.percent != null && b.percent <= LOW_BATTERY_PERCENT))

    fun startProblem(battery: BatteryReading): WakeStopReason? = if (batteryLow(battery)) WakeStopReason.LOW_BATTERY else null

    fun stopReason(startedAtMs: Long, nowMs: Long, battery: BatteryReading): WakeStopReason? = when {
        batteryLow(battery) -> WakeStopReason.LOW_BATTERY
        elapsedMs(startedAtMs, nowMs) >= MAX_SESSION_MS -> WakeStopReason.TIMEOUT
        else -> null
    }
}

sealed interface WakeState {
    /** True while the service holds (or is opening) the microphone. */
    val active: Boolean get() = false

    /** True only while audio is fed to the recogniser; during a reply audio is read and discarded. */
    val processesAudio: Boolean get() = false

    data object Off : WakeState
    data object Starting : WakeState {
        override val active: Boolean get() = true
    }
    data class Listening(val startedAtMs: Long) : WakeState {
        override val active: Boolean get() = true
        override val processesAudio: Boolean get() = true
    }
    data class Responding(val startedAtMs: Long, val resumeAtMs: Long) : WakeState {
        override val active: Boolean get() = true
    }
    data class Stopped(val reason: WakeStopReason, val detail: String? = null) : WakeState

    companion object {
        // A getter, not a stored field: a stored field would read Off while Off is still initialising.
        val initial: WakeState get() = Off
    }
}

sealed interface WakeEvent {
    data object Arm : WakeEvent
    data class EngineReady(val nowMs: Long) : WakeEvent
    data class EngineFailed(val detail: String) : WakeEvent
    data class Heard(val nowMs: Long) : WakeEvent
    data class Tick(val nowMs: Long, val battery: BatteryReading) : WakeEvent
    data class Stop(val reason: WakeStopReason) : WakeEvent
}

/** Pure transition function for the wake-word session; the service only executes what the state says. */
object WakeMachine {
    /** Audio is ignored for this long after a detection, so the spoken reply cannot re-trigger it. */
    const val REPLY_PAUSE_MS = 6_000L

    fun next(state: WakeState, event: WakeEvent): WakeState = when (event) {
        WakeEvent.Arm -> if (state.active) state else WakeState.Starting
        is WakeEvent.EngineReady -> if (state == WakeState.Starting) WakeState.Listening(event.nowMs) else state
        is WakeEvent.EngineFailed -> if (state.active) WakeState.Stopped(WakeStopReason.ENGINE_ERROR, event.detail) else state
        is WakeEvent.Heard -> if (state is WakeState.Listening) WakeState.Responding(state.startedAtMs, event.nowMs + REPLY_PAUSE_MS) else state
        is WakeEvent.Tick -> tick(state, event)
        is WakeEvent.Stop -> if (state.active) WakeState.Stopped(event.reason) else state
    }

    private fun tick(state: WakeState, e: WakeEvent.Tick): WakeState = when (state) {
        WakeState.Starting -> if (WakeSessionPolicy.batteryLow(e.battery)) WakeState.Stopped(WakeStopReason.LOW_BATTERY) else state
        is WakeState.Listening -> WakeSessionPolicy.stopReason(state.startedAtMs, e.nowMs, e.battery)?.let { WakeState.Stopped(it) } ?: state
        is WakeState.Responding -> WakeSessionPolicy.stopReason(state.startedAtMs, e.nowMs, e.battery)?.let { WakeState.Stopped(it) }
            ?: if (e.nowMs >= state.resumeAtMs) WakeState.Listening(state.startedAtMs) else state
        else -> state
    }
}

/** Preconditions checked before the microphone service may start. */
object WakeArming {
    fun problem(modelReady: Boolean, micGranted: Boolean, notificationsAllowed: Boolean, battery: BatteryReading, copilotOpen: Boolean): WakeStopReason? = when {
        !modelReady -> WakeStopReason.MODEL_MISSING
        !micGranted -> WakeStopReason.PERMISSION_MISSING
        !notificationsAllowed -> WakeStopReason.NOTIFICATIONS_OFF
        copilotOpen -> WakeStopReason.COPILOT_MIC
        else -> WakeSessionPolicy.startProblem(battery)
    }
}

enum class WakeLaunch { DIRECT, NOTIFICATION, NONE }

/**
 * What happens after the phrase is heard. Android 10+ blocks activity starts from the background, so the copilot is
 * opened directly only while TraceVeil is on screen; otherwise a high-priority notification is posted for the user to
 * tap. The spoken reply only promises what will actually happen.
 */
object WakeResponse {
    fun launch(appVisible: Boolean, notificationsAllowed: Boolean): WakeLaunch = when {
        appVisible -> WakeLaunch.DIRECT
        notificationsAllowed -> WakeLaunch.NOTIFICATION
        else -> WakeLaunch.NONE
    }

    fun reply(launch: WakeLaunch, hasLatestCheck: Boolean): String = when (launch) {
        WakeLaunch.DIRECT ->
            if (hasLatestCheck) "Yes? Opening your last check." else "Yes? You have no saved checks yet. Opening TraceVeil."
        WakeLaunch.NOTIFICATION ->
            if (hasLatestCheck) "Yes? Tap the TraceVeil notification to see your last check."
            else "Yes? You have no saved checks yet. Tap the TraceVeil notification to open the app."
        WakeLaunch.NONE ->
            if (hasLatestCheck) "Yes? Open TraceVeil to see your last check." else "Yes? You have no saved checks yet."
    }
}
