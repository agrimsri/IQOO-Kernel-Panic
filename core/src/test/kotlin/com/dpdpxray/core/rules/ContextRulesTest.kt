package com.dpdpxray.core.rules

import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.Category
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.PenaltyBand
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.Severity
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G1 context rules: who publishes the audited app, content delivery, and whether the run started from a fresh reset.
 * Test data shaped like real runs, not device replays.
 */
class ContextRulesTest {
    private val s = 1_000_000_000L
    private val catalog = TrackerCatalog.loadDefault()
    private val engine = RulesEngine(catalog)

    private val notCleared = "App data was not cleared (reset not approved or Shizuku not authorised) — results reflect the app's current state."

    /** The lookups that made a real Instagram Quick check score 70/100 (C1 + NC). */
    private val instagramLookups = listOf(
        NetEvent(tNanos = 1 * s, hostname = "graph.facebook.com"),
        NetEvent(tNanos = 2 * s, hostname = "i.instagram.com"),
        NetEvent(
            tNanos = 3 * s, hostname = "scontent.cdninstagram.com",
            cnameChain = listOf("scontent.cdninstagram.com", "scontent.xx.fbcdn.net"),
        ),
        NetEvent(tNanos = 4 * s, hostname = "static.xx.fbcdn.net"),
    )

    private fun session(
        pkg: String,
        events: List<NetEvent>,
        plan: AuditPlan = AuditPlan.SILENT,
        freshState: Boolean? = null,
        consent: List<ConsentEvent> = emptyList(),
        notes: List<String> = emptyList(),
        childFacing: Boolean = false,
    ) = AuditSession(
        id = "${plan.name}-$pkg", targetPackage = pkg, targetLabel = pkg, plan = plan, childFacing = childFacing,
        startedAtNanos = 0, endedAtNanos = 30 * s, netEvents = events, consentEvents = consent,
        privateDnsActive = false, notes = notes, freshState = freshState,
    )

    private fun ids(r: AuditResult) = r.findings.map { it.checkId }.toSet()
    private fun hosts(vararg h: String) = h.mapIndexed { i, host -> NetEvent(tNanos = (i + 1) * s, hostname = host) }

    private val acceptAt5 = listOf(ConsentEvent(2 * s, ConsentKind.SCREEN_APPEARED), ConsentEvent(5 * s, ConsentKind.ACCEPT))
    private val rejectAt5 = listOf(ConsentEvent(2 * s, ConsentKind.SCREEN_APPEARED), ConsentEvent(5 * s, ConsentKind.REJECT))

    // --- Instagram-like kept-data run -------------------------------------------------------------------------------

    @Test
    fun `instagram kept-data run without a consent screen is not penalised`() {
        val r = engine.evaluate(listOf(session("com.instagram.android", instagramLookups, freshState = false)))
        val found = ids(r)
        assertFalse("no C1: $found", CheckId.C1 in found)
        assertFalse("no NC: $found", CheckId.NC in found)
        assertFalse("no C10: $found", CheckId.C10 in found)
        assertEquals(1, r.findings.count { it.checkId == CheckId.U1 })
        val u1 = r.findings.single { it.checkId == CheckId.U1 }
        assertEquals(Severity.INFO, u1.severity)
        assertEquals(PenaltyBand.NONE, u1.penaltyBand)
        assertTrue(u1.dpdpRefs.isEmpty())
        assertTrue(u1.explanation.contains("kept its data"))
        assertTrue(u1.explanation.contains("sign-up"))
        assertEquals(100, r.score)
        assertTrue(r.findings.all { it.severity == Severity.INFO })
    }

    @Test
    fun `instagram contacting meta servers is labelled same company and stays visible`() {
        val r = engine.evaluate(listOf(session("com.instagram.android", instagramLookups, freshState = false)))
        val s1 = r.findings.single { it.checkId == CheckId.S1 }
        assertEquals(Severity.INFO, s1.severity)
        assertEquals(PenaltyBand.NONE, s1.penaltyBand)
        assertTrue(s1.evidence.any { it.startsWith("graph.facebook.com") && it.contains("Same company as the app (Meta)") })
        assertTrue("CDN hosts are not data collection: ${s1.evidence}", s1.evidence.none { it.contains("fbcdn") || it.contains("cdninstagram") })
        assertTrue(s1.explanation.contains("first-party"))
        assertTrue(s1.entities.contains("Meta (Facebook SDK)"))
    }

