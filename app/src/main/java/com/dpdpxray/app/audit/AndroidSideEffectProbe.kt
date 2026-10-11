package com.dpdpxray.app.audit

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.provider.Telephony
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import com.dpdpxray.app.control.ShizukuBridge

/**
 * The phone's answers for [RestartSideEffects], read only (nothing is changed). Public Android settings and services
 * first; the always-on VPN and the running services come from the Shizuku shell, which is also what a restart needs,
 * so they are read exactly when a restart could happen. Every reading that fails is null (unknown), never "none".
 */
class AndroidSideEffectProbe(private val context: Context) : SideEffectProbe {
    private val cr get() = context.contentResolver

    override fun enabledAccessibilityServices(): String? =
        runCatching { Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) }.getOrNull()

    override fun accessibilityPackages(): Set<String>? = runCatching {
        val am = context.getSystemService(AccessibilityManager::class.java) ?: return null
        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).mapNotNull { it.resolveInfo?.serviceInfo?.packageName }.toSet()
    }.getOrNull()

    override fun defaultInputMethod(): String? =
        runCatching { Settings.Secure.getString(cr, Settings.Secure.DEFAULT_INPUT_METHOD) }.getOrNull()

    override fun homePackage(): String? = runCatching {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }.getOrNull()

    override fun smsPackage(): String? = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()

    override fun deviceAdminPackages(): Set<String>? = runCatching {
        context.getSystemService(DevicePolicyManager::class.java)?.activeAdmins.orEmpty().map { it.packageName }.toSet()
    }.getOrNull()

    override fun notificationListenerPackages(): Set<String>? =
        runCatching { NotificationManagerCompat.getEnabledListenerPackages(context) }.getOrNull()

    override suspend fun alwaysOnVpnPackage(): String? {
        if (!ShizukuBridge.hasPermission()) return null
        return ShizukuBridge.exec("settings", "get", "secure", "always_on_vpn_app").getOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    }

    override suspend fun runningServices(pkg: String): String? {
        if (!ShizukuBridge.hasPermission()) return null
        return ShizukuBridge.exec("dumpsys", "activity", "services", pkg).getOrNull()
    }
}
