package com.dpdpxray.app.voice.wake

/** A battery snapshot; [percent] is null when the phone does not report one. */
data class BatteryReading(val percent: Int?, val charging: Boolean, val systemLow: Boolean)

/**
 * How long one listening session may run, chosen in Settings. [limitMs] is null for "until I turn it off"; the
 * low-battery stop, the notification's Stop action and swiping TraceVeil away still end every session.
 */
enum class WakeDuration(val limitMs: Long?, val label: String, val hindi: String) {
    MIN_15(15 * 60_000L, "15 min", "15 मिनट"),
    HOUR_1(60 * 60_000L, "1 hour", "1 घंटा"),
    UNTIL_OFF(null, "Until I turn it off", "जब तक मैं बंद न करूँ");

    /** Spoken/written length for the stop explanation, e.g. "15 minutes". */
    val spelled: String get() = when (this) {
        MIN_15 -> "15 minutes"
        HOUR_1 -> "1 hour"
        UNTIL_OFF -> "until you stop it"
    }

    companion object {
        /**
         * No choice saved yet. On the charger there is no battery cost, so it listens until stopped (a plugged-in demo
         * phone should not go deaf mid-presentation). On battery one hour covers a demo or a meeting and bounds a
         * forgotten session; the 15% low-battery stop applies either way.
         */
        fun default(charging: Boolean): WakeDuration = if (charging) UNTIL_OFF else HOUR_1

        fun effective(saved: WakeDuration?, charging: Boolean): WakeDuration = saved ?: default(charging)

        fun fromName(name: String?): WakeDuration? = entries.firstOrNull { it.name == name }
    }
}

enum class WakeStopReason {
    USER, TIMEOUT, LOW_BATTERY, COPILOT_MIC, ENGINE_ERROR, MODEL_MISSING, PERMISSION_MISSING, NOTIFICATIONS_OFF,
    NOT_ALLOWED_NOW, APP_CLOSED, OPTED_OUT, SYSTEM;

