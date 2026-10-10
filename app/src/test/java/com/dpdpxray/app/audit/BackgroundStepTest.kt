package com.dpdpxray.app.audit

import com.dpdpxray.app.report.EvidenceReport
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The background step only records a background moment it has confirmed: Home pressed and the app off screen. */
class BackgroundStepTest {
    private var nowMs = 0L
    private val clock = { nowMs * 1_000_000L }
    private val pause: suspend (Long) -> Unit = { nowMs += it }

    @Test fun `app that leaves the screen is confirmed at the moment it was seen gone`() = runBlocking {
        val onScreen = ArrayDeque(listOf(true, true, false))
        var pressed = 0
        val out = BackgroundStep({ pressed++; true }, { onScreen.removeFirst() }, clock, pause, pollMs = 200).run(3_000)
        assertEquals(1, pressed)
        assertEquals(BackgroundStep.Outcome.Confirmed(400 * 1_000_000L), out)
    }

    @Test fun `home that cannot be pressed is not established and nothing is polled`() = runBlocking {
        val out = BackgroundStep({ false }, { fail("must not poll"); null }, clock, pause).run(3_000)
        assertTrue(out is BackgroundStep.Outcome.NotConfirmed)
        assertTrue((out as BackgroundStep.Outcome.NotConfirmed).reason, out.reason.contains("Home could not be pressed"))
    }

    @Test fun `app that stays on screen is not called background, and polling is bounded`() = runBlocking {
        var polls = 0
        val out = BackgroundStep({ true }, { polls++; true }, clock, pause, pollMs = 250).run(1_000)
        assertEquals(BackgroundStep.Outcome.NotConfirmed("the app was still on screen 1.0 s after Home was pressed"), out)
        assertEquals(5, polls)
        assertEquals(1_000L, nowMs)
    }

    @Test fun `a screen that cannot be read never confirms the background`() = runBlocking {
        val out = BackgroundStep({ true }, { null }, clock, pause, pollMs = 500).run(1_000)
        assertEquals(BackgroundStep.Outcome.NotConfirmed("the screen could not be read to confirm the app had left it"), out)
    }

    @Test fun `normal timing watches 10 s in the background, Fast Preview not at all`() {
        val full = AuditConfig("demo.test", "Demo", listOf(AuditPlan.SILENT, AuditPlan.REJECT, AuditPlan.ACCEPT))
        assertEquals(10_000L, AuditTimingPolicy.forAudit(full).backgroundMs)
        assertEquals(0L, AuditTimingPolicy.forAudit(full.copy(plans = listOf(AuditPlan.ACCEPT), fastPreview = true)).backgroundMs)
        // The existing foreground windows are unchanged: the background step is added after them, never carved out.
        val t = AuditTimingPolicy.forAudit(full)
        assertEquals(listOf(20_000L, 2_500L, 15_000L, 25_000L), listOf(t.waitConsentMs, t.dwellMs, t.observeMs, t.silentMs))
    }

    @Test fun `report coverage mentions the background window only for plans that recorded one`() {
        val s = AuditSession("a", "demo.test", "Demo", AuditPlan.REJECT)
        assertTrue(EvidenceReport.coverageNotes(listOf(s), AuditTimingPolicy.NORMAL, sample = false).none { it.startsWith("Background check") })
        val notes = EvidenceReport.coverageNotes(listOf(s.copy(backgroundStartedNanos = 30_000_000_000L)), AuditTimingPolicy.NORMAL, sample = false)
        assertTrue(notes.any { it.startsWith("Background check: after the observation window the app was sent to the background") && it.contains("Plan B · Reject") })
    }

    @Test fun `background note states what was confirmed`() {
        assertEquals(
            "Background check: Home was pressed after the observation window; the app was confirmed off screen 41.3 s after launch and watched for 10 s more.",
            BackgroundStep.confirmedNote(41_300_000_000L, 10_000),
        )
        assertEquals("Background check not established: the screen could not be read.", BackgroundStep.notConfirmedNote("the screen could not be read"))
    }
}
