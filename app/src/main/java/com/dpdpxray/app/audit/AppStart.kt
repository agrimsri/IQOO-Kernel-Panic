package com.dpdpxray.app.audit

import com.dpdpxray.core.model.StartMode

/**
 * Starts the audited app fresh before each plan, the way a real first launch would see it.
 *
 * - With Shizuku, every plan stops the app first (`am force-stop`), so its process, DNS cache and open connections are
 *   new and its launch-time lookups are made again where TraceVeil can see them. This is a **restart**: nothing is
 *   deleted and the person's data, sign-in and settings stay.
 * - Only when the person approved it for a test app is the data also cleared (`pm clear`): a **reset**.
 * - Without Shizuku the app is opened as it is (it may already be running), and the notes say so.
 *
 * Shell access is passed in, so the policy is tested on the JVM.
 */
internal class AppStart(
    private val shizukuReady: () -> Boolean,
    /** Whether the app has a running process now; null when that cannot be read. */
    private val processRunning: suspend (String) -> Boolean?,
    private val forceStop: suspend (String) -> Result<String>,
    private val clearData: suspend (String) -> Result<String>,
) {
    /** [freshState]: the data was actually cleared. [problem]: why a restart or reset did not happen, in plain words. */
    data class Outcome(val mode: StartMode, val freshState: Boolean, val runningBefore: Boolean?, val problem: String?, val notes: List<String>)

    suspend fun prepare(pkg: String, resetApproved: Boolean): Outcome {
        if (!shizukuReady()) {
            return Outcome(StartMode.NOT_RESTARTED, false, null, NO_SHIZUKU, listOf(NO_SHIZUKU_KEPT_NOTE, notRestartedNote(NO_SHIZUKU)))
        }
        val running = runCatching { processRunning(pkg) }.getOrNull()
        val stopped = forceStop(pkg)
        if (stopped.isFailure) {
            val why = "stopping it failed: ${stopped.exceptionOrNull()?.message ?: "unknown error"}"
            val dataNote = if (resetApproved) "Could not clear app data because stopping the test app failed: ${stopped.exceptionOrNull()?.message}" else KEPT_NOTE
            return Outcome(StartMode.NOT_RESTARTED, false, running, why, listOf(notRestartedNote(why), dataNote))
        }
        if (!resetApproved) return Outcome(StartMode.RESTARTED, false, running, null, listOf(RESTARTED_NOTE, KEPT_NOTE))
        val cleared = clearData(pkg)
        return if (cleared.isSuccess) Outcome(StartMode.RESET, true, running, null, listOf(RESET_NOTE))
        else Outcome(
            StartMode.RESTARTED, false, running, "clearing its data failed: ${cleared.exceptionOrNull()?.message}",
            listOf("Could not clear app data: ${cleared.exceptionOrNull()?.message}", RESTARTED_NOTE),
        )
    }

    companion object {
        const val RESTARTED_NOTE = "Restarted the app before this plan (data kept)."
        const val RESET_NOTE = "Reset the app before this plan (data cleared, as approved)."

        /** Starts with the marker the consent-history rules read (AuditSession.KEPT_DATA_NOTES). */
        const val KEPT_NOTE = "App data was not cleared (reset not approved) — results reflect the app's current state."
        const val NO_SHIZUKU_KEPT_NOTE = "App data was not cleared (reset not approved or Shizuku not authorised) — results reflect the app's current state."
        const val NO_SHIZUKU = "the Shizuku helper is not running or not allowed"

        fun notRestartedNote(why: String) =
            "Could not restart the app before this plan ($why), so if it was already open it may reuse lookups and connections it made earlier."
    }
}
