package com.dpdpxray.app.audit

import com.dpdpxray.app.consent.ConsentReappearance
import com.dpdpxray.app.data.AuditMeta
import com.dpdpxray.core.consent.BlockerKind
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.BlockedStep
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.PlanConditions
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.StartMode
import com.dpdpxray.core.rules.VisibilityDiagnosis
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pieces of a real-app check that run without a phone: refusals, hand-offs, caps, summaries and the one-tap retry. */
class RealAppFlowTest {

    // --- Which apps can be checked --------------------------------------------------------------------------------------

    private val ok = AuditTargets.Facts(installed = true, launchable = true, system = false)

    @Test fun `TraceVeil refuses to check itself, politely`() {
        val r = AuditTargets.refusal("com.dpdpxray.app", "TraceVeil", "com.dpdpxray.app", ok)!!
        assertTrue(r, r.startsWith("TraceVeil can't check itself"))
    }

    @Test fun `an app that is not installed, or has no screen to open, is refused with its name`() {
        assertTrue(AuditTargets.refusal("com.gone.app", "Gone", "com.dpdpxray.app", ok.copy(installed = false))!!.contains("Gone is not installed"))
        assertTrue(AuditTargets.refusal("com.svc.app", "Svc", "com.dpdpxray.app", ok.copy(launchable = false))!!.contains("no screen TraceVeil can open"))
        assertNull(AuditTargets.refusal("com.reskill.hacktracker", "HackTracker", "com.dpdpxray.app", ok))
    }

    @Test fun `a system app can be checked, with a note, and is never offered a reset`() {
        assertNull(AuditTargets.refusal("com.google.android.apps.docs", "Docs", "com.dpdpxray.app", ok.copy(system = true)))
        assertTrue(AuditTargets.systemNote("Docs").contains("system app"))
        assertFalse(CheckAgain.resetAllowed(debuggable = true, controlledDemo = false, allowlisted = true, system = true))
    }

    @Test fun `an app uninstalled, or a capture stopped, between plans ends the check and keeps what was captured`() {
        assertEquals(AuditTargets.Stop.Fail(AuditTargets.uninstalled("HackTracker")), AuditTargets.stop(captured = 0, reason = AuditTargets.uninstalled("HackTracker")))
        val keep = AuditTargets.stop(captured = 1, reason = AuditTargets.CAPTURE_STOPPED) as AuditTargets.Stop.KeepPartial
        assertTrue(keep.note, keep.note.endsWith("The plans before it are kept."))
        assertEquals(AuditTargets.uninstalled("HackTracker"), AuditTargets.beforePlan("HackTracker", installed = false, captureRunning = true))
        assertEquals(AuditTargets.CAPTURE_STOPPED, AuditTargets.beforePlan("HackTracker", installed = true, captureRunning = false))
        assertNull(AuditTargets.beforePlan("HackTracker", installed = true, captureRunning = true))
    }

    @Test fun `an app that crashes as it opens fails with words that say so`() {
        assertTrue(AuditTargets.didNotOpen("HackTracker", crashed = true).contains("closed with an error as it opened"))
        assertTrue(AuditTargets.didNotOpen("HackTracker", crashed = false).contains("did not come to the screen"))
    }

    // --- The phone is locked --------------------------------------------------------------------------------------------

    @Test fun `a locked phone pauses the plan until it is unlocked, and gives up after the limit`() = runBlocking {
        var now = 0L
        val unlocked = Waits.until(timeoutMs = 120_000, clockMs = { now }, pause = { now += it }) { now >= 30_000 }
        assertTrue(unlocked)
        assertTrue(now in 30_000..31_000)
        now = 0L
        assertFalse(Waits.until(timeoutMs = 120_000, clockMs = { now }, pause = { now += it }) { false })
        assertTrue(now in 120_000..121_000)
    }

    // --- Android permission prompts and the person's Continue ------------------------------------------------------------

