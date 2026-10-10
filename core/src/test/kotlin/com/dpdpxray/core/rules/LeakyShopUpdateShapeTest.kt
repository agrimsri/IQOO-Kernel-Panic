package com.dpdpxray.core.rules

import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.AuditSource
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.RejectOption
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.ScreenAudit
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The planned LeakyShop 1.1 demo update (leakyshop flavor `leakyV11`, Shop.kt) as test data, shaped like the
 * device-shaped 1.0 audit in [DemoScoreRegressionTest]: 1.1 adds a visible Reject button to the first screen and stops
 * contacting trackers after Reject, but still looks up trackers at launch before any choice and still pre-ticks the
 * box. Test data, not a device observation: it pins what the engine should say about that partial fix.
 */
class LeakyShopUpdateShapeTest {
    private val S = 1_000_000_000L
    private val engine = RulesEngine(TrackerCatalog.loadDefault())
    private fun net(t: Double, host: String) = NetEvent(tNanos = (t * S).toLong(), hostname = host)
    private fun summary(r: AuditResult) = Triple(r.score, r.band, r.findings.map { it.checkId }.toSet())

    private val screen10 = ScreenAudit(
        isConsentScreen = true, preTicked = true, rejectOption = RejectOption.HIDDEN,
        source = AuditSource.DETERMINISTIC, observedFields = setOf("preTicked", "rejectOption"),
    )
    private val screen11 = screen10.copy(rejectOption = RejectOption.VISIBLE)
    private val shown = ConsentEvent((2.1 * S).toLong(), ConsentKind.SCREEN_APPEARED)
    private val base = AuditSession(id = "", targetPackage = "com.leakyshop.demo", targetLabel = "LeakyShop", plan = AuditPlan.SILENT)
    private val launch = listOf(
        net(0.4, "api.leakyshop.example"), net(0.6, "app-measurement.com"), net(0.7, "graph.facebook.com"),
        net(0.9, "firebase-settings.crashlytics.com"), net(1.3, "launches.appsflyersdk.com"),
    )

    private val v10 = listOf(
        base.copy(id = "silent", plan = AuditPlan.SILENT, endedAtNanos = 30 * S, consentEvents = listOf(shown), screenAudit = screen10, netEvents = launch),
        base.copy(
            id = "reject", plan = AuditPlan.REJECT, endedAtNanos = 35 * S, screenAudit = screen10,
            consentEvents = listOf(shown, ConsentEvent(6 * S, ConsentKind.SETTINGS, "Manage"), ConsentEvent((8.3 * S).toLong(), ConsentKind.REJECT, "Reject all")),
            // 1.0: refusing still wakes the SDKs.
            netEvents = launch + listOf(net(9.4, "graph.facebook.com"), net(11.2, "app-measurement.com"), net(12.0, "launches.appsflyersdk.com")),
        ),
        base.copy(
            id = "accept", plan = AuditPlan.ACCEPT, endedAtNanos = 30 * S, screenAudit = screen10,
            consentEvents = listOf(shown, ConsentEvent((4.9 * S).toLong(), ConsentKind.ACCEPT, "Accept & Continue")),
            netEvents = launch + listOf(net(5.6, "googleads.g.doubleclick.net"), net(6.1, "in1.clevertap-prod.com")),
        ),
    )

    private val v11 = v10.map { s ->
        when (s.plan) {
            // 1.1: Reject sits on the first screen, and refusing contacts no tracker.
            AuditPlan.REJECT -> s.copy(
                screenAudit = screen11, netEvents = launch,
                consentEvents = listOf(shown, ConsentEvent((4.0 * S).toLong(), ConsentKind.REJECT, "Reject")),
            )
            else -> s.copy(screenAudit = screen11)
        }
    }

    @Test
    fun `leaky 1_0 shape keeps the recorded 21 with C1 C2 C3 C4 C10`() {
        assertEquals(
            Triple(21, ScoreBand.AT_RISK, setOf(CheckId.C1, CheckId.C2, CheckId.C3, CheckId.C4, CheckId.C10)),
            summary(engine.evaluate(v10)),
        )
    }

    @Test
    fun `leaky 1_1 shape fixes C2 and C4, keeps C1 C3 C10 and scores 66`() {
        val r = engine.evaluate(v11)
        val (score, band, checks) = summary(r)
        assertEquals(setOf(CheckId.C1, CheckId.C3, CheckId.C10), checks)
        assertTrue("score $score should rise above 21 but stay below 100", score != null && score > 21 && score < 100)
        assertEquals(LOCKED_V11_SCORE, score)
        assertEquals(ScoreBand.NEEDS_WORK, band)
    }

    private companion object {
        /** Recorded from the engine for this shape; a change here means the scoring moved. */
        const val LOCKED_V11_SCORE = 66
    }
}
