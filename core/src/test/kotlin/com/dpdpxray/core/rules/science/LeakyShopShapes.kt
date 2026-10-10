package com.dpdpxray.core.rules.science

import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.AuditSource
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.RejectOption
import com.dpdpxray.core.model.ScreenAudit
import com.dpdpxray.core.report.AuditJson

/**
 * The controlled LeakyShop demo shapes, as in DemoScoreRegressionTest (same sessions; that test locks 8, 21 and 100), and
 * the recorded Facebook Quick check replay. Test data, not new device observations.
 *
 * - [aiAssisted]: the three-plan sample with the AI screen reading (only preTicked and rejectOption confirmed from the
 *   accessibility tree; the other screen fields come from the AI review, so C5 to C8 are INFERRED). Scores 8.
 * - [rulesOnly]: the same plans with only the tree-observed screen fields known. Scores 21.
 * - [fixed]: the fixed build. Scores 100.
 */
object LeakyShopShapes {
    private const val S = 1_000_000_000L
    private fun net(t: Double, host: String) = NetEvent(tNanos = (t * S).toLong(), hostname = host)

    private val leakyScreen = ScreenAudit(
        isConsentScreen = true, preTicked = true, rejectOption = RejectOption.HIDDEN, purposesCount = 3,
        separateChoices = false, itemisedData = false, purposeStated = true, withdrawalInfo = false,
        languageOptions = listOf("English"), childAudienceSignals = false, confidence = 0.86,
        source = AuditSource.MERGED, observedFields = setOf("preTicked", "rejectOption"),
    )
    private val shown = ConsentEvent((2.1 * S).toLong(), ConsentKind.SCREEN_APPEARED)
    private val base = AuditSession(id = "", targetPackage = "com.leakyshop.demo", targetLabel = "LeakyShop (sample)", plan = AuditPlan.SILENT)

    val aiAssisted: List<AuditSession> = listOf(
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

    private val deviceScreen = ScreenAudit(
        isConsentScreen = true, preTicked = true, rejectOption = RejectOption.HIDDEN,
        source = AuditSource.DETERMINISTIC, observedFields = setOf("preTicked", "rejectOption"),
    )

    val rulesOnly: List<AuditSession> = aiAssisted.map { it.copy(screenAudit = deviceScreen) }

    val fixed: List<AuditSession> = rulesOnly.map { s ->
        s.copy(
            targetPackage = "com.leakyshop.demo.fixed",
            screenAudit = deviceScreen.copy(preTicked = false, rejectOption = RejectOption.VISIBLE),
            netEvents = listOf(net(0.4, "api.leakyshop.example"), net(9.0, "api.leakyshop.example")),
        )
    }

    /** The Facebook Quick check recorded on the test phone (audit-1791603680860), as used by CatalogueMatchTest. */
    val facebook: List<AuditSession> by lazy {
        AuditJson.decodeSessions(LeakyShopShapes::class.java.getResource("/replays/facebook-audit-1791603680860.sessions.json")!!.readText())
    }
}
