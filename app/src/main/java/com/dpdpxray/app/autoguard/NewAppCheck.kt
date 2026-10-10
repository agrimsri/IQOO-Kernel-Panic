package com.dpdpxray.app.autoguard

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.dpdpxray.app.apkguard.ApkInspector
import com.dpdpxray.app.apkguard.ApkInstallWatcher
import com.dpdpxray.app.apkguard.InstallAlert
import com.dpdpxray.app.apkguard.InstallAlertPolicy
import com.dpdpxray.app.graph
import com.dpdpxray.core.apk.ApkRiskRules

/**
 * The background new-app check, run by [NewAppCheckJobService] while TraceVeil is closed. It asks Android which
 * packages changed since the last check (PackageManager.getChangedPackages with the saved sequence number), keeps the
 * ones that are new installs (see [NewAppWatch]), checks each with the same APK Guard rules as the live receiver, and
 * posts the same notification. It only reads; it never opens, blocks or removes an app.
 */
object NewAppCheck {
    private val lock = Any()

    /** What Android reported: its sequence number, the changed package names (null after a full read) and the apps. */
    private class Reading(val sequence: Int?, val changedNames: List<String>?, val present: List<ChangedApp>)

    /** What happened in one run, for the job and for tests on a phone. */
    data class Outcome(val read: NewAppWatch.Read, val alerted: List<String>, val speak: InstallAlert?)

    /**
     * Runs one check (blocking; call off the main thread). Returns null when the check is switched off. [fromJob] is
     * true for the background job, whose finishing time Settings shows.
     */
    fun run(context: Context, fromJob: Boolean): Outcome? = synchronized(lock) { runLocked(context.applicationContext, fromJob) }

    /**
     * Called when the check is switched off: forgets where it left off (waiting for a running check to finish), so
     * switching it on again starts from a fresh baseline and apps installed meanwhile are not alerted as new.
     */
    fun reset(context: Context) = synchronized(lock) { AutoGuardStore(context).clearWatch() }

    private fun runLocked(context: Context, fromJob: Boolean): Outcome? {
        val settings = context.graph.settings.settings.value
        if (!settings.autoCheckNewApps) return null
        val store = AutoGuardStore(context)
        val pm = context.packageManager
        val boot = bootCount(context)
        val saved = store.watchState()
        val read = NewAppWatch.read(saved, boot)

        // If Android refuses a read, nothing is saved: the next run looks at the same window again. The sequence number
        // is read before the app list, so an install in between is seen now and simply reported again next time.
        val reading = runCatching {
            when (read) {
                NewAppWatch.Read.Baseline, NewAppWatch.Read.AllInstalled -> Reading(pm.getChangedPackages(0)?.sequenceNumber, null, installedApps(pm))
                is NewAppWatch.Read.Since -> pm.getChangedPackages(read.sequence).let { changed ->
                    val names = changed?.packageNames.orEmpty()
                    Reading(changed?.sequenceNumber, names, names.mapNotNull { changedApp(pm, it) })
                }
            }
        }.getOrElse { return Outcome(read, emptyList(), null) }

        val fresh = if (saved == null) emptyList() else NewAppWatch.newInstalls(reading.present, context.packageName, saved.known, store.alerted())
        val (alerted, speak) = alert(context, store, fresh)
        val known = NewAppWatch.nextKnown(saved?.known, read, reading.present, reading.changedNames.orEmpty())
        store.saveWatch(NewAppWatch.next(read, reading.sequence, boot, known), jobFinishedAt = if (fromJob) System.currentTimeMillis() else null)
        return Outcome(read, alerted, speak.takeIf { settings.speakApkGuardAlerts })
    }

    /**
     * Posts the alerts; returns the alerted packages and the alert to speak. A few apps get one notification each; more
     * share one. Each install is claimed in the ledger right before its notification is posted (after its details were
     * read), so the live receiver never alerts it as well and a failed read does not mark it as alerted.
     */
    private fun alert(context: Context, store: AutoGuardStore, fresh: List<ChangedApp>): Pair<List<String>, InstallAlert?> {
        if (fresh.isEmpty()) return emptyList<String>() to null
        if (fresh.size <= NewAppWatch.MAX_SEPARATE_ALERTS) {
            val alerted = mutableListOf<String>()
            var first: InstallAlert? = null
            fresh.forEach { app ->
                val inspected = ApkInspector.readInstalled(context, app.packageName) ?: return@forEach
                val alert = InstallAlertPolicy.alert(inspected.facts.label, ApkRiskRules.evaluate(inspected.facts).summary, app.packageName)
                if (!store.claim(app.packageName, app.firstInstallTime)) return@forEach
                ApkInstallWatcher.postInstallAlert(context, app.packageName, alert)
                alerted += app.packageName
                if (first == null) first = alert
            }
            return alerted to first
        }
        val pm = context.packageManager
        val claimed = fresh.filter { store.claim(it.packageName, it.firstInstallTime) }.map { it.packageName }
        if (claimed.isEmpty()) return emptyList<String>() to null
        val names = claimed.map { pkg -> runCatching { pm.getApplicationLabel(applicationInfo(pm, pkg)).toString() }.getOrDefault(pkg) }
        val alert = InstallAlertPolicy.combined(names)
        ApkInstallWatcher.postCombinedAlert(context, claimed, alert)
        return claimed to alert
    }

    /** Settings.Global.BOOT_COUNT, which changes on every restart; null when Android does not report it. */
    private fun bootCount(context: Context): Int? =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrNull()

    /** null when the package is gone (a removal is also a change). */
    private fun changedApp(pm: PackageManager, pkg: String): ChangedApp? = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0)
        toChanged(info)
    }.getOrNull()

    private fun applicationInfo(pm: PackageManager, pkg: String): ApplicationInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, 0)

    private fun installedApps(pm: PackageManager): List<ChangedApp> {
        val all = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.getInstalledPackages(0)
        return all.mapNotNull { toChanged(it) }
    }

    private fun toChanged(info: PackageInfo): ChangedApp? {
        val ai = info.applicationInfo ?: return null
        return ChangedApp(info.packageName, info.firstInstallTime, ai.flags and ApplicationInfo.FLAG_SYSTEM != 0)
    }
}
