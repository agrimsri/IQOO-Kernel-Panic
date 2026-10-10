package com.dpdpxray.app.voice.wake

/** Plain on-screen and notification wording for Hey TraceVeil, kept in one place so it can be tested. */
object WakeCopy {
    const val HEARING_TITLE = "Hey TraceVeil heard you — listening for one command"

    fun notificationTitle(paused: Boolean): String =
        if (paused) "Hey TraceVeil is paused — tap to stop" else "Listening for “Hey TraceVeil” — tap to stop"

    fun notificationText(limited: Boolean): String =
        "Offline, on this phone only. Audio is never saved or sent. " +
            if (limited) "Stops by itself when the timer ends." else "Listens until you stop it."

    fun startLabel(duration: WakeDuration): String = when (duration) {
        WakeDuration.UNTIL_OFF -> "Start listening (until I turn it off)"
        else -> "Start listening (${duration.spelled})"
    }

    /** Shown while listening; [until] is a formatted clock time, or null for "until I turn it off". */
    fun listeningNote(until: String?, paused: Boolean): String = when {
        paused -> "Paused while the voice copilot or a check uses the microphone. It resumes by itself."
        until != null -> "Listening for “Hey TraceVeil” until $until. Audio stays on this phone and is never saved."
        else -> "Listening for “Hey TraceVeil” until you stop it. Audio stays on this phone and is never saved."
    }

    const val INTRO =
        "Off by default. When you press Start, TraceVeil listens on this phone only for “Hey TraceVeil”, for as long " +
            "as you choose, with a notification the whole time. Google stays your default assistant."

    const val DURATION_NOTE =
        "Without a choice it listens until you stop it while charging, and for 1 hour on battery. Every session stops at " +
            "15% battery (unless charging), from the notification's Stop button, or when you swipe TraceVeil away from recent apps."

    fun sensitivityNote(s: WakeSensitivity): String = when (s) {
        WakeSensitivity.STRICT ->
            "Strict: say “Hey TraceVeil” clearly, then pause. It ignores the phrase when other speech runs straight into it. Best for noisy rooms."
        WakeSensitivity.NORMAL ->
            "Normal: reacts to softer or faster speech, but may also react to similar-sounding speech nearby."
    }

    const val EVENTS_NOTE = "Counts only: no audio or words are kept. Ordinary speech that is not the phrase is not counted."

    const val BACKGROUND_NOTE =
        "It keeps listening with the screen off. Some phones stop background apps to save power; if it stops early, " +
            "allow TraceVeil to run in the background in its battery settings."
}
