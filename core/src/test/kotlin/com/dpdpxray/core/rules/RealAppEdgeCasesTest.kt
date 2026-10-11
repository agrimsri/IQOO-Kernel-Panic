package com.dpdpxray.core.rules

import com.dpdpxray.core.consent.BlockerKind
import com.dpdpxray.core.consent.BlockerLexicon
import com.dpdpxray.core.consent.ConsentLexicon
import com.dpdpxray.core.model.AppManifestFacts
import com.dpdpxray.core.model.AppTraffic
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.BlockedStep
import com.dpdpxray.core.model.CaptureHealth
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.PlanConditions
import com.dpdpxray.core.model.RejectOption
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.StartMode
import com.dpdpxray.core.model.UiNode
import com.dpdpxray.core.report.AuditJson
import com.dpdpxray.core.rules.VisibilityDiagnosis.Cause
import com.dpdpxray.core.screen.DeterministicScreenAuditor
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays of what real third-party apps do on a phone, as plain sessions: a consent screen that never shows, consent
 * only after sign-in, a consent screen in a WebView, long plans, Private DNS switched on midway, an app with no network
 * and a capture cut short. Each must end in a clear, truthful result: scored when the evidence allows it, and an
 * honest Inconclusive (with its cause) only when it does not.
 */
class RealAppEdgeCasesTest {
    private val s = 1_000_000_000L
    private val engine = RulesEngine(TrackerCatalog.loadDefault())
    private val restarted = PlanConditions(startMode = StartMode.RESTARTED, runningBeforeStart = true, privateDnsAtStart = false,
        privateDnsAtEnd = false, otherVpnAtEnd = false, networkAtStart = true, networkAtEnd = true)

    private fun session(
        hosts: List<String>,
        freshState: Boolean = false,
        conditions: PlanConditions? = restarted,
        notes: List<String> = listOf("No consent screen detected within 20 s."),
        health: CaptureHealth? = null,
        privateDns: Boolean = false,
        manifest: AppManifestFacts? = null,
        plan: AuditPlan = AuditPlan.REJECT,
    ) = AuditSession(
        id = "real-${plan.name}", targetPackage = "com.reskill.hacktracker", targetLabel = "HackTracker", plan = plan,
        startedAtNanos = 0, endedAtNanos = 45 * s,
        netEvents = hosts.mapIndexed { i, h -> NetEvent((i + 1) * s, h) },
        freshState = freshState, conditions = conditions, notes = notes, captureHealth = health, privateDnsActive = privateDns,
        manifestFacts = manifest,
    )

    private fun ids(sessions: List<AuditSession>) = engine.evaluate(sessions).findings.map { it.checkId }.toSet()

    // --- Consent screen not seen --------------------------------------------------------------------------------------

    @Test fun `no consent screen on a fresh start with enough lookups is scored with NC, not inconclusive`() {
        val r = engine.evaluate(listOf(session(listOf("app-measurement.com", "api.hacktracker.example", "cdn.hacktracker.example"), freshState = true)))
        assertNotNull(r.score)
        assertTrue(r.band != ScoreBand.INCONCLUSIVE)
        assertTrue(r.findings.any { it.checkId == CheckId.NC })
        assertNull(Inconclusive.reason(r.findings))
    }

    @Test fun `no consent screen after a restart that kept data, with only the app's own lookups, is scored`() {
        val r = engine.evaluate(listOf(session(listOf("api.hacktracker.example", "cdn.hacktracker.example", "img.hacktracker.example"))))
        assertNotNull("the 20 s no-consent note alone never makes a result inconclusive", r.score)
        assertTrue(CheckId.U1 in r.findings.map { it.checkId })
        assertFalse(CheckId.V0 in r.findings.map { it.checkId })
    }

    @Test fun `no consent screen after a restart that kept data, with third-party tracker lookups, stays an honest inconclusive`() {
        // A kept-data run cannot tell whether the user agreed earlier (for example at sign-up). Not a capture failure.
        val r = engine.evaluate(listOf(session(listOf("app-measurement.com", "api.hacktracker.example", "cdn.hacktracker.example"))))
        assertEquals(Inconclusive.Reason.CONSENT_HISTORY_UNKNOWN, Inconclusive.reason(r.findings))
        assertTrue(r.findings.single { it.checkId == CheckId.U1 }.entities.isNotEmpty())
        assertEquals("not a visibility problem, so no visibility diagnosis", emptyList<VisibilityDiagnosis.Item>(), VisibilityDiagnosis.forResult(r, listOf()))
    }

    @Test fun `consent only after sign-in, sign-in not completed - U1 with the blocked step, never NC and never low visibility`() {
        val blocked = session(
            listOf("api.hacktracker.example", "auth.hacktracker.example", "cdn.hacktracker.example"),
            conditions = restarted.copy(blocker = BlockedStep(BlockerKind.SIGN_IN.name, handedOff = true, waitedMs = 180_000, consentReached = false)),
            notes = listOf("Blocked by a sign-in screen: the app's consent screen was never reached, so this plan could not test consent."),
        )
        val found = ids(listOf(blocked))
        assertTrue(CheckId.U1 in found)
        assertFalse(CheckId.NC in found)
        assertFalse(CheckId.V0 in found)
    }

