package com.dpdpxray.app.autoguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoGuardJobsTest {
    private val now = 1_791_500_000_000L

    @Test fun `job ids are fixed and distinct, so testers can force-run them`() {
        assertEquals(4101, AutoGuardJobs.NEW_APPS_PERIODIC)
        assertEquals(4102, AutoGuardJobs.NEW_APPS_NOW)
        assertEquals(4103, AutoGuardJobs.WEEKLY_CHECKUP)
        assertEquals(3, setOf(AutoGuardJobs.NEW_APPS_PERIODIC, AutoGuardJobs.NEW_APPS_NOW, AutoGuardJobs.WEEKLY_CHECKUP).size)
    }

    @Test fun `periods respect Android's 15 minute minimum and a weekly checkup`() {
        assertEquals(15 * 60_000L, AutoGuardJobs.NEW_APPS_INTERVAL_MS)
        assertTrue(AutoGuardJobs.NEW_APPS_FLEX_MS < AutoGuardJobs.NEW_APPS_INTERVAL_MS)
        assertEquals(7 * 24 * 3_600_000L, AutoGuardJobs.CHECKUP_INTERVAL_MS)
    }

    @Test fun `both on by default keeps both periodic jobs`() {
        val plan = AutoGuardJobs.plan(newApps = true, weekly = true)
        assertEquals(setOf(AutoGuardJobs.NEW_APPS_PERIODIC, AutoGuardJobs.WEEKLY_CHECKUP), plan.keep)
        assertTrue(plan.cancel.isEmpty())
    }

    @Test fun `switching a check off cancels its jobs and only its jobs`() {
        val noNewApps = AutoGuardJobs.plan(newApps = false, weekly = true)
        assertEquals(setOf(AutoGuardJobs.NEW_APPS_PERIODIC, AutoGuardJobs.NEW_APPS_NOW), noNewApps.cancel)
        assertEquals(setOf(AutoGuardJobs.WEEKLY_CHECKUP), noNewApps.keep)
        val noWeekly = AutoGuardJobs.plan(newApps = true, weekly = false)
        assertEquals(setOf(AutoGuardJobs.WEEKLY_CHECKUP), noWeekly.cancel)
        val off = AutoGuardJobs.plan(newApps = false, weekly = false)
        assertTrue(off.keep.isEmpty())
        assertEquals(3, off.cancel.size)
    }

    @Test fun `an app start queues a check only when none ran recently`() {
        assertTrue("first ever start needs a baseline", AutoGuardJobs.runNewAppCheckNow(null, true, lastRunAt = null, now = now))
        assertFalse("started by the periodic job itself", AutoGuardJobs.runNewAppCheckNow(null, true, lastRunAt = now - 14 * 60_000L, now = now))
        assertTrue("after a force stop cancelled the jobs", AutoGuardJobs.runNewAppCheckNow(null, true, lastRunAt = now - 6 * 3_600_000L, now = now))
        assertFalse(AutoGuardJobs.runNewAppCheckNow(null, false, lastRunAt = null, now = now))
    }

    @Test fun `switching the check on runs it at once, other changes do not`() {
        assertTrue(AutoGuardJobs.runNewAppCheckNow(previous = false, enabled = true, lastRunAt = now, now = now))
        assertFalse("only the weekly switch changed", AutoGuardJobs.runNewAppCheckNow(previous = true, enabled = true, lastRunAt = null, now = now))
        assertFalse(AutoGuardJobs.runNewAppCheckNow(previous = true, enabled = false, lastRunAt = null, now = now))
    }
}
