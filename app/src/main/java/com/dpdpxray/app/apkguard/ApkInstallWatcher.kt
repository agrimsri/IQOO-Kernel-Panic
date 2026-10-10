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
import com.dpdpxray.app.graph
import com.dpdpxray.core.apk.ApkRiskRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * New-install watcher. A receiver registered from [com.dpdpxray.app.XrayApp] hears ACTION_PACKAGE_ADDED while
 * TraceVeil's process is alive. Android delivers this broadcast only to running apps' registered receivers, so alerts
 * appear only while TraceVeil is running; an enabled consent watcher (accessibility service) keeps the process alive.
 * It posts one notification per new install and, if the user switched it on, speaks it with an offline voice.
 * It never starts an audit, a VPN or anything else.
 */
object ApkInstallWatcher {
    const val CHANNEL = "apk_guard_installs"
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
                description = "Asks you to check a newly installed app before you open it. Shown only while TraceVeil is running."
            },
        )
    }

    private class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart
            val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            if (!InstallAlertPolicy.shouldAlert(intent.action, replacing, pkg, context.packageName)) return
            val app = context.applicationContext
            val pending = goAsync()
            scope.launch {
                try { onNewInstall(app, pkg!!) } finally { pending.finish() }
            }
        }
    }

    private fun onNewInstall(context: Context, pkg: String) {
        val inspected = ApkInspector.readInstalled(context, pkg) ?: return
        val report = ApkRiskRules.evaluate(inspected.facts)
        val alert = InstallAlertPolicy.alert(inspected.facts.label, report.summary, pkg)
        notify(context, pkg, alert)
        val speak = runCatching { context.graph.settings.settings.value.speakApkGuardAlerts }.getOrDefault(false)
        if (speak) ApkAlertSpeaker.speak(context, alert.spokenEn, alert.spokenHi)
    }

    private fun notify(context: Context, pkg: String, alert: InstallAlert) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context)
        val open = Intent(context, MainActivity::class.java)
            .setAction(IncomingApk.ACTION_OPEN_INSTALLED)
            .putExtra(IncomingApk.EXTRA_PACKAGE, pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = PendingIntent.getActivity(context, pkg.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(alert.title)
            .setContentText(alert.text)
            .setStyle(Notification.BigTextStyle().bigText("${alert.text}\n${alert.hindi}"))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_BASE + (pkg.hashCode() and 0xFFFF), n) }
    }

    private const val NOTIFICATION_BASE = 0x4A0000
}
