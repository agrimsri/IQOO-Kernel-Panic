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

    /** What happened in one run, for the job and for tests on a phone. */
    data class Outcome(val read: NewAppWatch.Read, val alerted: List<String>, val speak: InstallAlert?)

    /** Runs one check (blocking; call off the main thread). Returns null when the check is switched off. */
    fun run(context: Context): Outcome? = synchronized(lock) { runLocked(context.applicationContext) }

    /**
     * Called when the check is switched off: forgets where it left off (waiting for a running check to finish), so
     * switching it on again starts from a fresh baseline and apps installed meanwhile are not alerted as new.
     */
    fun reset(context: Context) = synchronized(lock) { AutoGuardStore(context).clearWatch() }

    private fun runLocked(context: Context): Outcome? {
        val settings = context.graph.settings.settings.value
        if (!settings.autoCheckNewApps) return null
        val store = AutoGuardStore(context)
        val pm = context.packageManager
        val startedAt = System.currentTimeMillis()
        val boot = bootCount(context)
        val saved = store.watchState()
        val read = NewAppWatch.read(saved, boot)

        // If Android refuses a read, nothing is saved: the next run looks at the same window again.
        val (reported, candidates) = runCatching {
            when (read) {
                NewAppWatch.Read.Baseline -> pm.getChangedPackages(0)?.sequenceNumber to emptyList<ChangedApp>()
                is NewAppWatch.Read.Since -> pm.getChangedPackages(read.sequence).let { changed ->
                    changed?.sequenceNumber to changed?.packageNames.orEmpty().mapNotNull { changedApp(pm, it) }
                }
                NewAppWatch.Read.AllInstalled -> pm.getChangedPackages(0)?.sequenceNumber to installedApps(pm)
            }
        }.getOrElse { return Outcome(read, emptyList(), null) }

        val fresh = if (saved == null) emptyList() else NewAppWatch.newInstalls(candidates, context.packageName, saved, store.alerted())
        // Claim each install before posting, so the live receiver (same process) never alerts it as well.
        val claimed = fresh.filter { store.claim(it.packageName, it.firstInstallTime) }
        val speak = alert(context, claimed.map { it.packageName })
        store.saveWatch(NewAppWatch.next(read, reported, boot, startedAt), finishedAt = System.currentTimeMillis())
        return Outcome(read, claimed.map { it.packageName }, speak.takeIf { settings.speakApkGuardAlerts })
    }

    /** Posts the alerts and returns the one to speak. A few apps get one notification each; more share one. */
    private fun alert(context: Context, packages: List<String>): InstallAlert? {
        if (packages.isEmpty()) return null
        if (packages.size <= NewAppWatch.MAX_SEPARATE_ALERTS) {
            var first: InstallAlert? = null
            packages.forEach { pkg ->
                val inspected = ApkInspector.readInstalled(context, pkg) ?: return@forEach
                val alert = InstallAlertPolicy.alert(inspected.facts.label, ApkRiskRules.evaluate(inspected.facts).summary, pkg)
                ApkInstallWatcher.postInstallAlert(context, pkg, alert)
                if (first == null) first = alert
            }
            return first
        }
        val pm = context.packageManager
        val names = packages.map { pkg -> runCatching { pm.getApplicationLabel(applicationInfo(pm, pkg)).toString() }.getOrDefault(pkg) }
        val alert = InstallAlertPolicy.combined(names)
        ApkInstallWatcher.postCombinedAlert(context, alert)
        return alert
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
