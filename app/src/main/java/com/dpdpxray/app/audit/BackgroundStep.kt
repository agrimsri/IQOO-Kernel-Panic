package com.dpdpxray.app.audit

import java.util.Locale

/**
 * Sends the audited app to the background (Android's Home action) and confirms it has left the screen. Only a confirmed
 * moment is recorded as the start of the background window; anything else leaves background behaviour not established.
 * Capture keeps running throughout, so this adds a window after the plan's observation and never shortens it.
 */
internal class BackgroundStep(
    /** Presses Home through the accessibility service; false when it is not connected or the action failed. */
    private val pressHome: suspend () -> Boolean,
    /** Whether a window of the audited app is still on screen; null when the screen cannot be read. */
    private val targetOnScreen: suspend () -> Boolean?,
    private val clockNanos: () -> Long,
    private val pause: suspend (Long) -> Unit,
    private val pollMs: Long = 200,
) {
    sealed interface Outcome {
        data class Confirmed(val atNanos: Long) : Outcome
        data class NotConfirmed(val reason: String) : Outcome
    }

    suspend fun run(confirmWithinMs: Long = CONFIRM_WITHIN_MS): Outcome {
        if (!pressHome()) return Outcome.NotConfirmed("Home could not be pressed (accessibility service not connected or the action failed)")
        var waited = 0L
        var readable = false
        while (true) {
            when (targetOnScreen()) {
                false -> return Outcome.Confirmed(clockNanos())
                true -> readable = true
                null -> Unit
            }
            if (waited >= confirmWithinMs) break
            pause(pollMs)
            waited += pollMs
        }
        return Outcome.NotConfirmed(
            if (readable) "the app was still on screen ${secs(confirmWithinMs * 1_000_000L)} s after Home was pressed"
            else "the screen could not be read to confirm the app had left it",
        )
    }

    companion object {
        const val CONFIRM_WITHIN_MS = 3_000L

        fun confirmedNote(sinceLaunchNanos: Long, watchMs: Long): String =
            "Background check: Home was pressed after the observation window; the app was confirmed off screen " +
                "${secs(sinceLaunchNanos)} s after launch and watched for ${watchMs / 1000} s more."

        fun notConfirmedNote(reason: String): String = "Background check not established: $reason."

        private fun secs(nanos: Long) = String.format(Locale.US, "%.1f", nanos / 1e9)
    }
}
