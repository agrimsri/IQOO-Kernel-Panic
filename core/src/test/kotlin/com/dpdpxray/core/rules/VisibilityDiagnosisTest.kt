package com.dpdpxray.core.rules

import com.dpdpxray.core.model.AppManifestFacts
import com.dpdpxray.core.model.AppTraffic
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.CaptureHealth
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.PlanConditions
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.StartMode
import com.dpdpxray.core.rules.VisibilityDiagnosis.Action
import com.dpdpxray.core.rules.VisibilityDiagnosis.Cause
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A bare "Inconclusive" becomes a diagnosis: why too few lookups were seen, from what the phone recorded around each
 * plan. The diagnosis never adds a score; it says what to fix and what one tap can try next.
 */
class VisibilityDiagnosisTest {
    private val s = 1_000_000_000L
    private val engine = RulesEngine(TrackerCatalog.loadDefault())

    private fun plan(
        conditions: PlanConditions? = PlanConditions(startMode = StartMode.RESTARTED, runningBeforeStart = true, privateDnsAtStart = false,
            privateDnsAtEnd = false, otherVpnAtEnd = false, networkAtStart = true, networkAtEnd = true, phoneLocked = false, appCrashed = false),
        lookups: Int = 0,
        seconds: Long = 40,
        plan: AuditPlan = AuditPlan.REJECT,
        health: CaptureHealth? = CaptureHealth(captureStartedNanos = 0, captureEndedNanos = seconds * s, dnsQueriesParsed = lookups.toLong(),
            malformedPackets = 0, hiddenDnsPackets = 0, nonDnsPackets = 0, dnsResponsesRelayed = lookups.toLong(), dnsRelayFailures = 0),
        privateDns: Boolean? = false,
        freshState: Boolean? = false,
        manifest: AppManifestFacts? = null,
        notes: List<String> = emptyList(),
    ) = AuditSession(
        id = "a-${plan.name}", targetPackage = "com.reskill.hacktracker", targetLabel = "HackTracker", plan = plan,
        startedAtNanos = 0, endedAtNanos = seconds * s,
        netEvents = (1..lookups).map { NetEvent(it * s, "api$it.hacktracker.example") },
        captureHealth = health, privateDnsActive = privateDns, freshState = freshState, conditions = conditions,
        manifestFacts = manifest, notes = notes,
    )

    private fun causes(vararg sessions: AuditSession) = VisibilityDiagnosis.diagnose(sessions.toList()).map { it.cause }

    @Test fun `the live HackTracker case - kept data, no restart, already running - names the restart as the fix`() {
        // As captured on the phone before this change: no Shizuku restart, the app was already open.
        val live = plan(PlanConditions(startMode = StartMode.NOT_RESTARTED, restartProblem = "Shizuku is not available",
            runningBeforeStart = true, privateDnsAtStart = false, privateDnsAtEnd = false, otherVpnAtEnd = false,
            networkAtStart = true, networkAtEnd = true))
        val d = VisibilityDiagnosis.diagnose(listOf(live))
        assertEquals(Cause.ALREADY_RUNNING, d.first().cause)
        assertEquals(Action.CHECK_AGAIN_WITH_RESTART, VisibilityDiagnosis.primaryAction(d))
        assertTrue(d.first().sentence, d.first().sentence.contains("already running"))
        assertTrue(d.first().evidence.single(), d.first().evidence.single().contains("Shizuku is not available"))
    }

    @Test fun `an older audit with no recorded conditions and kept data still points at a restart, worded as a possibility`() {
        val old = plan(conditions = null, notes = listOf("App data was not cleared (reset not approved or Shizuku not authorised) — results reflect the app's current state."), freshState = null)
        val d = VisibilityDiagnosis.diagnose(listOf(old)).first()
        assertEquals(Cause.ALREADY_RUNNING, d.cause)
        assertTrue(d.sentence, d.sentence.contains("may"))
        assertEquals(Action.CHECK_AGAIN_WITH_RESTART, d.action)
    }

