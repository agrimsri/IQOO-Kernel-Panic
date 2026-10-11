package com.dpdpxray.app.control

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.VpnService
import android.provider.Settings
import com.dpdpxray.app.consent.ConsentAccessibilityService

data class Preflight(
    val vpnReady: Boolean,
    val accessibilityOn: Boolean,
    val shizukuRunning: Boolean,
    val shizukuGranted: Boolean,
    val privateDnsActive: Boolean,
    val modelFound: Boolean,
    val modelLoaded: Boolean,
    /** Another app's VPN is running; TraceVeil must not take over its slot without the user turning it off. */
    val otherVpnActive: Boolean = false,
) {
    /** Minimum for a real (non-sample) audit. */
    val canAudit: Boolean get() = vpnReady && accessibilityOn
}

data class AuditableApp(val packageName: String, val label: String, val icon: Drawable?, val isSystem: Boolean)

object DeviceChecks {
    /**
     * True when the VPN permission is granted and no other app's VPN is running. Android allows one VPN at a time, and
     * VpnService.prepare() from an app the user approved earlier hands the VPN slot to that app, which stops another
     * app's running VPN. So prepare() is never called while another VPN is up: just opening TraceVeil must not switch
     * off the user's own VPN.
     */
    fun vpnReady(context: Context): Boolean = !otherVpnActive(context) && VpnService.prepare(context) == null

    /** Another app's VPN network is up. Our own VPN is recognised by its owner UID, which Android reveals only to us. */
    @Suppress("DEPRECATION")
    fun otherVpnActive(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val me = android.os.Process.myUid()
        return runCatching {
            cm.allNetworks.any { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@any false
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) && caps.ownerUid != me
            }
        }.getOrDefault(false)
    }

    /** Android's VPN settings, where the user can turn their own VPN off before a check. */
    fun openVpnSettings(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun accessibilityOn(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val me = ComponentName(context, ConsentAccessibilityService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) } || ConsentAccessibilityService.instance != null
    }

    /** True when the device uses DNS-over-TLS (Private DNS), which can hide lookups from the audit. */
    fun privateDnsActive(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return runCatching { cm.getLinkProperties(cm.activeNetwork)?.isPrivateDnsActive == true }.getOrDefault(false)
    }

    /** Whether the phone has a working (validated) internet connection now; null when it cannot be read. */
    fun networkOnline(context: Context): Boolean? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrNull()

    /** The phone is locked or its screen is off, so an app cannot be opened or seen working in front. */
    fun phoneLocked(context: Context): Boolean = runCatching {
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        val power = context.getSystemService(android.os.PowerManager::class.java)
        keyguard?.isKeyguardLocked == true || power?.isInteractive == false
    }.getOrDefault(false)

    /** Install facts about [pkg]; null when it is not installed. */
    data class AppFacts(val uid: Int, val system: Boolean, val debuggable: Boolean, val launchable: Boolean)

    fun appFacts(context: Context, pkg: String): AppFacts? = runCatching {
        val ai = context.packageManager.getApplicationInfo(pkg, 0)
        AppFacts(
            uid = ai.uid,
            system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            debuggable = ai.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            launchable = context.packageManager.getLaunchIntentForPackage(pkg) != null,
        )
    }.getOrNull()

    /** Android's Wi-Fi and mobile data settings, for a check that ran without a working connection. */
    fun openInternetSettings(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openPrivateDnsSettings(context: Context) {
        val intent = Intent("android.settings.WIRELESS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun openAppInfo(context: Context, pkg: String) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun launchableApps(context: Context): List<AuditableApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
        }
        return activities
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .map { ai ->
                AuditableApp(
                    packageName = ai.packageName,
                    label = pm.getApplicationLabel(ai).toString(),
                    icon = runCatching { pm.getApplicationIcon(ai) }.getOrNull(),
                    isSystem = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                )
            }
            .sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
    }

    fun launch(context: Context, pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun bringXrayToFront(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { context.startActivity(intent) }
    }
}
