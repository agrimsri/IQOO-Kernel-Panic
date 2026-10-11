package com.dpdpxray.app.autoguard

import com.dpdpxray.core.web.WebAudit
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.dpdpxray.app.data.CheckedApps
import com.dpdpxray.app.graph
import com.dpdpxray.core.update.AppVersion
import com.dpdpxray.core.update.InstalledApp
import com.dpdpxray.core.update.UpdateWatch
import com.dpdpxray.core.update.UpdatedApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android side of Update Watch: the saved audits (on this phone) and the installed versions of the audited apps only
 * (PackageManager, read-only). The decision itself is [UpdateWatch]. Blocking: call off the main thread.
 */
object UpdateWatchSource {
    private val _changes = MutableStateFlow(0L)

    /** Bumped when Android reports an app update while TraceVeil runs, so an open screen refreshes its list. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    fun packageChanged() { _changes.value = _changes.value + 1 }

    /** Checked apps whose installed version differs from their latest check. [only] limits the packages looked at. */
    fun updates(context: Context, only: Set<String>? = null): List<UpdatedApp> {
        if (only != null && only.isEmpty()) return emptyList()
        val repo = context.graph.repository
        // Website checks have no installed version to watch.
        val metas = repo.listNow().filter { !WebAudit.isWebPackage(it.targetPackage) && (only == null || it.targetPackage in only) }
        val checked = CheckedApps.latest(metas, repo::sessionsNow)
        val installed = checked.mapNotNull { installed(context, it.packageName) }.associateBy { it.packageName }
        return UpdateWatch.updatedSinceLastCheck(checked, installed)
    }

    /** null when the app is not installed (or Android refuses to say). */
    fun installed(context: Context, pkg: String): InstalledApp? = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0)
        InstalledApp(pkg, AppVersion.of(info.versionName, info.longVersionCode), info.lastUpdateTime.takeIf { it > 0L })
    }.getOrNull()
}
