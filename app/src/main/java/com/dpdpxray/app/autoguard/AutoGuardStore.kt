package com.dpdpxray.app.autoguard

import android.content.Context
import androidx.core.content.edit

/**
 * Auto-guard's own small state, kept apart from the user settings: where the new-app check left off (the change
 * sequence and the apps installed at the last check, as package names and install times), which installs
 * were already alerted (shared with the live install receiver, so one install is never alerted twice), which updates
 * of checked apps were alerted (package and version code, likewise shared), and when each
 * background job last ran (shown in Settings so a person can see the checks are running). Stays on the phone.
 */
class AutoGuardStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun watchState(): WatchState? {
        if (!prefs.contains(K_SEQUENCE) || !prefs.contains(K_KNOWN)) return null
        return WatchState(
            sequence = prefs.getInt(K_SEQUENCE, 0),
            bootCount = if (prefs.contains(K_BOOT)) prefs.getInt(K_BOOT, 0) else null,
            known = NewAppWatch.decodeLedger(prefs.getStringSet(K_KNOWN, null)),
        )
    }

    /** [jobFinishedAt] is set only by the background job, so Settings shows when the job itself last ran. */
    fun saveWatch(state: WatchState, jobFinishedAt: Long?) = prefs.edit(commit = true) {
        putInt(K_SEQUENCE, state.sequence)
        if (state.bootCount != null) putInt(K_BOOT, state.bootCount) else remove(K_BOOT)
        putStringSet(K_KNOWN, NewAppWatch.encodeLedger(state.known))
        if (jobFinishedAt != null) putLong(K_NEW_APPS_LAST_RUN, jobFinishedAt)
    }

    /** Forgets where the check left off, so switching it on again starts with a fresh baseline. */
    fun clearWatch() = prefs.edit(commit = true) {
        remove(K_SEQUENCE); remove(K_BOOT); remove(K_KNOWN); remove(K_NEW_APPS_LAST_RUN)
    }

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

    // --- Update Watch: which (package, version code) updates were alerted ------------------------------------------

    fun alertedUpdates(): Map<UpdateAlertLedger.Key, Long> = UpdateAlertLedger.decode(prefs.getStringSet(K_UPDATE_LEDGER, null))

    /** False until the first update check has recorded what was already updated (see [UpdateAlertPlan]). */
    val updatesInitialised: Boolean get() = prefs.contains(K_UPDATE_LEDGER)

    /**
     * Records that this update is being alerted now. Returns false when it was already alerted, by the background check
     * or by the live receiver, since the audit it is compared with ([lastCheckedAtMs]). Check and record happen under
     * one process-wide lock.
     */
    fun claimUpdate(key: UpdateAlertLedger.Key, lastCheckedAtMs: Long): Boolean = synchronized(LOCK) {
        val ledger = alertedUpdates()
        if (!UpdateAlertLedger.isNew(ledger, key, lastCheckedAtMs)) return false
        prefs.edit(commit = true) { putStringSet(K_UPDATE_LEDGER, UpdateAlertLedger.encode(UpdateAlertLedger.record(ledger, key, System.currentTimeMillis()))) }
        true
    }

    /** Records updates as seen without alerting them, and marks Update Watch as initialised (even when [keys] is empty). */
    fun seedUpdates(keys: List<UpdateAlertLedger.Key>) = synchronized(LOCK) {
        var ledger = alertedUpdates()
        val now = System.currentTimeMillis()
        keys.forEach { ledger = UpdateAlertLedger.record(ledger, it, now) }
        prefs.edit(commit = true) { putStringSet(K_UPDATE_LEDGER, UpdateAlertLedger.encode(ledger)) }
    }

    /** When the background job (not an in-app check) last finished a new-app check. */
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
        const val K_KNOWN = "watch_known_apps"
        const val K_LEDGER = "alerted_installs"
        const val K_UPDATE_LEDGER = "alerted_updates"
        const val K_NEW_APPS_LAST_RUN = "new_apps_last_run"
        const val K_CHECKUP_LAST_RUN = "checkup_last_run"
        const val K_CHECKUP_TO_REVIEW = "checkup_last_to_review"
    }
}
