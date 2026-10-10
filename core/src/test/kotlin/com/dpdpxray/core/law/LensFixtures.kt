package com.dpdpxray.core.law

import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.AuditSource
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.RejectOption
import com.dpdpxray.core.model.ScreenAudit

/** Test-only copies of the controlled LeakyShop shapes (see DemoScoreRegressionTest), not device observations. */
internal object LensFixtures {
    private const val S = 1_000_000_000L
    private fun net(t: Double, host: String) = NetEvent(tNanos = (t * S).toLong(), hostname = host)

    private val fullScreen = ScreenAudit(
        isConsentScreen = true, preTicked = true, rejectOption = RejectOption.HIDDEN, purposesCount = 3,
        separateChoices = false, itemisedData = false, purposeStated = true, withdrawalInfo = false,
        languageOptions = listOf("English"), childAudienceSignals = false, confidence = 0.86,
        source = AuditSource.MERGED, observedFields = setOf("preTicked", "rejectOption"),
    )
    private val deviceScreen = ScreenAudit(
        isConsentScreen = true, preTicked = true, rejectOption = RejectOption.HIDDEN,
        source = AuditSource.DETERMINISTIC, observedFields = setOf("preTicked", "rejectOption"),
    )
    private val shown = ConsentEvent((2.1 * S).toLong(), ConsentKind.SCREEN_APPEARED)
    private val base = AuditSession(id = "", targetPackage = "com.leakyshop.demo", targetLabel = "LeakyShop (sample)", plan = AuditPlan.SILENT)

    /** Three plans; with [fullScreen] it raises C1-C8 and C10 (score 8). */
    val leaky: List<AuditSession> = listOf(
        base.copy(
            id = "sample-c", plan = AuditPlan.SILENT, endedAtNanos = 30 * S, consentEvents = listOf(shown), screenAudit = fullScreen,
            netEvents = listOf(
                net(0.4, "api.leakyshop.example"), net(0.6, "app-measurement.com"), net(0.7, "graph.facebook.com"),
                net(0.9, "firebase-settings.crashlytics.com"), net(1.3, "launches.appsflyersdk.com"), net(6.0, "app-measurement.com"),
            ),
        ),
        base.copy(
            id = "sample-b", plan = AuditPlan.REJECT, endedAtNanos = 35 * S, screenAudit = fullScreen,
            consentEvents = listOf(shown, ConsentEvent(6 * S, ConsentKind.SETTINGS, "Manage"), ConsentEvent((8.3 * S).toLong(), ConsentKind.REJECT, "Reject all")),
            netEvents = listOf(
                net(0.4, "api.leakyshop.example"), net(0.6, "app-measurement.com"), net(0.8, "graph.facebook.com"),
                net(9.4, "graph.facebook.com"), net(11.2, "app-measurement.com"), net(12.0, "launches.appsflyersdk.com"),
            ),
        ),
        base.copy(
            id = "sample-a", plan = AuditPlan.ACCEPT, endedAtNanos = 30 * S, screenAudit = fullScreen,
            consentEvents = listOf(shown, ConsentEvent((4.9 * S).toLong(), ConsentKind.ACCEPT, "Accept & Continue")),
            netEvents = listOf(
                net(0.4, "api.leakyshop.example"), net(0.6, "app-measurement.com"), net(0.7, "graph.facebook.com"),
                net(5.2, "cdn.leakyshop.example"), net(5.6, "googleads.g.doubleclick.net"), net(6.1, "in1.clevertap-prod.com"),
            ),
        ),
    )

    /** The recorded device shape: C1, C2, C3, C4, C10, score 21 under the default standard. */
    val deviceLeaky: List<AuditSession> = leaky.map { it.copy(screenAudit = deviceScreen) }

    /** The fixed counterpart: no findings, score 100. */
    val deviceFixed: List<AuditSession> = deviceLeaky.map { s ->
        s.copy(
            targetPackage = "com.leakyshop.demo.fixed",
            screenAudit = deviceScreen.copy(preTicked = false, rejectOption = RejectOption.VISIBLE),
            netEvents = listOf(net(0.4, "api.leakyshop.example"), net(9.0, "api.leakyshop.example")),
        )
    }

    /** Child-facing: adds C9. */
    val childFacing: List<AuditSession> = leaky.map { it.copy(childFacing = true) }

    /** No consent screen in any plan, fresh state: raises NC. */
    val noScreen: List<AuditSession> = deviceLeaky.map { it.copy(consentEvents = emptyList(), screenAudit = null, freshState = true) }
}