    @Test fun `a restarted app that was not running and had nothing else in the way is not blamed on the restart`() {
        val d = causes(plan(PlanConditions(startMode = StartMode.NOT_RESTARTED, runningBeforeStart = false, networkAtStart = true, networkAtEnd = true)))
        assertFalse(Cause.ALREADY_RUNNING in d)
    }

    @Test fun `Private DNS on, or turned on during the plan, or encrypted DNS packets, is named with the setting to change`() {
        assertEquals(Cause.PRIVATE_DNS, causes(plan(privateDns = true)).first())
        val midway = VisibilityDiagnosis.diagnose(listOf(plan(PlanConditions(startMode = StartMode.RESTARTED, privateDnsAtStart = false, privateDnsAtEnd = true)))).first()
        assertEquals(Cause.PRIVATE_DNS, midway.cause)
        assertTrue(midway.evidence.single(), midway.evidence.single().contains("turned on during"))
        assertEquals(Action.TURN_OFF_PRIVATE_DNS, midway.action)
        val hidden = plan(health = CaptureHealth(captureStartedNanos = 0, captureEndedNanos = 40 * s, malformedPackets = 7, hiddenDnsPackets = 7, nonDnsPackets = 0))
        assertEquals(Cause.PRIVATE_DNS, causes(hidden).first())
    }

    @Test fun `another VPN and a stopped capture come first, because no re-run works until they are fixed`() {
        val vpn = VisibilityDiagnosis.diagnose(listOf(plan(PlanConditions(startMode = StartMode.RESTARTED, otherVpnAtEnd = true, privateDnsAtEnd = true))))
        assertEquals(listOf(Cause.OTHER_VPN, Cause.PRIVATE_DNS), vpn.map { it.cause }.take(2))
        assertEquals(Action.TURN_OFF_OTHER_VPN, VisibilityDiagnosis.primaryAction(vpn))
        val stopped = plan(health = CaptureHealth(captureStartedNanos = 0, captureEndedNanos = 6 * s,
            notes = listOf("Capture stopped 6.0 s after it started: VPN was turned off by the system or the user.")))
        val d = VisibilityDiagnosis.diagnose(listOf(stopped)).first()
        assertEquals(Cause.CAPTURE_STOPPED, d.cause)
        assertTrue(d.evidence.single().contains("VPN was turned off"))
        assertEquals(Action.CHECK_AGAIN, d.action)
    }

    @Test fun `no working internet, read on the phone or seen as every lookup failing, is named`() {
        assertEquals(Cause.NO_NETWORK, causes(plan(PlanConditions(startMode = StartMode.RESTARTED, networkAtStart = true, networkAtEnd = false))).first())
        val failing = plan(lookups = 1, health = CaptureHealth(captureStartedNanos = 0, captureEndedNanos = 40 * s, dnsQueriesParsed = 1,
            dnsResponsesRelayed = 0, dnsRelayFailures = 1))
        val d = VisibilityDiagnosis.diagnose(listOf(failing)).first()
        assertEquals(Cause.NO_NETWORK, d.cause)
        assertEquals(Action.CONNECT_TO_INTERNET, d.action)
    }

    @Test fun `a fresh restart with network use but no lookups suggests the app's own DNS, without claiming it`() {
        val d = VisibilityDiagnosis.diagnose(listOf(plan(PlanConditions(startMode = StartMode.RESTARTED, appTraffic = AppTraffic(openConnections = 4))))).first()
        assertEquals(Cause.OWN_DNS, d.cause)
        assertTrue(d.sentence, d.sentence.contains("may use its own"))
        assertTrue(d.evidence.single(), d.evidence.single().contains("4 open connections"))
        assertEquals(Action.RUN_WITH_INTERNET_CUT_OFF, d.action)
    }

