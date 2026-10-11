package com.dpdpxray.core.web

import com.dpdpxray.core.model.AnswerStatus
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.Category
import com.dpdpxray.core.model.Phase
import com.dpdpxray.core.rules.SessionClassifier
import com.dpdpxray.core.web.WebTestData.build
import com.dpdpxray.core.web.WebTestData.leakyReject
import com.dpdpxray.core.web.WebTestData.req
import com.dpdpxray.core.web.WebTestData.t
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSessionsTest {
    private val session = build(leakyReject())
    private val web = session.web!!

    @Test
    fun `a website plan becomes a session the rest of TraceVeil can read`() {
        assertEquals("web:news.example", session.targetPackage)
        assertEquals("https://news.example", session.targetLabel)
        assertEquals(AuditPlan.REJECT, session.plan)
        assertEquals(true, session.freshState)
        assertEquals(web.requests.size, session.netEvents.size)
        assertEquals(web.requests.map { it.host }, session.netEvents.map { it.hostname })
        assertEquals("news.example", web.pageHost)
        assertTrue(WebAudit.isWeb(listOf(session)))
    }

    @Test
    fun `requests keep their full https address, method and type`() {
        val gtag = web.requests.single { it.host == "www.googletagmanager.com" }
        assertEquals("https://www.googletagmanager.com/gtag/js?id=G-TEST", gtag.url)
        assertEquals(WebResourceType.SCRIPT, gtag.type)
        assertTrue(gtag.typeFromPage)
        assertTrue(gtag.thirdParty)
        val collect = web.requests.single { it.host == "region1.google-analytics.com" }
        assertEquals("POST", collect.method)
        assertEquals(WebResourceType.BEACON, collect.type)
        val page = web.requests.first()
        assertTrue(page.mainFrame)
        assertEquals(WebResourceType.DOCUMENT, page.type)
        assertFalse(web.requests.single { it.host == "cdn.news.example" }.thirdParty)
        assertEquals(WebResourceType.SCRIPT, web.requests.single { it.host == "cdn.news.example" }.type)
    }

    @Test
    fun `requests carry host, port, scheme, phase and tracker entity like the app capture's connection records`() {
        val gtag = web.requests.single { it.host == "www.googletagmanager.com" }
        assertEquals("https", gtag.scheme)
        assertEquals(443, gtag.port)
        assertEquals(Phase.BEFORE_CONSENT, gtag.phase)
        assertEquals("Google Tag Manager", gtag.trackerEntity)
        val ad = web.requests.single { it.host == "securepubads.g.doubleclick.net" }
        assertEquals(Phase.AFTER_REJECT, ad.phase)
        assertEquals("Google Ads / AdMob", ad.trackerEntity)
        assertNull(web.requests.single { it.host == "cdn.news.example" }.trackerEntity)
        assertEquals("Meta (Facebook SDK)", web.requests.single { it.host == "www.facebook.com" }.trackerEntity)
        val plain = build(leakyReject().copy(requests = listOf(req(0.1, "http://news.example:8080/", mainFrame = true)))).web!!.requests.single()
        assertEquals("http" to 8080, plain.scheme to plain.port)
    }

    @Test
    fun `request phases are assigned around the recorded choice`() {
        val phases = SessionClassifier(WebTestData.catalog).classify(session).map { it.hostname to it.phase }
        assertEquals(
            listOf(
                "news.example" to Phase.BEFORE_CONSENT, "www.googletagmanager.com" to Phase.BEFORE_CONSENT,
                "cdn.news.example" to Phase.BEFORE_CONSENT, "region1.google-analytics.com" to Phase.BEFORE_CONSENT,
                "www.facebook.com" to Phase.BEFORE_CONSENT, "securepubads.g.doubleclick.net" to Phase.AFTER_REJECT,
                "news.example" to Phase.AFTER_REJECT,
            ),
            phases,
        )
        // Without a choice but with a banner on screen, everything is before consent; with no banner, no consent was seen.
        val noChoice = build(leakyReject().copy(consentEvents = listOf(WebTestData.shown(1.0, WebTestData.oneTrust))))
        assertTrue(SessionClassifier(WebTestData.catalog).classify(noChoice).all { it.phase == Phase.BEFORE_CONSENT })
        val silent = build(WebTestData.noBannerSilent())
        assertTrue(SessionClassifier(WebTestData.catalog).classify(silent).all { it.phase == Phase.NO_CONSENT_SEEN })
    }

    @Test
    fun `Meta's pixel path on facebook com is a tracker, other facebook com pages are not`() {
        val pixel = web.requests.single { it.host == "www.facebook.com" }
        assertEquals("Meta (Facebook SDK)", pixel.pathEntity)
        val event = session.netEvents.single { it.hostname == "www.facebook.com" }
        assertEquals("Meta (Facebook SDK)", event.entity)
        assertEquals(Category.ADVERTISING, event.category)
        assertNull(WebTrackers.default.pathRule("https://www.facebook.com/trending"))
        assertEquals("Meta (Facebook SDK)", WebTrackers.default.pathRule("https://www.facebook.com/tr/?id=1")?.entity)
        assertEquals("Pinterest Tag", WebTrackers.default.pathRule("https://s.pinimg.com/ct/core.js")?.entity)
        assertNull(WebTrackers.default.pathRule("https://s.pinimg.com/images/pin.png"))
    }

    @Test
    fun `cookies - first-party tracker names, third-party tracker hosts, and when each was set`() {
        val ga = web.cookies.single { it.name == "_ga" }
        assertFalse(ga.thirdParty)
        assertEquals("Google Analytics", ga.trackerEntity)
        assertEquals(WebCookieBasis.DOCUMENTED_NAME, ga.trackerBasis)
        assertEquals(WebSnapshot.BANNER, ga.firstSeenAt)
        assertEquals(t(1.1), ga.firstSeenNanos)
        assertNull(ga.absentSinceNanos)
        assertEquals(t(4.9), ga.rewrittenAfterNanos)
        assertEquals(".news.example", ga.domain)
        assertEquals(1_850_000_000_000, ga.expiresEpochMs)

        val ide = web.cookies.single { it.name == "IDE" }
        assertTrue(ide.thirdParty)
        assertEquals("doubleclick.net", ide.site)
        assertEquals("Google Ads / AdMob", ide.trackerEntity)
        assertEquals(WebCookieBasis.TRACKER_HOST, ide.trackerBasis)
        assertEquals(WebSnapshot.END, ide.firstSeenAt)
        assertEquals(t(4.9), ide.absentSinceNanos)

        val consent = web.cookies.single { it.name == "OptanonConsent" }
        assertNull(consent.trackerEntity)
        assertFalse(consent.presentAtEnd)
    }

    @Test
    fun `cookie values never leave the builder - only a length and a hash prefix`() {
        val ide = web.cookies.single { it.name == "IDE" }
        assertEquals("AHWqTUk-very-long-identifier".length, ide.valueLength)
        assertEquals(12, ide.valueHash.length)
        assertEquals(WebSessions.hash("AHWqTUk-very-long-identifier"), ide.valueHash)
        val saved = com.dpdpxray.core.report.AuditJson.encodeSessions(listOf(session))
        for (value in listOf("AHWqTUk-very-long-identifier", "GA1.1.111.222", "GA1.1.999.888", "fb.1.333.444", "isGpcEnabled=0")) {
            assertFalse("$value was saved", saved.contains(value))
        }
    }

    @Test
    fun `storage keys and resolver addresses are kept`() {
        assertEquals(listOf(WebStorageItem("local", "_gcl_ls", 20, t(1.0), WebSnapshot.BANNER)), web.storage)
        val gtag = session.netEvents.single { it.hostname == "www.googletagmanager.com" }
        assertEquals(listOf("142.250.1.1"), gtag.answerIps)
        assertEquals(AnswerStatus.COMPLETE, gtag.answerStatus)
        assertNull(session.netEvents.single { it.hostname == "cdn.news.example" }.answerStatus)
    }

    @Test
    fun `a redirect to another site moves first-party to the final page`() {
        val c = leakyReject().copy(requests = listOf(
            req(0.1, "https://news.example/", mainFrame = true),
            req(0.3, "https://www.news-group.example/home", mainFrame = true),
            req(0.6, "https://static.news-group.example/a.css"),
            req(0.7, "https://news.example/legacy.js"),
        ))
        val w = build(c).web!!
        assertEquals("www.news-group.example", w.pageHost)
        assertEquals("https://www.news-group.example/home", w.finalUrl)
        assertFalse(w.requests.single { it.host == "static.news-group.example" }.thirdParty)
        assertTrue(w.requests.single { it.url.endsWith("legacy.js") }.thirdParty)
        assertEquals("web:news.example", build(c).targetPackage)
    }

    @Test
    fun `non-web addresses are dropped and the request cap is honoured`() {
        val many = (1..WebLimits.MAX_REQUESTS + 5).map { req(it / 1000.0, "https://news.example/a$it.png") }
        val w = build(leakyReject().copy(requests = many + req(9.0, "data:image/png;base64,AAAA") + req(9.1, "blob:https://news.example/x"), requestsDropped = 2)).web!!
        assertEquals(WebLimits.MAX_REQUESTS, w.requests.size)
        assertEquals(7, w.requestsDropped)
        assertEquals(WebResourceType.IMAGE, w.requests.first().type)
    }

    @Test
    fun `request types are inferred only when the page did not report one`() {
        fun infer(url: String, accept: String? = null, dest: String? = null, method: String = "GET") = WebSessions.inferType(url, false, accept, dest, method)
        assertEquals(WebResourceType.SUBFRAME, infer("https://x.example/frame", accept = "text/html,*/*"))
        assertEquals(WebResourceType.IMAGE, infer("https://x.example/p", accept = "image/avif,image/webp,*/*"))
        assertEquals(WebResourceType.STYLESHEET, infer("https://x.example/s.css"))
        assertEquals(WebResourceType.FONT, infer("https://x.example/f.woff2?v=1"))
        assertEquals(WebResourceType.SCRIPT, infer("https://x.example/a", dest = "script"))
        assertEquals(WebResourceType.XHR_FETCH, infer("https://x.example/collect", method = "POST"))
        assertEquals(WebResourceType.UNKNOWN, infer("https://x.example/collect"))
    }
}
