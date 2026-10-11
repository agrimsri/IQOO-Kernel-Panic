package com.dpdpxray.core.rules

import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.PlanConditions
import com.dpdpxray.core.model.RestartChoice
import com.dpdpxray.core.model.StartMode
import com.dpdpxray.core.report.AuditJson
import com.dpdpxray.core.rules.VisibilityDiagnosis.Cause
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An app with something running that a restart would switch off (an accessibility service, a VPN, a keyboard...) is
 * not restarted unless the person chooses it. The plan records what was found and the choice; a low-visibility result
 * then names the skipped restart as a choice, never as a failure.
 */
class RestartChoiceDiagnosisTest {
    private val engine = RulesEngine(TrackerCatalog.loadDefault())

    private fun plan(conditions: PlanConditions) = AuditSession(
        "s", "com.reskill.hacktracker", "HackTracker", AuditPlan.REJECT, endedAtNanos = 40_000_000_000, freshState = false, conditions = conditions,
    )

    private val skipped = PlanConditions(
        startMode = StartMode.NOT_RESTARTED, runningBeforeStart = true,
        restartProblem = "restarting it would switch off its accessibility service",
        restartSideEffects = listOf("ACCESSIBILITY"), restartChoice = RestartChoice.SKIPPED_BY_DEFAULT,
    )

    @Test fun `a restart left out to keep an accessibility service on is named as a choice`() {
        val s = plan(skipped)
        val item = VisibilityDiagnosis.forResult(engine.evaluate(listOf(s)), listOf(s)).first()
        assertEquals(Cause.ALREADY_RUNNING, item.cause)
        assertTrue(item.evidence.single(), item.evidence.single().startsWith(
            "Plan B · Reject: TraceVeil did not restart the app first (restarting it would switch off its accessibility service); it was already running."))
        assertTrue(item.sentence, item.sentence.startsWith("TraceVeil left the app running, so as not to switch off something it runs"))
        assertFalse(item.sentence, item.sentence.contains("could not restart"))
        assertEquals(VisibilityDiagnosis.Action.WATCH_LONGER, item.action)
    }

    @Test fun `a restart that failed is still a failure`() {
        val s = plan(skipped.copy(restartChoice = null, restartSideEffects = null, restartProblem = "stopping it failed: exit 1"))
        val item = VisibilityDiagnosis.forResult(engine.evaluate(listOf(s)), listOf(s)).first()
        assertTrue(item.evidence.single(), item.evidence.single().contains("could not restart the app first (stopping it failed: exit 1)"))
        assertTrue(item.sentence.contains("could not restart it"))
    }

    @Test fun `what a restart would switch off and the choice survive a save, and are not written when absent`() {
        val back = AuditJson.decodeSessions(AuditJson.encodeSessions(listOf(plan(skipped)))).single()
        assertEquals(skipped, back.conditions)
        val plain = AuditJson.encodeSessions(listOf(plan(PlanConditions(startMode = StartMode.RESTARTED))))
        assertFalse(plain.contains("restartSideEffects"))
        assertFalse(plain.contains("restartChoice"))
    }
}