    @Test fun `an Android permission prompt is never skipped or answered, the person is asked at once and the wait pauses`() = runBlocking {
        var now = 0L
        var skips = 0
        var handoffs = 0
        val consent = ConsentEvent(1, ConsentKind.SCREEN_APPEARED)
        val o = ConsentWaitAssist(
            probe = { consent.takeIf { now >= 50_000 } },
            detectBlocker = { BlockerKind.PERMISSION.takeIf { now < 40_000 } },
            trySkip = { skips++; "Allow" },
            onHandoff = { handoffs++ },
            onResume = {},
            clockMs = { now }, pause = { now += it },
        ).await(20_000)
        assertNotNull(o.consent)
        assertEquals(0, skips)
        assertEquals(1, handoffs)
        assertEquals(BlockerKind.PERMISSION, o.blocker)
        assertTrue("blocked ${o.blockedMs}", o.blockedMs in 39_000..40_500)
    }

    @Test fun `the person's Continue ends a hand-off the screen reader keeps seeing, and the normal wait resumes`() = runBlocking {
        var now = 0L
        var resumes = 0
        val o = ConsentWaitAssist(
            probe = { null },
            detectBlocker = { BlockerKind.SIGN_IN },
            trySkip = { null },
            onHandoff = {},
            onResume = { resumes++ },
            clockMs = { now }, pause = { now += it },
            userContinued = { now >= 30_000 },
        ).await(20_000)
        assertNull(o.consent)
        assertTrue(o.handedOff)
        assertEquals(1, resumes)
        assertTrue("blocked ${o.blockedMs}", o.blockedMs in 29_000..30_500)
        assertTrue("ended ${now}", now in 49_000..50_500)
    }

    @Test fun `a blocked step is recorded for the result, with what the agent and the person did`() {
        val o = ConsentWaitAssist.Outcome(ConsentEvent(1, ConsentKind.SCREEN_APPEARED), BlockerKind.FORM, listOf("Continue (after typing test values into Name)"), false, 3_000)
        assertEquals(BlockedStep("FORM", listOf("Continue (after typing test values into Name)"), false, 3_000, true), BlockerCopy.step(o))
        assertNull(BlockerCopy.step(ConsentWaitAssist.Outcome(null, null, emptyList(), false, 0)))
        val permission = BlockerCopy.notes(ConsentWaitAssist.Outcome(null, BlockerKind.PERMISSION, emptyList(), true, 12_000))
        assertTrue(permission.toString(), permission.any { it.contains("never answers") })
        assertTrue(BlockerCopy.promptBody(BlockerKind.PERMISSION).contains("Continue"))
        assertTrue(BlockerCopy.promptBody(BlockerKind.SIGN_IN).contains("Continue"))
        BlockerKind.entries.forEach { k ->
            assertTrue(BlockerCopy.promptTitle(k, emptyList()).isNotBlank() && BlockerCopy.promptTitle(k, emptyList(), hindi = true).isNotBlank())
            assertTrue(BlockerCopy.promptBody(k, hindi = true).isNotBlank())
        }
    }

    // --- A reprompt loop ----------------------------------------------------------------------------------------------

    @Test fun `a consent screen shown again more than three times is capped as evidence but every return is counted`() {
        assertNull(ConsentReappearance.capNote(3))
        val note = ConsentReappearance.capNote(7)!!
        assertTrue(note, note.contains("came back 7 times") && note.contains("first 3"))
        assertTrue(ConsentReappearance.countsAsReturn(isConsent = true, wasVisible = false, screenSeen = true, choiceMade = true))
        assertFalse(ConsentReappearance.shouldRecord(true, false, true, true, ConsentReappearance.MAX_PER_PLAN))
    }

    // --- What the result says -----------------------------------------------------------------------------------------

    private fun session(plan: AuditPlan, conditions: PlanConditions?, consent: List<ConsentEvent> = emptyList()) = AuditSession(
        id = plan.name, targetPackage = "com.reskill.hacktracker", targetLabel = "HackTracker", plan = plan,
        conditions = conditions, consentEvents = consent,
    )

