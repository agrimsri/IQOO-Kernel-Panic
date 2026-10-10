package com.dpdpxray.app.voice.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import com.dpdpxray.core.assistant.AppCandidate

/**
 * Launcher apps by label, for matching a spoken app name; the same set the app picker lists (TraceVeil itself
 * excluded), without loading icons. Kept for a minute so a "Which one?" follow-up sees the same list.
 */
object InstalledApps {
    private const val CACHE_MS = 60_000L
    @Volatile private var cached: Pair<Long, List<AppCandidate>>? = null

    fun load(context: Context): List<AppCandidate> {
        val now = SystemClock.elapsedRealtime()
        cached?.let { (at, apps) -> if (now - at < CACHE_MS) return apps }
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
        }
        val apps = activities.map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .map { AppCandidate(pm.getApplicationLabel(it).toString(), it.packageName) }
            .sortedBy { it.label.lowercase() }
        cached = now to apps
        return apps
    }
}
