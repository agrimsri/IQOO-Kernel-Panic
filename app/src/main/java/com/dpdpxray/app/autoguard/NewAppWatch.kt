package com.dpdpxray.app.autoguard

/** Where the background new-app check left off. Saved after every completed check. */
data class WatchState(
    /** Android's package-change sequence number after the last check. Android restarts it at 0 on every boot. */
    val sequence: Int,
    /** Settings.Global.BOOT_COUNT when [sequence] was read; null when Android did not report it. */
    val bootCount: Int?,
    /** Apps (not from the phone's system image) that were installed at the last check: package to first-install time. */
    val known: Map<String, Long>,
)

/** One installed package the check is looking at, copied out of PackageInfo so the decision is testable. */
data class ChangedApp(val packageName: String, val firstInstallTime: Long, val isSystem: Boolean)

/**
 * Pure rules for the background new-app check (no Android types). TraceVeil remembers which apps were installed at the
 * last check; an app that is not on that list (or is on it with a different first-install time, i.e. it was
 * reinstalled) is a new install. Updates keep their first-install time, so they are never new. Nothing depends on the
 * phone's clock, so a clock that is wrong or changed cannot make an old app look new.
 *
 * Within one boot, only the packages Android reports as changed since the saved sequence number
 * (PackageManager.getChangedPackages) are looked at. The very first check, and the first check after the check is
 * switched on, only records the list, so apps already on the phone never produce an alert.
 */
object NewAppWatch {
    /** Up to this many new apps get one notification each; more than that share one notification. */
    const val MAX_SEPARATE_ALERTS = 3

    /** Alerted installs remembered for de-duplication; the oldest are forgotten first. */
    const val LEDGER_LIMIT = 300

    sealed interface Read {
        /** Nothing saved yet (first run, or the check was just switched on): record the installed apps, alert nothing. */
        data object Baseline : Read

        /** Same boot as last time: ask Android for packages changed since [sequence]. */
        data class Since(val sequence: Int) : Read

        /**
         * The phone restarted (Android's change counter started again at 0) or the boot count is unreadable, so the saved
         * sequence cannot be trusted. Compare every installed app with the remembered list.
         */
        data object AllInstalled : Read
    }

    fun read(saved: WatchState?, bootCount: Int?): Read = when {
        saved == null -> Read.Baseline
        bootCount == null || saved.bootCount == null || bootCount != saved.bootCount -> Read.AllInstalled
        else -> Read.Since(saved.sequence)
    }

    /**
     * The apps to alert about, oldest install first: not from the system image, not TraceVeil, not on the [known] list
     * with the same first-install time, and not already alerted ([alerted] maps a package to the first-install time
     * that was alerted, by this check or by the live receiver).
     */
    fun newInstalls(present: List<ChangedApp>, ownPackage: String, known: Map<String, Long>, alerted: Map<String, Long>): List<ChangedApp> =
        present.asSequence()
            .filter { !it.isSystem && it.packageName != ownPackage && it.packageName.isNotBlank() }
            .filter { known[it.packageName] != it.firstInstallTime }
            .filter { alerted[it.packageName] != it.firstInstallTime }
            .distinctBy { it.packageName }
            .sortedBy { it.firstInstallTime }
            .toList()

    /**
     * The remembered list after a check. After a full read ([Read.Baseline] or [Read.AllInstalled]) it is exactly the
     * installed apps in [present]. After a [Read.Since] read, packages in [changedNames] are updated from [present],
     * and a changed package that is no longer installed is dropped.
     */
    fun nextKnown(known: Map<String, Long>?, read: Read, present: List<ChangedApp>, changedNames: Collection<String>): Map<String, Long> {
        val installed = present.filter { !it.isSystem }.associate { it.packageName to it.firstInstallTime }
        if (read !is Read.Since || known == null) return installed
        return known - changedNames.toSet() + installed
    }

    /**
     * The state to save after a check. [reportedSequence] is the sequence number Android returned, or null when it
     * returned no changes: then the same-boot sequence is kept, and after a restart the counter is still at 0.
     */
    fun next(read: Read, reportedSequence: Int?, bootCount: Int?, known: Map<String, Long>): WatchState {
        val sequence = reportedSequence ?: if (read is Read.Since) read.sequence else 0
        return WatchState(sequence, bootCount, known)
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

    /** Package to first-install time as stored text: "package<TAB>firstInstallTime" (the ledger and the known list). */
    fun encodeLedger(entries: Map<String, Long>): Set<String> = entries.map { (pkg, time) -> "$pkg\t$time" }.toSet()

    /** Reads stored entries; malformed entries are dropped, never guessed. */
    fun decodeLedger(stored: Set<String>?): Map<String, Long> = stored.orEmpty().mapNotNull { line ->
        val pkg = line.substringBefore('\t', "")
        val time = line.substringAfter('\t', "").toLongOrNull()
        if (pkg.isBlank() || time == null) null else pkg to time
    }.toMap()
}
