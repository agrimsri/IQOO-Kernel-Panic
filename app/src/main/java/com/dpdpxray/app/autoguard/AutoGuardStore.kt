package com.dpdpxray.app.autoguard

import android.content.Context
import androidx.core.content.edit

/**
 * Auto-guard's own small state, kept apart from the user settings: where the new-app check left off, which installs
 * were already alerted (shared with the live install receiver, so one install is never alerted twice), and when each
 * background job last ran (shown in Settings so a person can see the checks are running). Stays on the phone.
 */
class AutoGuardStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun watchState(): WatchState? {
        if (!prefs.contains(K_CHECKED_AT)) return null
        return WatchState(
            sequence = prefs.getInt(K_SEQUENCE, 0),
            bootCount = if (prefs.contains(K_BOOT)) prefs.getInt(K_BOOT, 0) else null,
            checkedAtMs = prefs.getLong(K_CHECKED_AT, 0L),
        )
    }

    fun saveWatch(state: WatchState, finishedAt: Long) = prefs.edit(commit = true) {
        putInt(K_SEQUENCE, state.sequence)
        if (state.bootCount != null) putInt(K_BOOT, state.bootCount) else remove(K_BOOT)
        putLong(K_CHECKED_AT, state.checkedAtMs)
        putLong(K_NEW_APPS_LAST_RUN, finishedAt)
    }

    /** Forgets where the check left off, so switching it on again starts with a fresh baseline. */
    fun clearWatch() = prefs.edit(commit = true) { remove(K_SEQUENCE); remove(K_BOOT); remove(K_CHECKED_AT) }

    fun alerted(): Map<String, Long> = NewAppWatch.decodeLedger(prefs.getStringSet(K_LEDGER, null))

    /**
     * Records that this install is being alerted now. Returns false when it was already alerted, by the background
     * check or by the live receiver. Check and record happen under one process-wide lock.
     */
    fun claim(packageName: String, firstInstallTime: Long): Boolean = synchronized(LOCK) {
        val ledger = alerted()
        if (!NewAppWatch.isNew(ledger, packageName, firstInstallTime)) return false
        prefs.edit(commit = true) { putStringSet(K_LEDGER, NewAppWatch.encodeLedger(NewAppWatch.record(ledger, packageName, firstInstallTime))) }
        true
    }

    val newAppsLastRunAt: Long? get() = prefs.getLong(K_NEW_APPS_LAST_RUN, 0L).takeIf { it > 0L }
    val checkupLastRunAt: Long? get() = prefs.getLong(K_CHECKUP_LAST_RUN, 0L).takeIf { it > 0L }
    val checkupLastToReview: Int? get() = if (prefs.contains(K_CHECKUP_TO_REVIEW)) prefs.getInt(K_CHECKUP_TO_REVIEW, 0) else null

    fun saveCheckupRun(at: Long, appsToReview: Int) = prefs.edit(commit = true) {
        putLong(K_CHECKUP_LAST_RUN, at)
        putInt(K_CHECKUP_TO_REVIEW, appsToReview)
    }

    private companion object {
        val LOCK = Any()
        const val FILE = "autoguard"
        const val K_SEQUENCE = "watch_sequence"
        const val K_BOOT = "watch_boot_count"
        const val K_CHECKED_AT = "watch_checked_at"
        const val K_LEDGER = "alerted_installs"
        const val K_NEW_APPS_LAST_RUN = "new_apps_last_run"
        const val K_CHECKUP_LAST_RUN = "checkup_last_run"
        const val K_CHECKUP_TO_REVIEW = "checkup_last_to_review"
    }
}
