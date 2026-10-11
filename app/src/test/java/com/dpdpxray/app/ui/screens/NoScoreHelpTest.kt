package com.dpdpxray.app.ui.screens

import com.dpdpxray.app.data.AuditBundle
import com.dpdpxray.app.data.AuditMeta
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.PlanConditions
import com.dpdpxray.core.model.StartMode
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.rules.VisibilityDiagnosis.Action
import com.dpdpxray.core.rules.VisibilityDiagnosis.Cause
import com.dpdpxray.core.rules.VisibilityDiagnosis.Item
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The result's "Why there's no score" section: when it shows, what its one filled action is, and which links follow. */
class NoScoreHelpTest {
    private val engine = RulesEngine(TrackerCatalog.loadDefault())

    private fun bundle(session: AuditSession, sample: Boolean = false): AuditBundle {
        val result = engine.evaluate(listOf(session))
        val meta = AuditMeta("audit-1", session.targetPackage, session.targetLabel, 1, result.score, result.band, result.findings.size,
            listOf(session.plan.label), sample = sample)
        return AuditBundle(meta, listOf(session), result, File("."))
    }

    private fun session(hosts: List<String>, conditions: PlanConditions?, fresh: Boolean = false) = AuditSession(
        "s", "com.reskill.hacktracker", "HackTracker", AuditPlan.REJECT, endedAtNanos = 40_000_000_000,
        netEvents = hosts.mapIndexed { i, h -> NetEvent((i + 1) * 1_000_000_000L, h) }, freshState = fresh, conditions = conditions,
    )

    @Test fun `the live HackTracker result offers check again with a restart as the one filled action`() {
        val b = bundle(session(emptyList(), PlanConditions(startMode = StartMode.NOT_RESTARTED, runningBeforeStart = true)))
        assertEquals(Cause.ALREADY_RUNNING, NoScoreHelp.items(b).first().cause)
        assertTrue(NoScoreHelp.hasPrimary(b))
        assertEquals(emptyList<Action>(), NoScoreHelp.otherActions(NoScoreHelp.items(b)))
    }

    @Test fun `after a settings fix the restart stays one tap away, and check again is never offered twice`() {
        val items = listOf(
            Item(Cause.PRIVATE_DNS, listOf("e"), "s", Action.TURN_OFF_PRIVATE_DNS),
            Item(Cause.APP_CRASHED, listOf("e"), "s", Action.CHECK_AGAIN),
            Item(Cause.ALREADY_RUNNING, listOf("e"), "s", Action.CHECK_AGAIN_WITH_RESTART),
        )
        assertEquals(listOf(Action.CHECK_AGAIN_WITH_RESTART), NoScoreHelp.otherActions(items))
        val crashOnly = listOf(Item(Cause.APP_CRASHED, listOf("e"), "s", Action.CHECK_AGAIN))
        assertEquals(emptyList<Action>(), NoScoreHelp.otherActions(crashOnly))
    }

    @Test fun `a consent-history result explains the limit without offering a restart, and a sample shows nothing`() {
        val kept = bundle(session(listOf("app-measurement.com", "api.hacktracker.example"), PlanConditions(startMode = StartMode.RESTARTED)))
        assertTrue(NoScoreHelp.historyUnknown(kept))
        assertTrue(NoScoreHelp.items(kept).isEmpty())
        assertFalse(NoScoreHelp.hasPrimary(kept))
        assertEquals(emptyList<Action>(), NoScoreHelp.otherActions(NoScoreHelp.items(kept)))
        val sample = bundle(session(emptyList(), null), sample = true)
        assertTrue(NoScoreHelp.items(sample).isEmpty())
        assertFalse(NoScoreHelp.historyUnknown(sample))
    }

    @Test fun `a scored result has no section`() {
        val scored = bundle(session(listOf("api.hacktracker.example", "cdn.hacktracker.example"), PlanConditions(startMode = StartMode.RESET), fresh = true))
        assertTrue(NoScoreHelp.items(scored).isEmpty())
        assertFalse(NoScoreHelp.historyUnknown(scored))
        assertTrue(NoScoreHelp.historyBody.en.contains("real limit") && NoScoreHelp.noShizuku.en.contains("Force stop"))
    }
}