    companion object {
        fun explain(reason: WakeStopReason, duration: WakeDuration? = null): String = when (reason) {
            USER -> "You stopped listening."
            TIMEOUT -> if (duration?.limitMs != null) "Stopped automatically after ${duration.spelled}, as you chose."
                else "Stopped automatically at the end of the time you chose."
            LOW_BATTERY -> "Stopped because the battery is low (15% or less and not charging)."
            COPILOT_MIC -> "Close the voice copilot first: only one recogniser can use the microphone at a time."
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

/** Auto-stop rules: a session never outlives the chosen length and never runs on a low, discharging battery. */
object WakeSessionPolicy {
    const val LOW_BATTERY_PERCENT = 15

    fun elapsedMs(startedAtMs: Long, nowMs: Long): Long = (nowMs - startedAtMs).coerceAtLeast(0)

    /** Time left in the session, or null when it runs until the user stops it. */
    fun remainingMs(startedAtMs: Long, nowMs: Long, limitMs: Long?): Long? =
        limitMs?.let { (it - elapsedMs(startedAtMs, nowMs)).coerceAtLeast(0) }

    fun batteryLow(b: BatteryReading): Boolean =
        !b.charging && (b.systemLow || (b.percent != null && b.percent <= LOW_BATTERY_PERCENT))

    fun startProblem(battery: BatteryReading): WakeStopReason? = if (batteryLow(battery)) WakeStopReason.LOW_BATTERY else null

    fun stopReason(startedAtMs: Long, nowMs: Long, battery: BatteryReading, limitMs: Long?): WakeStopReason? = when {
        batteryLow(battery) -> WakeStopReason.LOW_BATTERY
        limitMs != null && elapsedMs(startedAtMs, nowMs) >= limitMs -> WakeStopReason.TIMEOUT
        else -> null
    }
}

/** Why the wake recogniser has let go of the microphone for a while; the session and its clock continue. */
enum class WakeHold {
    /** Hearing and acting on one spoken command after "Hey TraceVeil". */
    COMMAND,

    /** The voice copilot screen is open; its "Tap to speak" may use the microphone. */
    COPILOT,

    /** A check started by voice is running (the user is tapping in another app), or TraceVeil is speaking. */
    ASSISTANT,
}

sealed interface WakeState {
    /** True while the service holds (or is opening) a session. */
    val active: Boolean get() = false

    /** True only while audio is fed to the recogniser; otherwise audio is read and discarded, or not read at all. */
    val processesAudio: Boolean get() = false

    /** True while the wake engine should keep its microphone open; false lets another recogniser use it. */
    val micOpen: Boolean get() = false

    data object Off : WakeState
    data object Starting : WakeState {
        override val active: Boolean get() = true
        override val micOpen: Boolean get() = true
    }
    data class Listening(val startedAtMs: Long) : WakeState {
        override val active: Boolean get() = true
        override val processesAudio: Boolean get() = true
        override val micOpen: Boolean get() = true
    }

    /** A short pause after a command or a hold, so the tail of a spoken reply cannot trigger the phrase again. */
    data class Responding(val startedAtMs: Long, val resumeAtMs: Long) : WakeState {
        override val active: Boolean get() = true
        override val micOpen: Boolean get() = true
    }

    /** The microphone is released while something else needs it; listening resumes when every hold is gone. */
    data class Paused(val startedAtMs: Long, val holds: Set<WakeHold>, val commandUntilMs: Long? = null) : WakeState {
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

    /** The command after the phrase has been answered (or abandoned). */
    data class CommandDone(val nowMs: Long) : WakeEvent

    /** The holds that come from outside the service: the copilot screen, a running voice check, assistant speech. */
    data class Holds(val external: Set<WakeHold>, val nowMs: Long) : WakeEvent

    /** [limitMs] is the chosen session length, null for "until I turn it off". */
    data class Tick(val nowMs: Long, val battery: BatteryReading, val limitMs: Long?) : WakeEvent
    data class Stop(val reason: WakeStopReason) : WakeEvent
}

/** Pure transition function for the wake-word session; the service only executes what the state says. */
object WakeMachine {
    /** After the last hold ends, audio is ignored this long so the end of a spoken reply cannot re-trigger it. */
    const val RESUME_PAUSE_MS = 1_500L

    /** A command (cue, listening, one follow-up question, reply) that takes longer than this is abandoned. */
    const val COMMAND_MAX_MS = 60_000L

    fun next(state: WakeState, event: WakeEvent): WakeState = when (event) {
        WakeEvent.Arm -> if (state.active) state else WakeState.Starting
        is WakeEvent.EngineReady -> if (state == WakeState.Starting) WakeState.Listening(event.nowMs) else state
        is WakeEvent.EngineFailed -> if (state.active) WakeState.Stopped(WakeStopReason.ENGINE_ERROR, event.detail) else state
        is WakeEvent.Heard ->
            if (state is WakeState.Listening) WakeState.Paused(state.startedAtMs, setOf(WakeHold.COMMAND), event.nowMs + COMMAND_MAX_MS) else state
        is WakeEvent.CommandDone ->
            if (state is WakeState.Paused && WakeHold.COMMAND in state.holds) withHolds(state.startedAtMs, state.holds - WakeHold.COMMAND, null, event.nowMs) else state
        is WakeEvent.Holds -> holds(state, event)
        is WakeEvent.Tick -> tick(state, event)
        is WakeEvent.Stop -> if (state.active) WakeState.Stopped(event.reason) else state
    }

    private fun holds(state: WakeState, e: WakeEvent.Holds): WakeState {
        val external = e.external - WakeHold.COMMAND
        return when (state) {
            is WakeState.Listening -> if (external.isEmpty()) state else WakeState.Paused(state.startedAtMs, external)
            is WakeState.Responding -> if (external.isEmpty()) state else WakeState.Paused(state.startedAtMs, external)
            is WakeState.Paused -> {
                val next = external + state.holds.filter { it == WakeHold.COMMAND }
                if (next == state.holds) state else withHolds(state.startedAtMs, next, state.commandUntilMs, e.nowMs)
            }
            // Starting has no microphone to give yet; the service re-applies holds once the engine is ready.
            else -> state
        }
    }

    private fun withHolds(startedAtMs: Long, holds: Set<WakeHold>, commandUntilMs: Long?, nowMs: Long): WakeState =
        if (holds.isEmpty()) WakeState.Responding(startedAtMs, nowMs + RESUME_PAUSE_MS)
        else WakeState.Paused(startedAtMs, holds, commandUntilMs.takeIf { WakeHold.COMMAND in holds })

    private fun tick(state: WakeState, e: WakeEvent.Tick): WakeState {
        fun stop(startedAtMs: Long) = WakeSessionPolicy.stopReason(startedAtMs, e.nowMs, e.battery, e.limitMs)?.let { WakeState.Stopped(it) }
        return when (state) {
            WakeState.Starting -> if (WakeSessionPolicy.batteryLow(e.battery)) WakeState.Stopped(WakeStopReason.LOW_BATTERY) else state
            is WakeState.Listening -> stop(state.startedAtMs) ?: state
            is WakeState.Responding -> stop(state.startedAtMs) ?: if (e.nowMs >= state.resumeAtMs) WakeState.Listening(state.startedAtMs) else state
            is WakeState.Paused -> stop(state.startedAtMs)
                ?: if (state.commandUntilMs != null && e.nowMs >= state.commandUntilMs) next(state, WakeEvent.CommandDone(e.nowMs)) else state
            else -> state
        }
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
 * How a command that needs TraceVeil's screen reaches it. Android 10+ blocks activity starts from the background, so
 * the screen changes directly only while TraceVeil is on screen (or Android lets it come forward); otherwise a
 * high-priority notification is posted for the user to tap. The spoken reply only promises what will actually happen.
 */
object WakeResponse {
    fun launch(appVisible: Boolean, notificationsAllowed: Boolean): WakeLaunch = when {
        appVisible -> WakeLaunch.DIRECT
        notificationsAllowed -> WakeLaunch.NOTIFICATION
        else -> WakeLaunch.NONE
    }
}