    @Test fun `a fresh restart with no network use reads as a quiet app, and unknown use stays unknown`() {
        val quiet = VisibilityDiagnosis.diagnose(listOf(plan(PlanConditions(startMode = StartMode.RESTARTED, appTraffic = AppTraffic(openConnections = 0))))).first()
        assertEquals(Cause.APP_QUIET, quiet.cause)
        assertEquals(Action.NONE, quiet.action)
        val unknown = VisibilityDiagnosis.diagnose(listOf(plan())).first()
        assertEquals(Cause.UNEXPLAINED, unknown.cause)
        assertTrue(unknown.sentence, unknown.sentence.contains("unknown"))
    }

    @Test fun `an app that does not ask for internet access is said so`() {
        val d = causes(plan(manifest = AppManifestFacts(requestsInternet = false)))
        assertTrue(Cause.NO_INTERNET_PERMISSION in d)
        assertFalse("the quiet-app line is not repeated", Cause.UNEXPLAINED in d)
    }

    @Test fun `a short watch, a locked phone and a crash are each named`() {
        assertTrue(Cause.WINDOW_TOO_SHORT in causes(plan(seconds = 9)))
        assertTrue(Cause.PHONE_LOCKED in causes(plan(PlanConditions(startMode = StartMode.RESTARTED, phoneLocked = true))))
        val crash = VisibilityDiagnosis.diagnose(listOf(plan(PlanConditions(startMode = StartMode.RESTARTED, appCrashed = true))))
        assertEquals(Cause.APP_CRASHED, crash.first().cause)
        assertEquals(Action.CHECK_AGAIN, crash.first().action)
    }

    @Test fun `a very long plan is never called too short`() {
        // 4 minutes behind a sign-in screen: the watch was long, so length is not the reason.
        assertFalse(Cause.WINDOW_TOO_SHORT in causes(plan(seconds = 240)))
    }

    @Test fun `every diagnosis has a cause, evidence and a sentence`() {
        val all = listOf(
            plan(), plan(privateDns = true), plan(PlanConditions(otherVpnAtEnd = true)), plan(seconds = 5),
            plan(PlanConditions(startMode = StartMode.NOT_RESTARTED)), plan(conditions = null),
        )
        for (p in all) {
            val d = VisibilityDiagnosis.diagnose(listOf(p))
            assertTrue(d.isNotEmpty())
            d.forEach { assertTrue(it.sentence.isNotBlank() && it.evidence.isNotEmpty()) }
        }
    }

    @Test fun `V0 carries the diagnosis in its explanation and evidence, and the result stays inconclusive`() {
        val r = engine.evaluate(listOf(plan(PlanConditions(startMode = StartMode.NOT_RESTARTED, runningBeforeStart = true, restartProblem = "Shizuku is not available"))))
        val v0 = r.findings.single { it.checkId == CheckId.V0 }
        assertEquals(ScoreBand.INCONCLUSIVE, r.band)
        assertEquals(null, r.score)
        assertTrue(v0.explanation, v0.explanation.contains("already running"))
        assertTrue(v0.evidence.toString(), v0.evidence.any { it.contains("Shizuku is not available") })
        assertFalse("a personal app is never told to clear its data", v0.explanation.contains("clear its data"))
    }

    @Test fun `every cause and action has a short phrase for the command-line tools`() {
        Cause.entries.forEach { assertTrue(it.name, VisibilityDiagnosis.phrase(it).isNotBlank()) }
        Action.entries.forEach { assertTrue(it.name, VisibilityDiagnosis.hint(it).isNotBlank()) }
        assertEquals("check again with a restart (the app keeps its data)", VisibilityDiagnosis.hint(Action.CHECK_AGAIN_WITH_RESTART))
    }

    @Test fun `enough lookups means no diagnosis at all`() {
        val r = engine.evaluate(listOf(plan(lookups = 6, freshState = true)))
        assertFalse(r.findings.any { it.checkId == CheckId.V0 })
        assertEquals(emptyList<VisibilityDiagnosis.Item>(), VisibilityDiagnosis.forResult(r, listOf(plan(lookups = 6))))
    }
}