    @Test
    fun `instagram fresh-reset run still does not call meta servers third-party trackers`() {
        val r = engine.evaluate(listOf(session("com.instagram.android", instagramLookups, freshState = true)))
        val found = ids(r)
        assertFalse(CheckId.C1 in found)
        assertFalse("no third-party tracker, so no NC: $found", CheckId.NC in found)
        assertFalse(CheckId.C10 in found)
        assertFalse("fresh run: consent history is not unknown", CheckId.U1 in found)
        assertTrue(CheckId.S1 in found)
        assertEquals(100, r.score)
    }

    @Test
    fun `a third-party tracker inside a meta app is still flagged on a fresh run`() {
        val events = instagramLookups + NetEvent(tNanos = 5 * s, hostname = "launches.appsflyersdk.com")
        val r = engine.evaluate(listOf(session("com.instagram.android", events, freshState = true)))
        val c1 = r.findings.single { it.checkId == CheckId.C1 }
        assertEquals(listOf("AppsFlyer"), c1.entities)
        assertTrue(c1.evidence.none { it.startsWith("graph.facebook.com") })
        assertTrue(CheckId.NC in ids(r))
        assertEquals(listOf("AppsFlyer"), r.findings.single { it.checkId == CheckId.C10 }.entities)
    }

    // --- Same lookups from a non-Meta app ---------------------------------------------------------------------------

    @Test
    fun `same lookups from a non-meta app on a fresh reset still give C1 and NC`() {
        val r = engine.evaluate(listOf(session("com.example.shop", instagramLookups, freshState = true)))
        val c1 = r.findings.single { it.checkId == CheckId.C1 }
        assertEquals(Severity.HIGH, c1.severity)
        assertEquals(listOf("Meta (Facebook SDK)"), c1.entities)
        assertTrue(CheckId.NC in ids(r))
        assertTrue(CheckId.C10 in ids(r))
        assertFalse(CheckId.S1 in ids(r))
        assertFalse(CheckId.U1 in ids(r))
    }

    @Test
    fun `facebook sdk inside a non-meta app before accept gives C1 as before`() {
        val r = engine.evaluate(listOf(session("com.example.shop", hosts("graph.facebook.com", "api.shop.example"), AuditPlan.ACCEPT, freshState = true, consent = acceptAt5)))
        val c1 = r.findings.single { it.checkId == CheckId.C1 }
        assertTrue(c1.evidence.single().contains("before the consent choice"))
    }

    @Test
    fun `same company is excluded from C2 and C9 but third parties are not`() {
        val metaReject = session("com.instagram.android", listOf(NetEvent(8 * s, "graph.facebook.com"), NetEvent(9 * s, "i.instagram.com")), AuditPlan.REJECT, freshState = true, consent = rejectAt5, childFacing = true)
        val meta = engine.evaluate(listOf(metaReject))
        assertFalse(CheckId.C2 in ids(meta))
        assertFalse(CheckId.C9 in ids(meta))

        val other = engine.evaluate(listOf(metaReject.copy(targetPackage = "com.example.shop")))
        assertTrue(CheckId.C2 in ids(other))
        assertTrue(CheckId.C9 in ids(other))
    }

    @Test
    fun `google app contacting google ads is same company, meta sdk is not`() {
        val r = engine.evaluate(listOf(session("com.google.android.youtube", hosts("googleads.g.doubleclick.net", "graph.facebook.com", "youtubei.googleapis.com"), freshState = true)))
        val c1 = r.findings.single { it.checkId == CheckId.C1 }
        assertEquals(listOf("Meta (Facebook SDK)"), c1.entities)
        assertTrue(r.findings.single { it.checkId == CheckId.S1 }.evidence.single().contains("Same company as the app (Google)"))
    }

