package com.dpdpxray.app.apkguard

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.dpdpxray.core.apk.ApkCodeScanner
import com.dpdpxray.core.apk.ApkGuardReport
import com.dpdpxray.core.apk.CodeScanResult
import com.dpdpxray.core.apk.InstallHighlights

/**
 * The automatic static check of a newly installed app, for its notification: the APK Guard rules (already in
 * [ApkGuardReport]) plus a read of the SDK class names in its installed APK files. Read-only and bounded by core's
 * CodeScanLimits; it never opens or runs the app, and it never starts a consent check. The code read is skipped while
 * Android reports the battery as low; the notification then says nothing about SDKs.
 */
object InstalledScan {
    fun highlights(context: Context, report: ApkGuardReport): InstallHighlights =
        InstallHighlights.of(report, if (batteryLow(context)) null else code(context, report.facts.packageName)?.found)

    /** The code scan of an installed app, or null when Android did not let TraceVeil read any of its code files. */
    fun code(context: Context, pkg: String): CodeScanResult? = runCatching {
        val files = ApkInspector.codeFiles(context, pkg)
        if (files.isEmpty()) null else ApkCodeScanner.scan(files).takeIf { it.dexFilesRead > 0 }
    }.getOrNull()

    private fun batteryLow(context: Context): Boolean = runCatching {
        // Sticky system broadcast: reading it registers nothing.
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW, false) == true
    }.getOrDefault(false)
}
