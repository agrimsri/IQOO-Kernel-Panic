package com.dpdpxray.app.apkguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.dpdpxray.app.MainActivity
import com.dpdpxray.app.autoguard.AutoGuardRoutes
import com.dpdpxray.app.autoguard.AutoGuardStore
import com.dpdpxray.app.autoguard.UpdateAlertLedger
import com.dpdpxray.app.autoguard.UpdateAlertPlan
import com.dpdpxray.app.autoguard.UpdateAlertPolicy
import com.dpdpxray.app.autoguard.UpdateWatchSource
import com.dpdpxray.app.graph
import com.dpdpxray.core.apk.ApkRiskRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Live new-install watcher. A receiver registered from [com.dpdpxray.app.XrayApp] hears ACTION_PACKAGE_ADDED while
 * TraceVeil's process is alive, so an install is alerted at once. When TraceVeil is closed, the Auto-guard background
 * check ([com.dpdpxray.app.autoguard.NewAppCheck]) finds the install instead, a few minutes later. Both record each
 * alerted install (package + first-install time) in one ledger, so an install is never alerted twice. Both honour the
 * "Check new apps automatically" setting. It posts one notification per new install and, if the user switched it on,
 * speaks it with an offline voice. The same receiver hears updates (PACKAGE_ADDED while replacing): an update of an app
 * the person checked before gets one "<app> updated" notification that offers a re-check (Update Watch, shared ledger
 * with the background check). It never starts an audit, a VPN or anything else.
 */
object ApkInstallWatcher {
    const val CHANNEL = "apk_guard_installs"
    const val UPDATES_CHANNEL = "checked_app_updates"
    @Volatile private var registered = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun register(context: Context) {
        if (registered) return
        val app = context.applicationContext
        runCatching {
            ensureChannel(app)
            val filter = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }
            // System broadcasts still reach a not-exported receiver; other apps cannot send to it.
            ContextCompat.registerReceiver(app, Receiver(), filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
    }

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "APK Guard: new apps", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Asks you to check a newly installed app before you open it. Checked on this phone only."
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(UPDATES_CHANNEL, "Updates to apps you checked", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tells you when an app you checked was updated, so you can check it again. Nothing starts by itself."
            },
        )
    }

    private class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart
            val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            if (UpdateAlertPlan.liveUpdate(intent.action, replacing, pkg, context.packageName)) {
                val app = context.applicationContext
                val pending = goAsync()
                scope.launch {
                    try { onUpdate(app, pkg!!) } finally { pending.finish() }
                }
                return
            }
            if (!InstallAlertPolicy.shouldAlert(intent.action, replacing, pkg, context.packageName)) return
            val app = context.applicationContext
            val pending = goAsync()
            scope.launch {
                try { onNewInstall(app, pkg!!) } finally { pending.finish() }
            }
        }
    }

    private fun onNewInstall(context: Context, pkg: String) {
        val settings = runCatching { context.graph.settings.settings.value }.getOrNull()
        if (settings?.autoCheckNewApps == false) return
        val inspected = ApkInspector.readInstalled(context, pkg) ?: return
        // Same scope as the background check: apps that came with the phone are not new installs to check.
        if (inspected.isSystemApp) return
        val firstInstall = ApkInspector.firstInstallTime(context, pkg) ?: return
        // The background check may have found this install first; never alert the same install twice.
        if (!AutoGuardStore(context).claim(pkg, firstInstall)) return
        val report = ApkRiskRules.evaluate(inspected.facts)
        val alert = InstallAlertPolicy.alert(inspected.facts.label, report.summary, pkg)
        postInstallAlert(context, pkg, alert)
        if (settings?.speakApkGuardAlerts == true) ApkAlertSpeaker.speak(context, alert.spokenEn, alert.spokenHi)
    }

    /**
     * Update Watch, live: an app was updated while TraceVeil runs. Alerts only when the person checked that app before,
     * its version differs from that check, and this (package, version code) was not alerted yet. Follows both the
     * new-app check and the "Alert when a checked app updates" switches.
     */
    private fun onUpdate(context: Context, pkg: String) {
        UpdateWatchSource.packageChanged()
        val settings = runCatching { context.graph.settings.settings.value }.getOrNull()
        if (settings?.autoCheckNewApps == false || settings?.alertCheckedAppUpdates == false) return
        val item = UpdateWatchSource.updates(context, setOf(pkg)).firstOrNull() ?: return
        val key = UpdateAlertLedger.key(item) ?: return
        if (!AutoGuardStore(context).claimUpdate(key, item.lastAuditedAtMs)) return
        postUpdateAlert(context, pkg, UpdateAlertPolicy.alert(item))
    }

    /**
     * One "<app> updated" alert. Tapping it opens TraceVeil's app picker with that app selected (its check sheet open);
     * nothing starts until the person taps Start. A later update of the same app replaces it.
     */
    fun postUpdateAlert(context: Context, pkg: String, alert: InstallAlert) {
        val open = Intent(context, MainActivity::class.java)
            .setAction(AutoGuardRoutes.ACTION_RECHECK)
            .putExtra(AutoGuardRoutes.EXTRA_PACKAGE, pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val id = UPDATE_BASE + (pkg.hashCode() and 0xFFFF)
        post(context, id, id, open, alert, UPDATES_CHANNEL)
    }

    /** One app's alert; tapping it opens that app's APK Guard result. Re-posting for the same package replaces it. */
    fun postInstallAlert(context: Context, pkg: String, alert: InstallAlert) {
        val open = Intent(context, MainActivity::class.java)
            .setAction(IncomingApk.ACTION_OPEN_INSTALLED)
            .putExtra(IncomingApk.EXTRA_PACKAGE, pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        post(context, NOTIFICATION_BASE + (pkg.hashCode() and 0xFFFF), pkg.hashCode(), open, alert)
    }

    /**
     * One alert for several new apps; tapping it opens APK Guard, which lists the newest installs first. Each batch
     * gets its own notification id, so a later batch does not replace an unread earlier one.
     */
    fun postCombinedAlert(context: Context, packages: List<String>, alert: InstallAlert) {
        val open = Intent(context, MainActivity::class.java)
            .setAction(AutoGuardRoutes.ACTION_OPEN_APK_GUARD)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val id = COMBINED_BASE + (packages.sorted().joinToString(",").hashCode() and 0xFFFF)
        post(context, id, COMBINED_BASE, open, alert)
    }

    /** False when Android will not show TraceVeil's notifications (permission refused or notifications switched off). */
    fun notificationsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() != false
    }

    private fun post(context: Context, id: Int, requestCode: Int, open: Intent, alert: InstallAlert, channel: String = CHANNEL) {
        if (!notificationsAllowed(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context)
        val tap = PendingIntent.getActivity(context, requestCode, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(alert.title)
            .setContentText(alert.text)
            .setStyle(Notification.BigTextStyle().bigText("${alert.text}\n${alert.hindi}"))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(id, n) }
    }

    /** Notification ids: one app's alert 0x4A0000..0x4AFFFF, combined alerts 0x4B0000..0x4BFFFF, updates 0x4C0000..0x4CFFFF. */
    private const val NOTIFICATION_BASE = 0x4A0000
    private const val COMBINED_BASE = 0x4B0000
    private const val UPDATE_BASE = 0x4C0000
}
