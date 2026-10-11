package com.dpdpxray.app.apkguard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.dpdpxray.app.autoguard.AutoGuardRoutes
import com.dpdpxray.app.autoguard.DownloadAlertCopy
import com.dpdpxray.app.autoguard.DownloadTap

/** Turns the intent TraceVeil was opened with into an APK Guard or Auto-guard route, or null for every other intent. */
object ApkGuardIntents {
    fun route(context: Context, intent: Intent?): String? = runCatching {
        if (intent == null) return null
        IncomingApk.installedRoute(intent.action, intent.getStringExtra(IncomingApk.EXTRA_PACKAGE))?.let { return it }
        // A tapped Auto-guard notification: the weekly phone checkup, APK Guard for several new apps at once, or a
        // re-check / consent check of an app (picker with that app selected; nothing starts until Start is tapped).
        AutoGuardRoutes.route(intent.action, intent.getStringExtra(AutoGuardRoutes.EXTRA_PACKAGE))?.let { return it }
        // A tapped "New APK downloaded" alert: check the file, or pick it in Downloads when TraceVeil cannot open it.
        when (val tap = DownloadAlertCopy.tap(intent.action, intent.getStringExtra(DownloadAlertCopy.EXTRA_URI))) {
            is DownloadTap.Check -> return ApkGuardRoutes.file(ApkFileChecks.start(context, Uri.parse(tap.uri)))
            DownloadTap.Pick -> return ApkGuardRoutes.DOWNLOADS
            null -> Unit
        }
        val stream = if (intent.action == Intent.ACTION_SEND) streamUri(intent) else null
        val type = intent.type ?: intent.data?.let { context.contentResolver.getType(it) }
        when (val parsed = IncomingApk.parse(intent.action, type, intent.dataString, stream?.toString())) {
            // Start reading now, while the sending app's read grant is certainly valid.
            is IncomingApk.Parsed.Check -> ApkGuardRoutes.file(ApkFileChecks.start(context, Uri.parse(parsed.uri)))
            is IncomingApk.Parsed.Unreadable -> ApkGuardRoutes.file(ApkFileChecks.failed(parsed.reason))
            null -> null
        }
    }.getOrNull()

    private fun streamUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else legacyStream(intent)

    @Suppress("DEPRECATION")
    private fun legacyStream(intent: Intent): Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
}
