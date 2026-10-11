package com.dpdpxray.core.rules

import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.PlanConditions
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.StartMode
import com.dpdpxray.core.rules.VisibilityDiagnosis.Cause
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A capture deeper than DNS (the full-relay capture's flows) plugs in through [ConnectionSource]. Without one, every rule
 * is unchanged. With one, "0 lookups but N connections" is explained as the app's own DNS or fixed addresses, and named
 * connections let a low-lookup plan be scored.
 */
class ConnectionSourceTest {
    private val s = 1_000_000_000L
    private val catalog = TrackerCatalog.loadDefault()

    private fun session(lookups: List<String> = emptyList(), startMode: StartMode = StartMode.RESTARTED, fresh: Boolean = true) = AuditSession(
        id = "x", targetPackage = "com.example.app", targetLabel = "Example", plan = AuditPlan.SILENT, startedAtNanos = 0, endedAtNanos = 40 * s,
        netEvents = lookups.mapIndexed { i, h -> NetEvent((i + 1) * s, h) }, freshState = fresh,
        conditions = PlanConditions(startMode = startMode, runningBeforeStart = true),
    )

    private fun source(vararg c: SeenConnection) = ConnectionSource { c.toList() }

    @Test fun `without a deeper capture nothing changes`() {
        val r = RulesEngine(catalog).evaluate(listOf(session()))
        assertEquals(ScoreBand.INCONCLUSIVE, r.band)
        assertEquals(r, RulesEngine(catalog, connections = ConnectionSource.NONE).evaluate(listOf(session())))
    }

    @Test fun `named connections with no visible lookups are scored from the connections, worded as connections`() {
        val conns = source(
            SeenConnection(2 * s, "app-measurement.com", noLookup = true),
            SeenConnection(3 * s, "api.example-app.test", noLookup = true),
        )
        val r = RulesEngine(catalog, connections = conns).evaluate(listOf(session()))
        assertFalse(r.findings.any { it.checkId == CheckId.V0 })
        assertNotNull(r.score)
        val c1 = r.findings.single { it.checkId == CheckId.C1 }
        assertTrue(c1.evidence.first(), c1.evidence.first().contains("connected"))
        assertFalse(c1.evidence.first(), c1.evidence.first().contains("looked up"))
    }

    @Test fun `plans with enough lookups ignore connections, so nothing is counted twice`() {
        val conns = source(SeenConnection(2 * s, "app-measurement.com", noLookup = true))
        val plain = RulesEngine(catalog).evaluate(listOf(session(listOf("api.example-app.test", "cdn.example-app.test"))))
        val withConns = RulesEngine(catalog, connections = conns).evaluate(listOf(session(listOf("api.example-app.test", "cdn.example-app.test"))))
        assertEquals(plain, withConns)
    }

    @Test fun `unnamed connections after a restart are diagnosed as the app's own DNS or fixed addresses`() {
        val conns = source(SeenConnection(2 * s, null, noLookup = true), SeenConnection(3 * s, null, noLookup = true), SeenConnection(4 * s, null, noLookup = false))
        val r = RulesEngine(catalog, connections = conns).evaluate(listOf(session()))
        assertEquals(ScoreBand.INCONCLUSIVE, r.band)
        val d = VisibilityDiagnosis.forResult(r, listOf(session()), conns)
        assertEquals(Cause.OWN_DNS, d.first().cause)
        assertTrue(d.first().evidence.single(), d.first().evidence.single().contains("3 connections (2 to addresses it never looked up)"))
        assertTrue(d.first().sentence, d.first().sentence.contains("own DNS"))
    }

    @Test fun `without a restart, connections that skip lookups point at the old cache first`() {
        val conns = source(SeenConnection(2 * s, null, noLookup = true))
        val d = VisibilityDiagnosis.diagnose(listOf(session(startMode = StartMode.NOT_RESTARTED, fresh = false)), conns)
        assertEquals(Cause.ALREADY_RUNNING, d.first().cause)
        assertTrue(d.first().evidence.single(), d.first().evidence.single().contains("1 connection without a lookup TraceVeil saw"))
    }

    @Test fun `a deeper capture that saw no connections at all reads as a quiet app`() {
        val d = VisibilityDiagnosis.diagnose(listOf(session()), ConnectionSource { emptyList() })
        assertEquals(Cause.APP_QUIET, d.first().cause)
    }
}
