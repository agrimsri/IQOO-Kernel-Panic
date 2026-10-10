package com.dpdpxray.app.autoguard

import android.provider.Settings
import com.dpdpxray.app.apkguard.ApkGuardRoutes
import com.dpdpxray.core.apk.CheckupArea

/** Auto-guard navigation and notification-tap actions. Other screens (for example a mode picker) link to [CHECKUP]. */
object AutoGuardRoutes {
    /** "My phone checkup". Keep this exact name: other features navigate to it. */
    const val CHECKUP = "checkup"

    const val ACTION_OPEN_CHECKUP = "com.dpdpxray.app.autoguard.OPEN_CHECKUP"
    const val ACTION_OPEN_APK_GUARD = "com.dpdpxray.app.autoguard.OPEN_APK_GUARD"

    /** A tapped "<app> updated" alert (Update Watch); the package travels in [EXTRA_PACKAGE]. */
    const val ACTION_RECHECK = "com.dpdpxray.app.autoguard.RECHECK"
    const val EXTRA_PACKAGE = "com.dpdpxray.app.autoguard.PACKAGE"

    /**
     * The route a tapped Auto-guard notification opens, or null for every other action. A re-check opens the app
     * picker with that app already selected, so its check sheet is open; nothing starts until the person taps Start.
     * A missing or malformed package opens nothing.
     */
    fun route(action: String?, pkg: String? = null): String? = when (action) {
        ACTION_OPEN_CHECKUP -> CHECKUP
        ACTION_OPEN_APK_GUARD -> ApkGuardRoutes.HUB
        ACTION_RECHECK -> pkg?.takeIf { ApkGuardRoutes.isPackageName(it) }?.let(ApkGuardRoutes::recheckPicker)
        else -> null
    }

    /**
     * The Android settings screen (a public Settings action) where the person can review an area, or null when the
     * review happens per app. Opening it changes nothing by itself.
     */
    fun settingsAction(area: CheckupArea): String? = when (area) {
        CheckupArea.SCREEN_CONTROL -> Settings.ACTION_ACCESSIBILITY_SETTINGS
        CheckupArea.NOTIFICATIONS -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
        CheckupArea.DRAW_OVER_APPS -> Settings.ACTION_MANAGE_OVERLAY_PERMISSION
        CheckupArea.INSTALL_APPS -> Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
        CheckupArea.DEVICE_ADMIN -> Settings.ACTION_SECURITY_SETTINGS
        CheckupArea.SMS, CheckupArea.OUTSIDE_STORE -> null
    }
}
