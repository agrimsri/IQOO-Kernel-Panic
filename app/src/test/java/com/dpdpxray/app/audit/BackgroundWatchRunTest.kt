package com.dpdpxray.app.audit

import com.dpdpxray.app.data.Audience
import com.dpdpxray.app.data.AuditMeta
import com.dpdpxray.app.ui.guide.LivePhase
import com.dpdpxray.app.ui.modes.CheckChoice
import com.dpdpxray.app.ui.modes.CheckSheet
import com.dpdpxray.core.copilot.VerdictWords
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.WatchSpan
import com.dpdpxray.core.rules.QuietBackgroundAppReplay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The background watch as the app runs it: its plan and timing, the loop that keeps the capture on (with its Stop and
 * the app's on-screen moments), and the words of its notification. JVM only, with a fake clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundWatchRunTest {
    private val ms = 1_000_000L

    // --- Plan and timing ------------------------------------------------------------------------------------------------

    @Test fun `a watch is one no-interaction plan that never clears data, taps or shortens to a preview`() {
        Audience.entries.forEach { a ->
            for (minutes in listOf(5, 10, 30)) {
                val c = CheckSheet.config(a, "com.example.rollcall", "RollCall", CheckChoice.WATCH, allowReset = true, shizukuReady = true,
                    agentTaps = true, childFacing = false, includeGms = true, watchMinutes = minutes)
                assertEquals(listOf(AuditPlan.SILENT), c.plans)
                assertEquals(minutes * 60_000L, c.watchMs)
                assertFalse("$a: a watch never clears data", c.resetAppData)
                assertFalse(c.autoDrive)
                assertFalse(c.fastPreview)
                assertTrue(c.includeGms)
                assertEquals(AuditTimingPolicy.BACKGROUND_WATCH, AuditTimingPolicy.profile(c))
            }
        }
        assertEquals(600_000L, CheckSheet.watchConfig("p", "P").watchMs)
    }

    @Test fun `a watch cannot be disguised as another check`() {
        listOf(
            { AuditConfig("p", "P", listOf(AuditPlan.ACCEPT), watchMs = 600_000) },
            { AuditConfig("p", "P", listOf(AuditPlan.SILENT, AuditPlan.REJECT, AuditPlan.ACCEPT), watchMs = 600_000) },
            { AuditConfig("p", "P", listOf(AuditPlan.SILENT), resetAppData = true, watchMs = 600_000) },
            { AuditConfig("p", "P", listOf(AuditPlan.SILENT), watchMs = 0) },
        ).forEach { make ->
            try { make(); fail("accepted an invalid watch") } catch (_: IllegalArgumentException) { }
        }
        val plain = AuditConfig("p", "P", listOf(AuditPlan.SILENT))
        assertFalse(plain.isWatch)
        assertEquals(AuditTimingPolicy.NORMAL, AuditTimingPolicy.profile(plain))
    }

    @Test fun `the watch's one window is its whole length, with no consent wait or Home step`() = runTest {
        val t = AuditTimingPolicy.forAudit(CheckSheet.watchConfig("p", "P", minutes = 10))
        assertEquals(0L, t.waitConsentMs)
        assertEquals(0L, t.backgroundMs)
        var finished = false
        launch { t.observe(AuditPlan.SILENT); finished = true }
        advanceTimeBy(599_000)
        assertFalse(finished)
        advanceUntilIdle()
        assertEquals(600_000L, currentTime)
        assertTrue(AuditTimingPolicy.coverageHint(AuditTimingPolicy.BACKGROUND_WATCH).contains("its consent screen was not tested"))
    }

    @Test fun `checking a watch again repeats the same watch and never restarts or clears, whatever was asked`() {
        val watch = QuietBackgroundAppReplay.backgroundWatch()
        val meta = AuditMeta("audit-w", watch.targetPackage, watch.targetLabel, 1, null, ScoreBand.INCONCLUSIVE, 2, listOf(AuditPlan.SILENT.label),
            timingProfile = AuditTimingPolicy.BACKGROUND_WATCH, includeGms = false)
        for (mode in CheckAgain.Mode.entries) for (restart in RestartPolicy.entries) {
            val c = CheckAgain.config(meta, listOf(watch), mode, restart)
            assertEquals(600_000L, c.watchMs)
            assertEquals(listOf(AuditPlan.SILENT), c.plans)
            assertFalse("$mode $restart", c.resetAppData)
        }
    }

    // --- The loop -------------------------------------------------------------------------------------------------------

    private class Fake(var now: Long = 1_000 * 1_000_000L) {
        val ticks = mutableListOf<Long>()
        var stopAt: Long? = null
        var captureLostAt: Long? = null
        var screen: (Long) -> ScreenSample = { ScreenSample.OFF }
        fun loop(sampleEveryMs: Long = 5_000) = WatchLoop(
            clockNanos = { now },
            pause = { now += it * 1_000_000L },
            stopRequested = { stopAt?.let { now >= it } == true },
            captureRunning = { captureLostAt?.let { now < it } ?: true },
            screen = { screen(now) },
            onTick = { ticks += it },
            sampleEveryMs = sampleEveryMs,
        )
    }

    @Test fun `a watch runs its planned length, counting down, and ends finished`() = runTest {
        val f = Fake()
        val o = f.loop().run(600_000)
        assertEquals(WatchLoop.End.FINISHED, o.end)
        assertEquals(600_000L, o.watchedMs)
        assertEquals(600_000L, f.ticks.first())
        assertTrue(f.ticks.zipWithNext().all { (a, b) -> b < a })
        assertEquals(o.startNanos, o.firstOffNanos)
        assertTrue(o.onScreen.isEmpty())
    }

    @Test fun `Stop ends the watch early and keeps its length, a lost capture ends it too`() = runTest {
        val stopped = Fake().apply { stopAt = now + 252_000 * ms }
        val o = stopped.loop().run(600_000)
        assertEquals(WatchLoop.End.STOPPED_BY_PERSON, o.end)
        assertEquals(252_000L, o.watchedMs)
        val lost = Fake().apply { captureLostAt = now + 90_000 * ms }
        assertEquals(WatchLoop.End.CAPTURE_STOPPED, lost.loop().run(600_000).end)
    }

    @Test fun `while the app is on screen, or the screen cannot be read, no moment counts as background`() = runTest {
        val f = Fake()
        val start = f.now
        // Off screen, then the person opens the app from 100 s to 130 s, then the screen is unreadable from 300 s to 310 s.
        f.screen = { t ->
            val s = (t - start) / ms / 1000
            when (s) {
                in 100..129 -> ScreenSample.ON
                in 300..309 -> ScreenSample.UNKNOWN
                else -> ScreenSample.OFF
            }
        }
        val o = f.loop().run(600_000)
        assertEquals(start, o.firstOffNanos)
        // Each span runs from the last off-screen reading before it to the first one after it.
        assertEquals(listOf(WatchSpan(start + 95_000 * ms, start + 130_000 * ms), WatchSpan(start + 295_000 * ms, start + 310_000 * ms)), o.onScreen)
    }

    @Test fun `an app never seen off screen has no background time`() = runTest {
        val f = Fake().apply { screen = { ScreenSample.UNKNOWN } }
        val o = f.loop().run(60_000)
        assertNull(o.firstOffNanos)
        assertTrue(o.onScreen.isEmpty())
    }

    @Test fun `spans before the app was first seen off screen are not kept, and a final on-screen run reaches the end`() {
        val s = listOf(0L to ScreenSample.ON, 5L to ScreenSample.OFF, 10L to ScreenSample.OFF, 15L to ScreenSample.ON)
        assertEquals(5L, WatchLoop.firstOff(s))
        assertEquals(listOf(WatchSpan(10, 20)), WatchLoop.spans(s, end = 20))
    }

    // --- Words ----------------------------------------------------------------------------------------------------------

    @Test fun `the notification counts whole minutes down and says only this app's traffic goes through TraceVeil`() {
        assertEquals("7 min left", WatchCopy.minutesLeft(420_000).en)
        assertEquals("7 min left", WatchCopy.minutesLeft(360_001).en)
        assertEquals("6 min left", WatchCopy.minutesLeft(360_000).en)
        assertEquals("1 min left", WatchCopy.minutesLeft(30_000).en)
        assertEquals("1 min left", WatchCopy.minutesLeft(0).en)
        assertEquals("7 मिनट बाकी", WatchCopy.minutesLeft(420_000).hi)
        assertEquals("Watching RollCall's connections · 7 min left", WatchCopy.notificationTitle("RollCall", 420_000).en)
        assertTrue(WatchCopy.notificationText("RollCall").en.startsWith("Only RollCall's traffic goes through TraceVeil"))
        assertEquals("Stop and save", WatchCopy.stop.en)
        assertEquals("Background watch: 7 min left", WatchCopy.step(420_000))
        assertEquals(LivePhase.WATCHING, LivePhase.of(WatchCopy.step(420_000)))
        assertEquals("the ordinary background step is not a watch", LivePhase.OTHER, LivePhase.of("Watching RollCall in the background"))
    }

    @Test fun `every watch string is in English and Hindi, plain, with no verdict words`() {
        val all = WatchCopy.all()
        all.forEach { assertTrue(it.en, it.en.isNotBlank() && it.hi.any { c -> c in 'ऀ'..'ॿ' }) }
        all.forEach { assertNull(it.en, VerdictWords.find(it.en)); assertNull(it.hi, VerdictWords.find(it.hi)) }
        val en = all.joinToString(" ") { it.en }.lowercase()
        listOf("safe", "compliant", "illegal", "the first", "the only", "malicious").forEach { assertFalse(it, en.contains(it)) }
        assertEquals("Watch RollCall in the background for 10 minutes", WatchCopy.action("RollCall", 10).en)
        assertTrue(WatchCopy.LOCKED_NOTE.contains("expected"))
    }
}
