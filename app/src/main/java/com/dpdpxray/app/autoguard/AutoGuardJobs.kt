package com.dpdpxray.app.autoguard

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context

/**
 * The platform JobScheduler jobs behind Auto-guard (no extra library). Job ids are fixed so a tester can force one:
 * `adb shell cmd jobscheduler run -f com.dpdpxray.app <id>`.
 *
 * Periodic jobs are persisted across restarts (needs RECEIVE_BOOT_COMPLETED). An already scheduled job is never
 * scheduled again with the same settings: scheduling resets its timer and would stop it if it were running.
 */
object AutoGuardJobs {
    /** New-app check, about every 15 minutes (Android's minimum period; Android decides the exact time, never on low battery). */
    const val NEW_APPS_PERIODIC = 4101
    /** One extra new-app check soon after boot, after the app starts, or when the check is switched on. */
    const val NEW_APPS_NOW = 4102
    /** Weekly phone checkup. */
    const val WEEKLY_CHECKUP = 4103

    const val NEW_APPS_INTERVAL_MS = 15 * 60_000L
    const val NEW_APPS_FLEX_MS = 5 * 60_000L
    const val CHECKUP_INTERVAL_MS = 7 * 24 * 3_600_000L
    const val CHECKUP_FLEX_MS = 24 * 3_600_000L
    private const val NOW_DELAY_MS = 1_000L
    private const val NOW_DEADLINE_MS = 10 * 60_000L

    /** Which jobs should exist for these settings. */
    data class Plan(val keep: Set<Int>, val cancel: Set<Int>)

    fun plan(newApps: Boolean, weekly: Boolean): Plan {
        val newAppJobs = setOf(NEW_APPS_PERIODIC, NEW_APPS_NOW)
        val keep = buildSet {
            if (newApps) add(NEW_APPS_PERIODIC)
            if (weekly) add(WEEKLY_CHECKUP)
        }
        val cancel = buildSet {
            if (!newApps) addAll(newAppJobs)
            if (!weekly) add(WEEKLY_CHECKUP)
        }
        return Plan(keep, cancel)
    }

    /**
     * Whether an app start should queue an extra check now: when no check has completed yet (a baseline is needed) or
     * the last one is older than a full period (for example after the app was force-stopped, which cancels its jobs).
     * A process started by the periodic job itself does not queue a second run.
     */
    fun catchUpNeeded(lastRunAt: Long?, now: Long): Boolean = lastRunAt == null || now - lastRunAt > NEW_APPS_INTERVAL_MS + NEW_APPS_FLEX_MS

    /**
     * Whether to queue a new-app check now. [previous] is the setting before this change, or null on app start.
     * Switching the check on runs it at once (a fresh baseline); an app start runs it only if [catchUpNeeded].
     */
    fun runNewAppCheckNow(previous: Boolean?, enabled: Boolean, lastRunAt: Long?, now: Long): Boolean = when {
        !enabled -> false
        previous == null -> catchUpNeeded(lastRunAt, now)
        else -> !previous
    }

    /** Schedules or cancels the jobs for these settings. Safe to call often. */
    fun sync(context: Context, newApps: Boolean, weekly: Boolean, runNewAppCheckNow: Boolean) {
        val js = context.getSystemService(JobScheduler::class.java) ?: return
        val plan = plan(newApps, weekly)
        plan.cancel.forEach { id -> runCatching { js.cancel(id) } }
        if (NEW_APPS_PERIODIC in plan.keep) ensurePeriodic(js, context, NEW_APPS_PERIODIC, NewAppCheckJobService::class.java, NEW_APPS_INTERVAL_MS, NEW_APPS_FLEX_MS, batteryNotLow = true)
        if (WEEKLY_CHECKUP in plan.keep) ensurePeriodic(js, context, WEEKLY_CHECKUP, PhoneCheckupJobService::class.java, CHECKUP_INTERVAL_MS, CHECKUP_FLEX_MS, batteryNotLow = true)
        if (newApps && runNewAppCheckNow) runNewAppCheckSoon(context)
    }

    /** Queues one new-app check to run within seconds (Android may delay it, for example in battery saver). */
    fun runNewAppCheckSoon(context: Context) {
        val js = context.getSystemService(JobScheduler::class.java) ?: return
        if (js.getPendingJob(NEW_APPS_NOW) != null) return
        val job = JobInfo.Builder(NEW_APPS_NOW, ComponentName(context, NewAppCheckJobService::class.java))
            .setMinimumLatency(NOW_DELAY_MS)
            .setOverrideDeadline(NOW_DEADLINE_MS)
            .build()
        runCatching { js.schedule(job) }
    }

    private fun ensurePeriodic(js: JobScheduler, context: Context, id: Int, service: Class<*>, interval: Long, flex: Long, batteryNotLow: Boolean) {
        val pending = runCatching { js.getPendingJob(id) }.getOrNull()
        if (pending != null && pending.isPeriodic && pending.intervalMillis == interval && pending.isPersisted && pending.isRequireBatteryNotLow == batteryNotLow) return
        val job = JobInfo.Builder(id, ComponentName(context, service))
            .setPeriodic(interval, flex)
            .setPersisted(true)
            .setRequiresBatteryNotLow(batteryNotLow)
            .build()
        runCatching { js.schedule(job) }
    }
}
