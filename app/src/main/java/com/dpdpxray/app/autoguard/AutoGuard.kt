package com.dpdpxray.app.autoguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dpdpxray.app.graph
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Auto-guard keeps TraceVeil's checks going without the person opening the app: a new-app check about every
 * 15 minutes and a weekly phone checkup, both through Android's JobScheduler, both switchable in Settings and both
 * on by default. Everything runs on this phone; nothing is sent anywhere.
 */
object AutoGuard {
    @Volatile private var started = false

    /**
     * Called once per process from the Application. Follows the two settings: switching one off cancels its job at
     * once; switching the new-app check on (again) starts with a fresh baseline, so apps installed while it was off
     * are never alerted as new.
     */
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val graph = app.graph
        graph.scope.launch {
            var previous: Boolean? = null
            graph.settings.settings.map { it.autoCheckNewApps to it.weeklyCheckup }.distinctUntilChanged().collect { (newApps, weekly) ->
                if (!newApps) NewAppCheck.reset(app)
                val lastRun = AutoGuardStore(app).newAppsLastRunAt
                val runNow = AutoGuardJobs.runNewAppCheckNow(previous, newApps, lastRun, System.currentTimeMillis())
                runCatching { AutoGuardJobs.sync(app, newApps, weekly, runNewAppCheckNow = runNow) }
                previous = newApps
            }
        }
    }
}

/**
 * After a restart (and after TraceVeil is updated) makes sure the jobs exist and runs one new-app check soon, so an
 * app installed just before the restart is still found. Not exported: only Android can send these broadcasts to it.
 */
class AutoGuardBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val settings = runCatching { context.graph.settings.settings.value }.getOrNull() ?: return
        runCatching { AutoGuardJobs.sync(context, settings.autoCheckNewApps, settings.weeklyCheckup, runNewAppCheckNow = true) }
    }
}
