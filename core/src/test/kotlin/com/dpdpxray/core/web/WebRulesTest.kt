package com.dpdpxray.core.web

import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.Severity
import com.dpdpxray.core.rules.Inconclusive
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.rules.ScoreCalculator
import com.dpdpxray.core.web.WebTestData.build
import com.dpdpxray.core.web.WebTestData.leakyReject
import com.dpdpxray.core.web.WebTestData.noBannerSilent
import com.dpdpxray.core.web.WebTestData.req
import com.dpdpxray.core.web.WebTestData.t
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRulesTest {
    private val engine = RulesEngine(WebTestData.catalog)

    @Test
    fun `a leaky site - trackers before consent and after Reject, a pre-ticked box, tracking cookies`() {
        val r = engine.evaluate(listOf(build(leakyReject())))
        val byId = r.findings.associateBy { it.checkId }
        assertEquals(setOf(CheckId.C1, CheckId.C2, CheckId.C3, CheckId.W1, CheckId.W2), byId.keys)

        val c1 = byId.getValue(CheckId.C1)
        assertEquals(Severity.HIGH, c1.severity)
        assertEquals(EvidenceType.OBSERVED, c1.evidenceType)
        assertEquals(listOf("Google Tag Manager", "Google Analytics", "Meta (Facebook SDK)"), c1.entities)
        assertEquals(WebRules.WEB_C1_TITLE, c1.title)
        assertTrue(c1.evidence.first(), c1.evidence.first().startsWith("GET script https://www.googletagmanager.com/gtag/js?id=G-TEST (Google Tag Manager"))
        assertTrue(c1.evidence.any { it.startsWith("POST beacon https://region1.google-analytics.com/g/collect") && it.contains("3.8 s before the consent choice") })
        assertTrue(c1.evidence.any { it.contains("by its documented path") })

        val c2 = byId.getValue(CheckId.C2)
        assertEquals(Severity.CRITICAL, c2.severity)
        assertEquals(listOf("Google Ads / AdMob"), c2.entities)
        assertTrue(c2.explanation.contains("TraceVeil tapped \"Reject All\""))
        assertTrue(c2.evidence.single().contains("1.0 s after the user chose reject"))

        // The app's own banner rule, with what the page showed added.
        val c3 = byId.getValue(CheckId.C3)
        assertEquals(EvidenceType.OBSERVED, c3.evidenceType)
        assertTrue(c3.evidence.contains("Ticked before any choice: \"Performance Cookies\" (in the banner's settings layer)"))

        val w1 = byId.getValue(CheckId.W1)
        assertEquals(Severity.INFO, w1.severity)
        assertEquals(listOf("Google Analytics", "Meta (Facebook SDK)"), w1.entities)
        assertTrue(w1.evidence.first().startsWith("_ga (first-party on news.example; named as the vendor documents for Google Analytics; value of 13 characters, not stored)"))
        val w2 = byId.getValue(CheckId.W2)
        assertEquals(listOf("Google Analytics", "Google Ads / AdMob"), w2.entities)
        assertTrue(w2.evidence.any { it.startsWith("_ga ") && it.contains("written again after 4.9 s") })
        assertTrue(w2.evidence.any { it.startsWith("IDE (third-party, kept for securepubads.g.doubleclick.net") })

        // Same score as an app audit with the same findings: 100 - 15 (C1) - 30 (C2) - 15 (C3); W1 and W2 are notes.
        assertEquals(40, r.score)
        assertEquals(ScoreBand.AT_RISK, r.band)
        assertEquals(ScoreCalculator.compute(r.findings), r.score to r.band)
    }

    @Test
    fun `website findings speak of requests, never of DNS lookups`() {
        val r = engine.evaluate(listOf(build(leakyReject())))
        for (f in r.findings) {
            val text = (listOf(f.title, f.explanation) + f.evidence).joinToString(" ")
            assertFalse("${f.checkId}: $text", text.contains("looked up") || text.contains("DNS"))
        }
    }

    @Test
    fun `no banner found while trackers are contacted gives NC with the limits of what was read`() {
        val r = engine.evaluate(listOf(build(noBannerSilent())))
        val nc = r.findings.single { it.checkId == CheckId.NC }
        assertEquals(WebRules.WEB_NC_TITLE, nc.title)
        assertTrue(nc.explanation.contains("inside a frame"))
        assertTrue(r.findings.any { it.checkId == CheckId.C1 && it.evidence.single().contains("no consent banner found in this plan") })
        assertEquals(70, r.score)
    }

    @Test
    fun `a banner inside another site's frame counts as a consent screen, so there is no NC`() {
        val frame = WebBanner(cmp = "Sourcepoint", detection = WebBannerDetection.FRAME_ONLY, frameHost = "cdn.privacy-mgmt.com")
        val c = noBannerSilent().copy(banner = frame, consentEvents = listOf(ConsentEvent(t(0.5), ConsentKind.SCREEN_APPEARED)))
        val r = engine.evaluate(listOf(build(c)))
        assertFalse(r.findings.any { it.checkId == CheckId.NC })
        assertTrue(r.findings.single { it.checkId == CheckId.C1 }.evidence.single().contains("the user had not chosen yet"))
        assertFalse(r.findings.any { it.checkId == CheckId.C3 || it.checkId == CheckId.C4 || it.checkId == CheckId.C15 })
    }

    @Test
    fun `a page that shows almost nothing is inconclusive, with the browser's error`() {
        val c = noBannerSilent().copy(requests = listOf(req(0.1, "https://down.example/", mainFrame = true)), errors = listOf("net::ERR_NAME_NOT_RESOLVED"))
        val r = engine.evaluate(listOf(build(c)))
        assertEquals(ScoreBand.INCONCLUSIVE, r.band)
        assertNull(r.score)
        assertEquals(Inconclusive.Reason.LOW_VISIBILITY, Inconclusive.reason(r.findings))
        assertTrue(r.findings.single { it.checkId == CheckId.V0 }.explanation.contains("net::ERR_NAME_NOT_RESOLVED"))
    }

    @Test
    fun `reject hidden behind settings - C4 with the taps it took, and the Reject plan recorded`() {
        val b = WebTestData.hiddenReject
        val c = leakyReject().copy(
            banner = b,
            consentEvents = listOf(
                WebTestData.shown(1.0, b),
                ConsentEvent(t(3.0), ConsentKind.SETTINGS, "Manage options"),
                ConsentEvent(t(5.0), ConsentKind.REJECT, "Reject all"),
            ),
        )
        val c4 = engine.evaluate(listOf(build(c))).findings.single { it.checkId == CheckId.C4 }
        assertEquals(EvidenceType.OBSERVED, c4.evidenceType)
        assertTrue(c4.evidence.contains("Options on the banner's first screen: \"Accept & continue\", \"Manage options\"."))
        assertTrue(c4.evidence.contains("Reject was reached only through \"Manage options\" → \"Reject all\"."))
    }

    @Test
    fun `a Reject plan that could not refuse says so under C4`() {
        val b = WebTestData.hiddenReject
        val c = leakyReject().copy(
            banner = b, consentEvents = listOf(WebTestData.shown(1.0, b)),
            notes = listOf("No Reject option was found, even after opening the banner's settings."),
        )
        val c4 = engine.evaluate(listOf(build(c))).findings.single { it.checkId == CheckId.C4 }
        assertTrue(c4.evidence.joinToString(), c4.evidence.any { it.startsWith("The Reject plan could not refuse: No Reject option was found") })
    }

    @Test
    fun `a banner that names no policy gives the informational C15`() {
        val b = WebTestData.hiddenReject
        val r = engine.evaluate(listOf(build(leakyReject().copy(banner = b, consentEvents = listOf(WebTestData.shown(1.0, b))))))
        val c15 = r.findings.single { it.checkId == CheckId.C15 }
        assertEquals(Severity.INFO, c15.severity)
        assertTrue(c15.explanation.contains("banner"))
        assertFalse(engine.evaluate(listOf(build(leakyReject()))).findings.any { it.checkId == CheckId.C15 })
    }

    @Test
    fun `cookies set in the moment around the tap are not claimed as set after it`() {
        val c = leakyReject()
        // The last reading without IDE started 3 s before the Reject tap: it may have been set before the tap.
        val early = c.copy(cookieSnapshots = c.cookieSnapshots.map { s ->
            if (s.at == WebSnapshot.BEFORE_CHOICE) s.copy(startNanos = t(2.0), endNanos = t(2.1)) else s
        })
        // Neither IDE (missing only from that early reading) nor _ga (last read with its old value then) is claimed.
        assertFalse(engine.evaluate(listOf(build(early))).findings.any { it.checkId == CheckId.W2 })
    }

    @Test
    fun `three plans - each finding names the plans it came from`() {
        val reject = build(leakyReject())
        val accept = build(leakyReject().copy(id = "web-1-ACCEPT", plan = AuditPlan.ACCEPT,
            consentEvents = listOf(WebTestData.shown(1.0, WebTestData.oneTrust), ConsentEvent(t(5.0), ConsentKind.ACCEPT, "Accept All Cookies"))))
        val silent = build(leakyReject().copy(id = "web-1-SILENT", plan = AuditPlan.SILENT, consentEvents = listOf(WebTestData.shown(1.0, WebTestData.oneTrust))))
        val r = engine.evaluate(listOf(silent, reject, accept))
        assertEquals(listOf(AuditPlan.SILENT, AuditPlan.REJECT, AuditPlan.ACCEPT), r.findings.single { it.checkId == CheckId.C1 }.plans)
        assertEquals(listOf(AuditPlan.REJECT), r.findings.single { it.checkId == CheckId.C2 }.plans)
        assertEquals(listOf(AuditPlan.REJECT), r.findings.single { it.checkId == CheckId.W2 }.plans)
    }

    @Test
    fun `app audits are untouched by the website rules`() {
        val app = build(leakyReject()).copy(web = null, targetPackage = "com.shop.app")
        val r = engine.evaluate(listOf(app))
        assertFalse(r.findings.any { it.checkId == CheckId.W1 || it.checkId == CheckId.W2 })
        assertTrue(r.findings.single { it.checkId == CheckId.C1 }.title == CheckId.C1.title)
    }
}