    @Test
    fun `an unknown publisher has no same-company context`() {
        val r = engine.evaluate(listOf(session("com.instagram.android.fake", instagramLookups, freshState = true)))
        assertTrue(CheckId.C1 in ids(r))
        assertFalse(CheckId.S1 in ids(r))
    }

    @Test
    fun `stored entity names from older audits still get same-company context`() {
        val legacyEvent = NetEvent(tNanos = 1 * s, hostname = "graph.facebook.com", entity = "Facebook", category = Category.SOCIAL)
        val r = engine.evaluate(listOf(session("com.facebook.katana", listOf(legacyEvent, NetEvent(2 * s, "edge-mqtt.facebook.com")), freshState = true)))
        assertFalse(CheckId.C1 in ids(r))
        assertTrue(CheckId.S1 in ids(r))
    }

    // --- Kept-data runs ---------------------------------------------------------------------------------------------

    @Test
    fun `kept-data run lists third-party trackers under U1 instead of C1 and NC`() {
        val r = engine.evaluate(listOf(session("com.example.shop", hosts("app-measurement.com", "launches.appsflyersdk.com", "api.shop.example"), freshState = false)))
        val found = ids(r)
        assertFalse(CheckId.C1 in found)
        assertFalse(CheckId.NC in found)
        assertFalse(CheckId.C10 in found)
        val u1 = r.findings.single { it.checkId == CheckId.U1 }
        assertEquals(setOf("Google Analytics for Firebase", "AppsFlyer"), u1.entities.toSet())
        assertTrue(u1.evidence.any { it.startsWith("app-measurement.com") && it.contains("consent history unknown") })
        assertTrue(u1.evidence.any { it.startsWith("launches.appsflyersdk.com") })
        assertTrue(u1.evidence.none { it.contains("no consent was ever requested") })
        assertTrue(u1.evidence.first().contains("kept its data"))
        assertEquals(EvidenceType.OBSERVED, u1.evidenceType)
        // Trackers were looked up but whether consent came first is unknown: no score, never "Ready".
        assertEquals(null, r.score)
        assertEquals(ScoreBand.INCONCLUSIVE, r.band)
        assertEquals(Inconclusive.Reason.CONSENT_HISTORY_UNKNOWN, Inconclusive.reason(r.findings))
    }

    @Test
    fun `kept-data run without third-party tracker lookups keeps its score`() {
        val r = engine.evaluate(listOf(session("com.example.shop", hosts("api.shop.example", "cdn.shop.example"), freshState = false)))
        assertTrue(CheckId.U1 in ids(r))
        assertEquals(null, Inconclusive.reason(r.findings))
        assertEquals(100, r.score)
    }

    @Test
    fun `kept-data run where a consent screen appeared keeps C1`() {
        val r = engine.evaluate(listOf(session("com.example.shop", hosts("app-measurement.com", "api.shop.example"), AuditPlan.ACCEPT, freshState = false, consent = acceptAt5)))
        assertTrue(CheckId.C1 in ids(r))
        assertFalse(CheckId.U1 in ids(r))
    }

    @Test
    fun `kept-data trackers in a child-facing app still give C9`() {
        val r = engine.evaluate(listOf(session("com.example.kids", hosts("app-measurement.com", "api.kids.example"), freshState = false, childFacing = true)))
        assertTrue(CheckId.C9 in ids(r))
        assertTrue(CheckId.U1 in ids(r))
        assertFalse(CheckId.C1 in ids(r))
    }

