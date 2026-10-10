package com.dpdpxray.app.autoguard

import com.dpdpxray.core.apk.CheckupFacts
import com.dpdpxray.core.apk.InstallOrigin

/** An AppOps mode, copied out of AppOpsManager (MODE_ALLOWED = 0 … MODE_FOREGROUND = 4) so the rules run on the JVM. */
enum class OpMode {
    ALLOWED, IGNORED, ERRORED, DEFAULT, FOREGROUND, UNKNOWN;

    companion object {
        fun of(raw: Int): OpMode = when (raw) {
            0 -> ALLOWED
            1 -> IGNORED
            2 -> ERRORED
            3 -> DEFAULT
            4 -> FOREGROUND
            else -> UNKNOWN
        }
    }
}

/** What the checkup read for one app before the phone-wide lists are applied. null = Android did not report it. */
data class RawCheckupApp(
    val packageName: String,
    val label: String?,
    val grantedPermissions: Set<String>?,
    val displayOverApps: Boolean?,
    val installApps: Boolean?,
    val origin: InstallOrigin?,
)

/**
 * Pure rules that turn Android's special-access readings into yes / no / unknown. Android lists an app under
 * "Display over other apps" or "Install unknown apps" only when the app declares the matching permission, so an app
 * that does not declare it is a certain "no". A reading that failed is unknown, never "no".
 */
object SpecialAccess {
    const val SYSTEM_ALERT_WINDOW = "android.permission.SYSTEM_ALERT_WINDOW"
    const val REQUEST_INSTALL_PACKAGES = "android.permission.REQUEST_INSTALL_PACKAGES"

    /** The AppOps name of "Install unknown apps" (AppOpsManager has no public constant for it). */
    const val OP_REQUEST_INSTALL_PACKAGES = "android:request_install_packages"

    /** Android 8 (API 26) made apps declare REQUEST_INSTALL_PACKAGES before they can be allowed to install apps. */
    private const val INSTALL_DECLARATION_SDK = 26

    /**
     * "Display over other apps". In the default mode Android falls back to whether the permission itself is granted
     * (true only for some old or pre-installed apps). [mode] is null when the reading failed.
     */
    fun displayOverApps(declared: Boolean, mode: OpMode?, permissionGranted: Boolean?): Boolean? = when {
        !declared -> false
        mode == null || mode == OpMode.UNKNOWN -> null
        mode == OpMode.ALLOWED || mode == OpMode.FOREGROUND -> true
        mode == OpMode.DEFAULT -> permissionGranted
        else -> false
    }

    /** Whether Android would offer the "Install unknown apps" switch for this app at all. */
    fun installEligible(declared: Boolean, targetSdk: Int?): Boolean = declared || (targetSdk != null && targetSdk < INSTALL_DECLARATION_SDK)

    /** "Install unknown apps": only an explicit allow counts; the default mode means the person never allowed it. */
    fun installApps(eligible: Boolean, mode: OpMode?): Boolean? = when {
        !eligible -> false
        mode == null || mode == OpMode.UNKNOWN -> null
        else -> mode == OpMode.ALLOWED
    }

    /**
     * Package names in a component list such as Settings.Secure enabled_accessibility_services
     * ("com.a/com.a.Service:com.b/.Other"). An empty or missing setting means nothing is switched on.
     */
    fun packagesInComponentList(value: String?): Set<String> = value.orEmpty().split(':')
        .mapNotNull { entry -> entry.trim().substringBefore('/', "").trim().takeIf { it.isNotEmpty() } }
        .toSet()

    /** Joins one app's own readings with the phone-wide lists; a list that could not be read leaves every app unknown. */
    fun facts(app: RawCheckupApp, accessibilityOn: Set<String>?, notificationAccess: Set<String>?, deviceAdmins: Set<String>?): CheckupFacts =
        CheckupFacts(
            packageName = app.packageName,
            label = app.label,
            grantedPermissions = app.grantedPermissions,
            accessibilityOn = accessibilityOn?.let { app.packageName in it },
            notificationAccessOn = notificationAccess?.let { app.packageName in it },
            displayOverAppsAllowed = app.displayOverApps,
            installAppsAllowed = app.installApps,
            deviceAdminActive = deviceAdmins?.let { app.packageName in it },
            origin = app.origin,
        )
}
