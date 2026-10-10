package com.dpdpxray.app.autoguard

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import com.dpdpxray.app.MainActivity
import com.dpdpxray.app.apkguard.ApkFactsMapper
import com.dpdpxray.app.apkguard.ApkInspector
import com.dpdpxray.app.apkguard.ApkInstallWatcher
import com.dpdpxray.app.graph
import com.dpdpxray.core.apk.CheckupFacts
import com.dpdpxray.core.apk.PhoneCheckup
import com.dpdpxray.core.apk.PhoneCheckupResult

/**
 * Reads, for every installed app that did not come with the phone (and not TraceVeil itself), the facts the phone
 * checkup counts. Read-only: it never changes a permission or a setting. Each reading that Android refuses is left
 * unknown (null), never "no".
 */
object PhoneCheckupCollector {
    fun collect(context: Context): List<CheckupFacts> {
        val pm = context.packageManager
        val ops = context.getSystemService(AppOpsManager::class.java)
        val accessibility = accessibilityPackages(context)
        val listeners = runCatching { NotificationManagerCompat.getEnabledListenerPackages(context).toSet() }.getOrNull()
        val admins = runCatching {
            context.getSystemService(DevicePolicyManager::class.java)?.activeAdmins.orEmpty().map { it.packageName }.toSet()
        }.getOrNull()
        return installedWithPermissions(pm)
            .filter { it.packageName != context.packageName }
            .mapNotNull { info ->
                val ai = info.applicationInfo ?: return@mapNotNull null
                if (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) return@mapNotNull null
                val requested = info.requestedPermissions?.toSet().orEmpty()
                // Android only reads these switches for apps that declare the permission; skip the call otherwise.
                val overlayDeclared = SpecialAccess.SYSTEM_ALERT_WINDOW in requested
                val installEligible = SpecialAccess.installEligible(SpecialAccess.REQUEST_INSTALL_PACKAGES in requested, ai.targetSdkVersion)
                val raw = RawCheckupApp(
                    packageName = info.packageName,
                    label = runCatching { ai.loadLabel(pm).toString() }.getOrNull(),
                    grantedPermissions = ApkFactsMapper.granted(info.requestedPermissions, info.requestedPermissionsFlags, PackageInfo.REQUESTED_PERMISSION_GRANTED),
                    displayOverApps = SpecialAccess.displayOverApps(
                        declared = overlayDeclared,
                        mode = if (overlayDeclared) opMode(ops, AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, ai.uid, info.packageName) else null,
                        permissionGranted = if (!overlayDeclared) null
                        else runCatching { pm.checkPermission(SpecialAccess.SYSTEM_ALERT_WINDOW, info.packageName) == PackageManager.PERMISSION_GRANTED }.getOrNull(),
                    ),
                    installApps = SpecialAccess.installApps(
                        eligible = installEligible,
                        mode = if (installEligible) opMode(ops, SpecialAccess.OP_REQUEST_INSTALL_PACKAGES, ai.uid, info.packageName) else null,
                    ),
                    origin = runCatching { ApkInspector.origin(context, info.packageName, isSystem = false) }.getOrNull(),
                )
                SpecialAccess.facts(raw, accessibility, listeners, admins)
            }
    }

    /** Throws when Android refuses the list: an unreadable phone must never look like a phone with no apps. */
    private fun installedWithPermissions(pm: PackageManager): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        else @Suppress("DEPRECATION") pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)

    /**
     * The op's mode for another app (no side effects: a check, not a note). Android 16 renamed unsafeCheckOpNoThrow
     * back to checkOpNoThrow; both read the same mode. null when this phone does not let TraceVeil read it.
     */
    private fun opMode(ops: AppOpsManager?, op: String, uid: Int, pkg: String): OpMode? = ops?.let {
        runCatching {
            OpMode.of(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) it.checkOpNoThrow(op, uid, pkg) else legacyCheckOp(it, op, uid, pkg))
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun legacyCheckOp(ops: AppOpsManager, op: String, uid: Int, pkg: String): Int = ops.unsafeCheckOpNoThrow(op, uid, pkg)

    /**
     * Accessibility services switched on, from Settings.Secure enabled_accessibility_services; if that cannot be read,
     * from AccessibilityManager; null when neither can be read.
     */
    private fun accessibilityPackages(context: Context): Set<String>? =
        runCatching { SpecialAccess.packagesInComponentList(Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)) }
            .recoverCatching {
                context.getSystemService(AccessibilityManager::class.java)!!
                    .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    .mapNotNull { it.resolveInfo?.serviceInfo?.packageName }.toSet()
            }.getOrNull()
}

/** One checkup, on demand (the checkup screen) or weekly (the job), plus its notification. */
object PhoneCheckupRun {
    const val CHANNEL = "phone_checkup"
    private const val NOTIFICATION_ID = 0x4A1001

    /** Reads the phone and evaluates it. Blocking; call off the main thread. Throws if Android refuses the app list. */
    fun now(context: Context): PhoneCheckupResult = PhoneCheckup.evaluate(PhoneCheckupCollector.collect(context))

    /**
     * The weekly job: runs the checkup and posts one notification when there is something to review, in the app's
     * language setting. Returns null when the weekly checkup is switched off or Android refused the app list.
     */
    fun weekly(context: Context): PhoneCheckupResult? {
        val settings = context.graph.settings.settings.value
        if (!settings.weeklyCheckup) return null
        val result = runCatching { now(context) }.getOrElse { return null }
        AutoGuardStore(context).saveCheckupRun(System.currentTimeMillis(), result.appsToReview)
        if (result.shouldNotify) notify(context, result, hindi = settings.guideLanguage == "hi")
        return result
    }

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Weekly phone checkup", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Once a week, says how many apps hold powerful access such as reading SMS. Checked on this phone only."
            },
        )
    }

    private fun notify(context: Context, result: PhoneCheckupResult, hindi: Boolean) {
        if (!ApkInstallWatcher.notificationsAllowed(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context)
        val open = Intent(context, MainActivity::class.java)
            .setAction(AutoGuardRoutes.ACTION_OPEN_CHECKUP)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = PendingIntent.getActivity(context, NOTIFICATION_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = if (hindi) result.notificationTitle.hi else result.notificationTitle.en
        val text = if (hindi) result.notificationText.hi else result.notificationText.en
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }
}
