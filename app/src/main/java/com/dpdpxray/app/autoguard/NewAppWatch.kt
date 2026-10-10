package com.dpdpxray.app.autoguard

/** Where the background new-app check left off. Saved after every completed check. */
data class WatchState(
    /** Android's package-change sequence number after the last check. Android restarts it at 0 on every boot. */
    val sequence: Int,
    /** Settings.Global.BOOT_COUNT when [sequence] was read; null when Android did not report it. */
    val bootCount: Int?,
    /** Wall-clock time the last check started. Apps first installed before it (minus [NewAppWatch.MARGIN_MS]) are not new. */
    val checkedAtMs: Long,
)

/** One installed package the check is looking at, copied out of PackageInfo so the decision is testable. */
data class ChangedApp(val packageName: String, val firstInstallTime: Long, val isSystem: Boolean)

/**
 * Pure rules for the background new-app check (no Android types). The check asks Android which packages changed since
 * the last check (PackageManager.getChangedPackages) and alerts only for apps that were newly installed since then:
 * never for updates, apps that came with the phone, TraceVeil itself, or an install that was already alerted (by this
 * check or by the live install receiver). The very first check only records a baseline, so apps that were already on
 * the phone never produce an alert.
 */
object NewAppWatch {
    /**
     * Android records an app's first-install time slightly before it bumps the change counter. This margin keeps an app
     * that was installed during the previous check from being mistaken for an old one.
     */
    const val MARGIN_MS = 2 * 60_000L

    /** Up to this many new apps get one notification each; more than that share one notification. */
    const val MAX_SEPARATE_ALERTS = 3

    /** Alerted installs remembered for de-duplication; the oldest are forgotten first. */
    const val LEDGER_LIMIT = 300

    sealed interface Read {
        /** Nothing saved yet (first run, or the check was just switched on): record a baseline and alert nothing. */
        data object Baseline : Read

        /** Same boot as last time: ask Android for packages changed since [sequence]. */
        data class Since(val sequence: Int) : Read

        /**
         * The phone restarted (Android's change counter started again at 0) or the boot count is unreadable, so the saved
         * sequence cannot be trusted. Look at every installed app; the time rule below still skips every old app.
         */
        data object AllInstalled : Read
    }

    fun read(saved: WatchState?, bootCount: Int?): Read = when {
        saved == null -> Read.Baseline
        bootCount == null || saved.bootCount == null || bootCount != saved.bootCount -> Read.AllInstalled
        else -> Read.Since(saved.sequence)
    }

    /**
     * The apps to alert about, oldest install first. [alerted] maps a package to the first-install time that was already
     * alerted; a reinstall has a new first-install time and is alerted again, an update keeps it and is not.
     */
    fun newInstalls(candidates: List<ChangedApp>, ownPackage: String, saved: WatchState, alerted: Map<String, Long>): List<ChangedApp> =
        candidates.asSequence()
            .filter { !it.isSystem && it.packageName != ownPackage && it.packageName.isNotBlank() }
            .filter { it.firstInstallTime >= saved.checkedAtMs - MARGIN_MS }
            .filter { alerted[it.packageName] != it.firstInstallTime }
            .distinctBy { it.packageName }
            .sortedBy { it.firstInstallTime }
            .toList()

    /**
     * The state to save after a check that started at [startedAt]. [reportedSequence] is the sequence number Android
     * returned, or null when it returned no changes: then the same-boot sequence is kept, and after a restart the
     * counter is still at 0.
     */
    fun next(read: Read, reportedSequence: Int?, bootCount: Int?, startedAt: Long): WatchState {
        val sequence = reportedSequence ?: if (read is Read.Since) read.sequence else 0
        return WatchState(sequence, bootCount, startedAt)
    }

    /** True when this install (package + first-install time) has not been alerted yet. */
    fun isNew(alerted: Map<String, Long>, packageName: String, firstInstallTime: Long): Boolean = alerted[packageName] != firstInstallTime

    /** [alerted] with this install added, keeping the [LEDGER_LIMIT] most recent installs. */
    fun record(alerted: Map<String, Long>, packageName: String, firstInstallTime: Long): Map<String, Long> {
        val all = alerted + (packageName to firstInstallTime)
        if (all.size <= LEDGER_LIMIT) return all
        return all.entries.sortedByDescending { it.value }.take(LEDGER_LIMIT).associate { it.key to it.value }
    }

    // --- storage format ---------------------------------------------------------------------------------------------

    /** One ledger entry as stored text: "package<TAB>firstInstallTime". */
    fun encodeLedger(alerted: Map<String, Long>): Set<String> = alerted.map { (pkg, time) -> "$pkg\t$time" }.toSet()

    /** Reads stored ledger entries; malformed entries are dropped, never guessed. */
    fun decodeLedger(stored: Set<String>?): Map<String, Long> = stored.orEmpty().mapNotNull { line ->
        val pkg = line.substringBefore('\t', "")
        val time = line.substringAfter('\t', "").toLongOrNull()
        if (pkg.isBlank() || time == null) null else pkg to time
    }.toMap()
}