    // --- Consent screen in a WebView ----------------------------------------------------------------------------------

    /** A cookie-consent page rendered in a WebView, as Android's accessibility tree exposes it: nested Views, Buttons as leaves. */
    private val webViewConsent: UiNode = run {
        fun v(vararg kids: UiNode) = UiNode(className = "android.view.View", children = kids.toList())
        fun t(text: String) = UiNode(className = "android.widget.TextView", text = text)
        fun b(text: String) = UiNode(className = "android.widget.Button", text = text, isClickable = true)
        UiNode(className = "android.widget.FrameLayout", children = listOf(
            UiNode(className = "android.webkit.WebView", contentDescription = "HackTracker", children = listOf(
                v(v(v(v(v(
                    t("We value your privacy"),
                    t("We and our partners use cookies and process personal data to measure and improve the app."),
                    v(t("Privacy Policy")),
                    v(b("Reject all"), b("Customise"), b("Accept all")),
                ))))),
            )),
        ))
    }

    @Test fun `a consent screen inside a WebView is recognised, its buttons are classified and it is not a blocker`() {
        val labels = webViewConsent.flatten().map { it.label }
        assertTrue(ConsentLexicon.isConsentScreen(labels))
        assertEquals(ConsentKind.REJECT, ConsentLexicon.classifyButton("Reject all"))
        assertEquals(ConsentKind.ACCEPT, ConsentLexicon.classifyButton("Accept all"))
        assertNull(BlockerLexicon.detect(webViewConsent))
        val audit = DeterministicScreenAuditor.audit(webViewConsent)
        assertTrue(audit.isConsentScreen)
        assertEquals(RejectOption.VISIBLE, audit.rejectOption)
        assertTrue(ConsentLexicon.mentionsPrivacyPolicy(labels))
    }

    // --- Long plans, Private DNS midway, no network, capture cut short --------------------------------------------------

    @Test fun `a very long plan with a full event buffer reports hidden lookups (V1), never low visibility`() {
        val hosts = (1..5_000).map { "h$it.hacktracker.example" }
        val r = engine.evaluate(listOf(session(hosts, freshState = true, health = CaptureHealth(captureStartedNanos = 0,
            captureEndedNanos = 600 * s, dnsQueriesParsed = 5_300, eventsRecorded = 5_000, eventsDropped = 300, eventBufferCapacity = 5_000)))
            .map { it.copy(endedAtNanos = 600 * s) })
        val found = r.findings.map { it.checkId }
        assertTrue(CheckId.V1 in found)
        assertFalse(CheckId.V0 in found)
        assertNotNull(r.score)
    }

    @Test fun `Private DNS switched on midway shows in V1 and, with few lookups, as the named cause`() {
        val midway = session(listOf("api.hacktracker.example"), privateDns = true,
            conditions = restarted.copy(privateDnsAtStart = false, privateDnsAtEnd = true))
        val r = engine.evaluate(listOf(midway))
        assertTrue(r.findings.any { it.checkId == CheckId.V1 })
        val d = VisibilityDiagnosis.forResult(r, listOf(midway))
        assertEquals(Cause.PRIVATE_DNS, d.first().cause)
        assertTrue(d.first().evidence.single().contains("turned on during the plan"))
    }

    @Test fun `an app with no network gives no score and says why`() {
        val offline = session(emptyList(), conditions = restarted.copy(appTraffic = AppTraffic(openConnections = 0)),
            manifest = AppManifestFacts(requestsInternet = false))
        val r = engine.evaluate(listOf(offline))
        assertEquals(ScoreBand.INCONCLUSIVE, r.band)
        assertNull(r.score)
        assertEquals(Cause.NO_INTERNET_PERMISSION, VisibilityDiagnosis.forResult(r, listOf(offline)).first().cause)
    }

    @Test fun `a VPN revoked midway is named from the capture's own stop note`() {
        val cut = session(emptyList(), health = CaptureHealth(captureStartedNanos = 0, captureEndedNanos = 4 * s,
            notes = listOf("Capture stopped 4.0 s after it started: VPN was turned off by the system or the user.")))
        val d = VisibilityDiagnosis.forResult(engine.evaluate(listOf(cut)), listOf(cut))
        assertEquals(Cause.CAPTURE_STOPPED, d.first().cause)
    }

    // --- Saved files ---------------------------------------------------------------------------------------------------

    @Test fun `conditions survive a save, and a session without them is written exactly as before`() {
        val withConditions = session(listOf("api.hacktracker.example"), conditions = restarted.copy(
            appTraffic = AppTraffic(openConnections = 2), blocker = BlockedStep("FORM", agentTapped = listOf("Continue"), consentReached = true)))
        val back = AuditJson.decodeSessions(AuditJson.encodeSessions(listOf(withConditions))).single()
        assertEquals(withConditions.conditions, back.conditions)
        val old = AuditJson.encodeSessions(listOf(session(listOf("a.example"), conditions = null)))
        assertFalse(old.contains("conditions"))
        assertFalse(AuditJson.encodeSessions(listOf(session(listOf("a.example"), manifest = AppManifestFacts()))).contains("requestsInternet"))
    }
}
