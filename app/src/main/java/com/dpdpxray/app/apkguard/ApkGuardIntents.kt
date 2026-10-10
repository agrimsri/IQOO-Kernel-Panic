package com.dpdpxray.app.apkguard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/** Turns the intent TraceVeil was opened with into an APK Guard route, or null for every other intent. */
object ApkGuardIntents {
    fun route(context: Context, intent: Intent?): String? = runCatching {
        if (intent == null) return null
        IncomingApk.installedRoute(intent.action, intent.getStringExtra(IncomingApk.EXTRA_PACKAGE))?.let { return it }
        val stream = if (intent.action == Intent.ACTION_SEND) streamUri(intent) else null
        val type = intent.type ?: intent.data?.let { context.contentResolver.getType(it) }
        val picked = IncomingApk.pickUri(intent.action, type, intent.dataString, stream?.toString()) ?: return null
        val uri = Uri.parse(picked)
        // Start reading now, while the sending app's read grant is certainly valid.
        ApkGuardRoutes.file(ApkFileChecks.start(context, uri))
    }.getOrNull()

    private fun streamUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else legacyStream(intent)

    @Suppress("DEPRECATION")
    private fun legacyStream(intent: Intent): Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
}