    @Test fun `the blocked-steps summary says which plans were blocked and what the person did`() {
        val sessions = listOf(
            session(AuditPlan.REJECT, PlanConditions(blocker = BlockedStep("SIGN_IN", handedOff = true, waitedMs = 42_000, consentReached = true))),
            session(AuditPlan.ACCEPT, PlanConditions(blocker = BlockedStep("OTP", handedOff = true, waitedMs = 180_000, consentReached = false)),
                consent = listOf(ConsentEvent(2, ConsentKind.PERMISSION_DIALOG, "Allow HackTracker to send you notifications?"))),
        )
        val lines = BlockedSteps.lines(sessions)
        assertEquals(3, lines.size)
        assertTrue(lines[0].detail.en, lines[0].detail.en.contains("You completed it") && lines[0].detail.en.contains("42 s"))
        assertTrue(lines[1].detail.en, lines[1].detail.en.contains("consent was not tested"))
        assertTrue(lines[2].detail.en, lines[2].detail.en.contains("Allow HackTracker to send you notifications?") && lines[2].detail.en.contains("never answers"))
        lines.forEach { assertTrue(it.title.hi.isNotBlank() && it.detail.hi.isNotBlank()) }
        assertTrue(BlockedSteps.lines(listOf(session(AuditPlan.REJECT, null))).isEmpty())
    }

    @Test fun `every diagnosis cause and action has words in both languages`() {
        VisibilityDiagnosis.Cause.entries.forEach { c ->
            val t = DiagnosisCopy.title(c)
            assertTrue(c.name, t.en.isNotBlank() && t.hi.isNotBlank())
            val body = DiagnosisCopy.body(VisibilityDiagnosis.Item(c, listOf("e"), "The English sentence.", VisibilityDiagnosis.Action.NONE))
            assertEquals("The English sentence.", body.en)
            assertTrue(body.hi.isNotBlank())
        }
        VisibilityDiagnosis.Action.entries.filter { it != VisibilityDiagnosis.Action.NONE }.forEach { a ->
            assertNotNull(a.name, DiagnosisCopy.action(a))
        }
        assertNull(DiagnosisCopy.action(VisibilityDiagnosis.Action.NONE))
        assertTrue(DiagnosisCopy.restart.en.contains("restart") && DiagnosisCopy.restartNote.en.contains("data stays"))
        assertTrue(DiagnosisCopy.resetConfirmBody("HackTracker").en.contains("clears"))
    }

    // --- One tap: check again ----------------------------------------------------------------------------------------

    private fun meta(plans: List<AuditPlan>, timing: String? = AuditTimingPolicy.NORMAL, autoDrive: Boolean? = true) = AuditMeta(
        id = "audit-1", targetPackage = "com.reskill.hacktracker", targetLabel = "HackTracker", createdAtMs = 1, score = null,
        band = ScoreBand.INCONCLUSIVE, findingCount = 2, plans = plans.map { it.label }, timingProfile = timing, autoDrive = autoDrive, includeGms = false,
    )

    @Test fun `check again with a restart repeats the same check and never clears data`() {
        val c = CheckAgain.config(meta(listOf(AuditPlan.REJECT)), listOf(session(AuditPlan.REJECT, null)), CheckAgain.Mode.RESTART)
        assertEquals(listOf(AuditPlan.REJECT), c.plans)
        assertFalse(c.resetAppData)
        assertTrue(c.autoDrive)
        assertEquals("com.reskill.hacktracker", c.targetPackage)
    }

    @Test fun `check again with a reset is for developer-owned apps only and keeps a Full check full`() {
        val full = listOf(AuditPlan.SILENT, AuditPlan.REJECT, AuditPlan.ACCEPT)
        val c = CheckAgain.config(meta(full), full.map { session(it, null) }, CheckAgain.Mode.RESET)
        assertEquals(full, c.plans)
        assertTrue(c.resetAppData)
        assertTrue(CheckAgain.resetAllowed(debuggable = true, controlledDemo = false, allowlisted = false, system = false))
        assertTrue(CheckAgain.resetAllowed(debuggable = false, controlledDemo = true, allowlisted = false, system = false))
        assertFalse("a personal app is never offered a reset", CheckAgain.resetAllowed(debuggable = false, controlledDemo = false, allowlisted = false, system = false))
    }

    @Test fun `an older audit without recorded options repeats safely, with the person tapping`() {
        val c = CheckAgain.config(meta(listOf(AuditPlan.ACCEPT), timing = AuditTimingPolicy.FAST_PREVIEW, autoDrive = null),
            listOf(session(AuditPlan.ACCEPT, null)), CheckAgain.Mode.RESTART)
        assertFalse(c.autoDrive)
        assertTrue(c.fastPreview)
    }
}
