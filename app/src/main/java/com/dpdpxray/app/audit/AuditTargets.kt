package com.dpdpxray.app.audit

/**
 * Which apps a check can run on, and how a check ends when the app or the capture goes away between plans. Every
 * message names the app and says what happened in plain words; nothing here guesses at a result.
 */
internal object AuditTargets {
    /** What the phone says about the target app before a check. */
    data class Facts(val installed: Boolean, val launchable: Boolean, val system: Boolean)

    /** Why a check cannot start on [pkg], or null when it can. */
    fun refusal(pkg: String, label: String, ownPackage: String, facts: Facts): String? = when {
        pkg == ownPackage -> "TraceVeil can't check itself: its own capture and consent watcher would only see their own work. Pick another app."
        !facts.installed -> "$label is not installed on this phone, so there is nothing to check."
        !facts.launchable -> "$label has no screen TraceVeil can open (no launcher entry), so it can't be checked this way."
        else -> null
    }

    fun systemNote(label: String) = "$label is a system app: Android may keep it running or start it again by itself, so a restart may not " +
        "take full effect, and TraceVeil never offers to clear a system app's data."

    fun uninstalled(label: String) = "$label was uninstalled during the check, so the remaining plans did not run."

    const val CAPTURE_STOPPED = "TraceVeil's capture stopped (the VPN was turned off, or another VPN took over), so the remaining plans did not run."

    fun stayedLocked(label: String) = "The phone stayed locked, so $label could not be opened and the remaining plans did not run."

    /** Why the next plan cannot run, or null when it can. Checked before every plan. */
    fun beforePlan(label: String, installed: Boolean, captureRunning: Boolean): String? = when {
        !installed -> uninstalled(label)
        !captureRunning -> CAPTURE_STOPPED
        else -> null
    }

    /** The app did not stay on screen after it was opened. */
    fun didNotOpen(label: String, crashed: Boolean): String =
        if (crashed) "$label closed with an error as it opened (Android showed that it stopped), so it could not be checked. Try opening it yourself first."
        else "$label did not come to the screen. Android may have blocked opening it from the background — start Shizuku, or keep TraceVeil open and retry."

    sealed interface Stop {
        /** Nothing was captured yet: the check fails with [message]. */
        data class Fail(val message: String) : Stop

        /** Plans already captured are saved, with [note] on the last of them. */
        data class KeepPartial(val note: String) : Stop
    }

    fun stop(captured: Int, reason: String): Stop = if (captured == 0) Stop.Fail(reason) else Stop.KeepPartial("$reason The plans before it are kept.")
}

/** Thrown inside a plan when the check cannot go on (see [AuditTargets.stop]). */
internal class PlanStopped(message: String) : IllegalStateException(message)

/** Small bounded waits, tested on the JVM with a fake clock. */
internal object Waits {
    /** Polls [done] until it is true (returns true) or [timeoutMs] passes (returns false). */
    suspend fun until(timeoutMs: Long, clockMs: () -> Long, pause: suspend (Long) -> Unit, intervalMs: Long = 1_000, done: suspend () -> Boolean): Boolean {
        val started = clockMs()
        while (true) {
            if (done()) return true
            val remaining = timeoutMs - (clockMs() - started)
            if (remaining <= 0) return false
            pause(minOf(intervalMs, remaining))
        }
    }
}
