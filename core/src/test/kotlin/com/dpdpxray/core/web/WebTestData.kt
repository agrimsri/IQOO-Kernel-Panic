package com.dpdpxray.core.web

import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.trackers.TrackerCatalog

/** Synthetic website-check captures for the tests: a made-up news site, not a real one. */
object WebTestData {
    const val S = 1_000_000_000L
    fun t(seconds: Double) = (seconds * S).toLong()

    val catalog: TrackerCatalog = TrackerCatalog.loadDefault()
    val sessions = WebSessions(catalog)

    /** The OneTrust banner exactly as the probe read the fixture page (reject on screen, one pre-ticked box). */
    val oneTrust: WebBanner by lazy { WebProbe.parseScan(javaClass.getResource("/web/fixtures/onetrust.probe.json")!!.readText())!!.banner!! }
    val hiddenReject: WebBanner by lazy { WebProbe.parseScan(javaClass.getResource("/web/fixtures/generic-hidden-reject.probe.json")!!.readText())!!.banner!! }

    fun req(seconds: Double, url: String, method: String = "GET", mainFrame: Boolean = false, accept: String? = null) =
        RawWebRequest(t(seconds), url, method, mainFrame, accept)

    fun shown(seconds: Double, banner: WebBanner) = ConsentEvent(t(seconds), ConsentKind.SCREEN_APPEARED, tree = WebBanners.tree(banner))

    /**
     * A leaky site: Google's tag, Google Analytics and Meta's pixel before any choice, an ad request after Reject, a
     * first-party _ga cookie written again after Reject and a third-party ad cookie set after it.
     */
    fun leakyReject(): WebPlanCapture = WebPlanCapture(
        id = "web-1-REJECT", plan = AuditPlan.REJECT, url = "https://news.example/",
        startedAtNanos = 0, endedAtNanos = t(20.2), startedAtEpochMs = 1_791_000_000_000,
        requests = listOf(
            req(0.1, "https://news.example/", mainFrame = true, accept = "text/html,application/xhtml+xml"),
            req(0.5, "https://www.googletagmanager.com/gtag/js?id=G-TEST"),
            req(0.9, "https://cdn.news.example/app.js"),
            req(1.2, "https://region1.google-analytics.com/g/collect?v=2&tid=G-TEST", method = "POST"),
            req(2.0, "https://www.facebook.com/tr?id=1&ev=PageView"),
            req(6.0, "https://securepubads.g.doubleclick.net/pagead/ads?gdfp_req=1"),
            req(7.0, "https://news.example/api/articles"),
        ),
        resourceTypes = mapOf(
            "https://www.googletagmanager.com/gtag/js?id=G-TEST" to WebResourceType.SCRIPT,
            "https://region1.google-analytics.com/g/collect?v=2&tid=G-TEST" to WebResourceType.BEACON,
        ),
        cookieSnapshots = listOf(
            CookieSnapshot(WebSnapshot.BANNER, t(1.0), t(1.1), listOf(CookieReading("news.example", "_ga", "GA1.1.111.222"))),
            CookieSnapshot(
                WebSnapshot.BEFORE_CHOICE, t(4.9), t(4.95),
                listOf(CookieReading("news.example", "_ga", "GA1.1.111.222"), CookieReading("news.example", "_fbp", "fb.1.333.444"),
                    CookieReading("news.example", "OptanonConsent", "isGpcEnabled=0")),
            ),
            CookieSnapshot(
                WebSnapshot.END, t(20.0), t(20.1),
                listOf(CookieReading("news.example", "_ga", "GA1.1.999.888"), CookieReading("news.example", "_fbp", "fb.1.333.444"),
                    CookieReading("securepubads.g.doubleclick.net", "IDE", "AHWqTUk-very-long-identifier")),
            ),
        ),
        storageSnapshots = listOf(StorageSnapshot(WebSnapshot.BANNER, t(1.0), listOf(WebStorageKey("_gcl_ls", 20)), emptyList())),
        cookieMeta = listOf(WebCookieMeta("_ga", ".news.example", "/", 1_850_000_000_000)),
        banner = oneTrust,
        consentEvents = listOf(shown(1.0, oneTrust), ConsentEvent(t(5.0), ConsentKind.REJECT, "Reject All")),
        profileCleared = true, pageLoaded = true, deviceModel = "Test phone", androidVersion = "16",
        addresses = mapOf("www.googletagmanager.com" to listOf("142.250.1.1")),
    )

    /** A site with no banner the probe can find, contacting Google Analytics. */
    fun noBannerSilent(): WebPlanCapture = WebPlanCapture(
        id = "web-2-SILENT", plan = AuditPlan.SILENT, url = "https://quiet.example/",
        startedAtNanos = 0, endedAtNanos = t(25.0), startedAtEpochMs = 1_791_000_000_000,
        requests = listOf(
            req(0.1, "https://quiet.example/", mainFrame = true),
            req(1.0, "https://www.google-analytics.com/g/collect?v=2"),
        ),
        profileCleared = true, pageLoaded = true,
    )

    fun build(c: WebPlanCapture): AuditSession = sessions.build(c)
}