    @Test
    fun `NC counts only the fresh plans when another plan kept its data`() {
        val fresh = session("com.example.shop", hosts("app-measurement.com", "api.shop.example"), AuditPlan.SILENT, freshState = true)
        val kept = session("com.example.shop", hosts("launches.appsflyersdk.com", "api.shop.example"), AuditPlan.ACCEPT, freshState = false)
        val r = engine.evaluate(listOf(fresh, kept))
        val nc = r.findings.single { it.checkId == CheckId.NC }
        assertEquals(listOf(AuditPlan.SILENT), nc.plans)
        val c1 = r.findings.single { it.checkId == CheckId.C1 }
        assertEquals(listOf("Google Analytics for Firebase"), c1.entities)
        assertEquals(listOf(AuditPlan.ACCEPT), r.findings.single { it.checkId == CheckId.U1 }.plans)
    }

    @Test
    fun `fresh-reset runs keep today's behaviour exactly`() {
        val sessions = listOf(session("com.example.shop", hosts("app-measurement.com", "launches.appsflyersdk.com", "api.shop.example")))
        val legacy = engine.evaluate(sessions)
        val fresh = engine.evaluate(sessions.map { it.copy(freshState = true) })
        assertEquals(legacy, fresh)
        assertEquals(setOf(CheckId.C1, CheckId.NC, CheckId.C10), ids(fresh))
    }

    // --- Legacy audits (freshState not recorded) --------------------------------------------------------------------

    @Test
    fun `legacy session without the note behaves as before`() {
        val r = engine.evaluate(listOf(session("com.example.shop", hosts("app-measurement.com", "api.shop.example"), freshState = null)))
        assertTrue(CheckId.C1 in ids(r))
        assertTrue(CheckId.NC in ids(r))
        assertFalse(CheckId.U1 in ids(r))
    }

    @Test
    fun `legacy session with the not-cleared note is treated as kept data`() {
        val r = engine.evaluate(listOf(session("com.example.shop", hosts("app-measurement.com", "api.shop.example"), freshState = null, notes = listOf(notCleared))))
        assertFalse(CheckId.C1 in ids(r))
        assertFalse(CheckId.NC in ids(r))
        assertTrue(CheckId.U1 in ids(r))
    }

    @Test
    fun `legacy session whose reset failed is treated as kept data`() {
        val failed = session("com.example.shop", hosts("app-measurement.com", "api.shop.example"), freshState = null, notes = listOf("Could not clear app data: exit 1: refused"))
        assertTrue(failed.appDataKept)
        assertTrue(CheckId.U1 in ids(engine.evaluate(listOf(failed))))
    }

    @Test
    fun `an explicit fresh state wins over note text`() {
        val s0 = session("com.example.shop", hosts("app-measurement.com", "api.shop.example"), freshState = true, notes = listOf(notCleared))
        assertFalse(s0.appDataKept)
        assertTrue(s0.copy(freshState = false, notes = emptyList()).appDataKept)
        assertFalse(s0.copy(freshState = null, notes = emptyList()).appDataKept)
    }

    @Test
    fun `classifier exposes the same labels for screens`() {
        val classifier = SessionClassifier(catalog)
        val events = classifier.classify(session("com.instagram.android", instagramLookups, freshState = false))
        val byHost = events.associateBy { it.hostname }
        assertEquals(Category.FIRST_PARTY_SAME_COMPANY, byHost.getValue("graph.facebook.com").category)
        assertEquals("Meta (Facebook SDK)", byHost.getValue("graph.facebook.com").entity)
        assertEquals(Category.CONTENT_DELIVERY, byHost.getValue("static.xx.fbcdn.net").category)
        // An alias to a content CDN is ordinary infrastructure, not CNAME cloaking: the queried name stays unknown.
        assertEquals(Category.UNKNOWN, byHost.getValue("scontent.cdninstagram.com").category)
        assertNull(byHost.getValue("scontent.cdninstagram.com").disguisedVia)
        assertEquals(Category.UNKNOWN, byHost.getValue("i.instagram.com").category)
        assertEquals("Meta", classifier.ownerOf(session("com.instagram.android", emptyList()))?.company)
        assertNull(classifier.ownerOf(session("com.example.shop", emptyList())))
        assertNotNull(SessionClassifier.sameCompanyLabel("Meta"))
        assertEquals("Same company as the app (Meta)", SessionClassifier.sameCompanyLabel("Meta"))
    }
}
