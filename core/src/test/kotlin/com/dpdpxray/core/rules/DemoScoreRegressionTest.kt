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
import org.junit.Test

/**
 * Locks the score arithmetic of the controlled demo shapes so evidence-engine changes cannot move them. The expected
 * values were recorded from the engine before alias-aware classification (A3). These are test-only copies of the
 * three-plan sample in app/data/SampleAudit.kt and a fixed counterpart, not device observations.
 */
class DemoScoreRegressionTest {
    private val S = 1_000_000_000L
    private val engine = RulesEngine(TrackerCatalog.loadDefault())

    private fun net(t: Double, host: String) = NetEvent(tNanos = (t * S).toLong(), hostname = host)

    private val leakyScreen = ScreenAudit(
        isConsentScreen = true, preTicked = true, rejectOption = RejectOption.HIDDEN, purposesCount = 3,
        separateChoices = false, itemisedData = false, purposeStated = true, withdrawalInfo = false,
        languageOptions = listOf("English"), childAudienceSignals = false, confidence = 0.86,
        source = AuditSource.MERGED, observedFields = setOf("preTicked", "rejectOption"),
    )
    private val fixedScreen = leakyScreen.copy(
        preTicked = false, rejectOption = RejectOption.VISIBLE, purposesCount = 1, separateChoices = true,
        itemisedData = true, withdrawalInfo = true, languageOptions = listOf("English", "Hindi"),
    )
    private val shown = ConsentEvent((2.1 * S).toLong(), ConsentKind.SCREEN_APPEARED)
    private val base = AuditSession(id = "", targetPackage = "com.leakyshop.demo", targetLabel = "LeakyShop (sample)", plan = AuditPlan.SILENT)

    private val leaky = listOf(
        base.copy(
            id = "sample-c", plan = AuditPlan.SILENT, endedAtNanos = 30 * S, consentEvents = listOf(shown), screenAudit = leakyScreen,
            netEvents = listOf(
                net(0.4, "api.leakyshop.example"), net(0.6, "app-measurement.com"), net(0.7, "graph.facebook.com"),
                net(0.9, "firebase-settings.crashlytics.com"), net(1.3, "launches.appsflyersdk.com"), net(6.0, "app-measurement.com"),
            ),
        ),
        base.copy(
            id = "sample-b", plan = AuditPlan.REJECT, endedAtNanos = 35 * S, screenAudit = leakyScreen,
            consentEvents = listOf(shown, ConsentEvent(6 * S, ConsentKind.SETTINGS, "Manage"), ConsentEvent((8.3 * S).toLong(), ConsentKind.REJECT, "Reject all")),
            netEvents = listOf(
                net(0.4, "api.leakyshop.example"), net(0.6, "app-measurement.com"), net(0.8, "graph.facebook.com"),
                net(9.4, "graph.facebook.com"), net(11.2, "app-measurement.com"), net(12.0, "launches.appsflyersdk.com"),
            ),
        ),
        base.copy(
            id = "sample-a", plan = AuditPlan.ACCEPT, endedAtNanos = 30 * S, screenAudit = leakyScreen,
            consentEvents = listOf(shown, ConsentEvent((4.9 * S).toLong(), ConsentKind.ACCEPT, "Accept & Continue")),
            netEvents = listOf(
                net(0.4, "api.leakyshop.example"), net(0.6, "app-measurement.com"), net(0.7, "graph.facebook.com"),
                net(5.2, "cdn.leakyshop.example"), net(5.6, "googleads.g.doubleclick.net"), net(6.1, "in1.clevertap-prod.com"),
            ),
        ),
    )

    /** Same plans with first-party traffic only before any choice and a compliant screen. */
    private val fixed = leaky.map { s ->
        s.copy(
            targetPackage = "com.leakyshop.demo.fixed", screenAudit = fixedScreen,
            netEvents = listOf(net(0.4, "api.leakyshop.example"), net(5.2, "cdn.leakyshop.example"), net(9.0, "api.leakyshop.example")),
        )
    }

    private fun summary(r: AuditResult) = Triple(r.score, r.band, r.findings.map { it.checkId }.toSet())

    @Test
    fun `sample leaky audit keeps its score and findings`() {
        assertEquals(
            Triple(8, ScoreBand.AT_RISK, setOf(CheckId.C1, CheckId.C2, CheckId.C3, CheckId.C4, CheckId.C5, CheckId.C6, CheckId.C7, CheckId.C8, CheckId.C10)),
            summary(engine.evaluate(leaky)),
        )
    }

    /**
     * Shaped like the recorded iQOO full audits (audit-1791570081266: 21 / At risk with C2, C1, C3, C4, C10;
     * audit-1791570260617: 100 / DPDP-ready): only the tree-observed screen fields are known. Test data, not a replay.
     */
    private val deviceScreen = ScreenAudit(
        isConsentScreen = true, preTicked = true, rejectOption = RejectOption.HIDDEN,
        source = AuditSource.DETERMINISTIC, observedFields = setOf("preTicked", "rejectOption"),
    )
    private val deviceLeaky = leaky.map { it.copy(screenAudit = deviceScreen) }
    private val deviceFixed = deviceLeaky.map { s ->
        s.copy(
            targetPackage = "com.leakyshop.demo.fixed",
            screenAudit = deviceScreen.copy(preTicked = false, rejectOption = RejectOption.VISIBLE),
            netEvents = listOf(net(0.4, "api.leakyshop.example"), net(9.0, "api.leakyshop.example")),
        )
    }

    @Test
    fun `device-shaped leaky audit scores 21 with the recorded findings`() {
        assertEquals(
            Triple(21, ScoreBand.AT_RISK, setOf(CheckId.C1, CheckId.C2, CheckId.C3, CheckId.C4, CheckId.C10)),
            summary(engine.evaluate(deviceLeaky)),
        )
    }

    @Test
    fun `device-shaped fixed audit scores 100`() {
        assertEquals(Triple(100, ScoreBand.READY, emptySet<CheckId>()), summary(engine.evaluate(deviceFixed)))
    }

    @Test
    fun `fixed counterpart keeps 100 with no findings`() {
        assertEquals(Triple(100, ScoreBand.READY, emptySet<CheckId>()), summary(engine.evaluate(fixed)))
    }
}
