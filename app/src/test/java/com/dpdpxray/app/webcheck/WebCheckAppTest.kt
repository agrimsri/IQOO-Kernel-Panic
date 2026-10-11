package com.dpdpxray.app.webcheck

import com.dpdpxray.app.capture.DnsForwarder
import com.dpdpxray.app.data.AuditBundle
import com.dpdpxray.app.data.AuditMeta
import com.dpdpxray.app.report.AuditJsonExport
import com.dpdpxray.app.report.EvidenceReport
import com.dpdpxray.app.trust.HomeTrust
import com.dpdpxray.app.trust.TrustText
import com.dpdpxray.app.ui.guide.Bi
import com.dpdpxray.core.export.AuditBundles
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import com.dpdpxray.core.web.RawWebRequest
import com.dpdpxray.core.web.WebAudit
import com.dpdpxray.core.web.WebBanner
import com.dpdpxray.core.web.WebBannerDetection
import com.dpdpxray.core.web.WebControl
import com.dpdpxray.core.web.WebPlanCapture
import com.dpdpxray.core.web.WebSessions
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebCheckAppTest {
    private val s = 1_000_000_000L
    private val catalog = TrackerCatalog.loadDefault()
    private fun dir(vararg c: String) = c.map(::File).first { it.isDirectory }
    private val appRoot = dir("src/main/java/com/dpdpxray/app", "app/src/main/java/com/dpdpxray/app")
    private fun source(path: String) = File(appRoot, path).readText()

    private val capture = WebPlanCapture(
        id = "web-1-REJECT", plan = AuditPlan.REJECT, url = "https://news.example/", startedAtNanos = 0, endedAtNanos = 20 * s,
        startedAtEpochMs = 1_791_000_000_000,
        requests = listOf(
            RawWebRequest(s / 10, "https://news.example/", mainFrame = true),
            RawWebRequest(s / 2, "https://www.googletagmanager.com/gtag/js?id=G-TEST"),
            RawWebRequest(2 * s, "https://www.facebook.com/tr?id=1&ev=PageView"),
            RawWebRequest(6 * s, "https://securepubads.g.doubleclick.net/pagead/ads"),
        ),
        consentEvents = listOf(ConsentEvent(1 * s, ConsentKind.SCREEN_APPEARED), ConsentEvent(5 * s, ConsentKind.REJECT, "Reject All")),
        profileCleared = true,
    )
    private val sessions = listOf(WebSessions(catalog).build(capture))
    private val result = RulesEngine(catalog).evaluate(sessions)

    // ---- copy -------------------------------------------------------------------------------------------------------

    private fun allCopy(): List<Bi> = listOf(
        WebCheckCopy.segment, WebCheckCopy.homeTitle, WebCheckCopy.homeBody, WebCheckCopy.title, WebCheckCopy.lead, WebCheckCopy.fieldLabel, WebCheckCopy.fieldHint, WebCheckCopy.disclosure, WebCheckCopy.addressesNote, WebCheckCopy.upgraded, WebCheckCopy.tapsTitle, WebCheckCopy.tapsBody, WebCheckCopy.plansLine, WebCheckCopy.start, WebCheckCopy.limits, WebCheckCopy.live, WebCheckCopy.requests, WebCheckCopy.toTrackers, WebCheckCopy.beforeChoice, WebCheckCopy.caveat, WebCheckCopy.bannerFound, WebCheckCopy.stop, WebCheckCopy.stopTitle, WebCheckCopy.stopBody, WebCheckCopy.keepChecking, WebCheckCopy.stopped, WebCheckCopy.backToCheck, WebCheckCopy.preparing, WebCheckCopy.clearing, WebCheckCopy.opening, WebCheckCopy.lookingForBanner, WebCheckCopy.silent, WebCheckCopy.silentBanner, WebCheckCopy.tappingReject, WebCheckCopy.tappingAccept, WebCheckCopy.tapReject, WebCheckCopy.tapAccept, WebCheckCopy.watching, WebCheckCopy.saving, WebCheckCopy.alreadyRunning, WebCheckCopy.appCheckRunning, WebCheckCopy.failed, WebCheckCopy.notCleared, WebCheckCopy.pageStopped, WebCheckCopy.noTapNote, WebCheckCopy.noChoiceNote, WebCheckCopy.notLoadedNote, WebCheckCopy.stillShownNote, WebCheckCopy.evidenceTitle, WebCheckCopy.evidenceSummary, WebCheckCopy.notice, WebCheckCopy.mapSummary, WebCheckCopy.mapNote, WebCheckCopy.timelineSummary,
    ) +
        listOf(WebCheckCopy.noBannerNote(20), WebCheckCopy.frameTapsNote("cdn.example"), WebCheckCopy.frameTapsNote(null)) + WebCheckView.planNames

    @Test
    fun `every website check text has English and Hindi`() {
        val all = allCopy()
        assertTrue(all.size > 40)
        all.forEach { b ->
            assertTrue(b.en, b.en.isNotBlank())
            assertTrue("no Hindi in '${b.hi}'", b.hi.any { it in 'ऀ'..'ॿ' } || b.hi == b.en)
        }
    }

    @Test
    fun `the start screen discloses the new network path in the agreed words`() {
        assertEquals(
            "TraceVeil opens this site on your phone to check it; the site sees a normal visit. Nothing else is sent anywhere.",
            WebCheckCopy.disclosure.en,
        )
        assertTrue(WebCheckCopy.addressesNote.en.contains("DNS resolver"))
        val screen = source("webcheck/WebCheckScreens.kt")
        assertTrue(screen.contains("WebCheckCopy.disclosure.pick(lang)"))
    }

    @Test
    fun `nothing in the website check's words claims safety, compliance or a verdict`() {
        val banned = Regex("\\b(safe|safety|secure|compliant|certified|malicious|fraud|illegal|guarantee)", RegexOption.IGNORE_CASE)
        allCopy().forEach { assertNull(it.en, banned.find(it.en)) }
    }

    // ---- live counters and the result card ---------------------------------------------------------------------------

    @Test
    fun `live counters count trackers by host and by documented path, and those before the choice`() {
        val c = WebCheckView.counts(capture.requests, catalog, choiceNanos = 5 * s)
        assertEquals(WebLiveCounts(requests = 4, toTrackers = 3, beforeChoice = 2), c)
        assertEquals(3, WebCheckView.counts(capture.requests, catalog, choiceNanos = null).beforeChoice)
    }

    @Test
    fun `the evidence card summarises each run without cookie values`() {
        val run = WebCheckView.summary(sessions).single()
        assertEquals(AuditPlan.REJECT.label, run.plan)
        val lines = run.lines.map { it.en }
        assertTrue(lines.toString(), lines[0].startsWith("Requests: 4 · 3 to trackers · 3 to other sites"))
        assertTrue(lines.any { it.startsWith("Cookies: 0") && it.contains("values not stored") })
        assertTrue(lines.any { it == "Choice: TraceVeil tapped \"Reject All\" at 5.0 s" })
        assertTrue(lines.any { it.startsWith("Banner:") })
    }

    @Test
    fun `a person's tap is classified by the banner's own control first, then by the consent wording`() {
        val banner = WebBanner(
            cmp = "Didomi", detection = WebBannerDetection.CMP_ELEMENT,
            controls = listOf(WebControl(0, "Continue without agreeing", cmpRole = "reject"), WebControl(1, "Agree and close", cmpRole = "accept")),
        )
        assertEquals(ConsentKind.REJECT, WebCheckRunner.classifyTap("Continue without agreeing", banner))
        assertEquals(ConsentKind.ACCEPT, WebCheckRunner.classifyTap("Agree and close", banner))
        assertEquals(ConsentKind.REJECT, WebCheckRunner.classifyTap("Reject all", null))
        assertNull(WebCheckRunner.classifyTap("Read more", banner))
        assertNull(WebCheckRunner.classifyTap("  ", banner))
    }

    // ---- export, report, trust ----------------------------------------------------------------------------------------

    private fun bundle() = AuditBundle(
        AuditMeta("web-1", WebAudit.packageFor("news.example"), "https://news.example", 1_791_000_000_000, result.score, result.band,
            result.findings.size, listOf(AuditPlan.REJECT.label), timingProfile = "normal", targetKind = WebAudit.KIND),
        sessions, result, File("."),
    )

    @Test
    fun `the JSON export names the website target, and the CI reader gets the same result back`() {
        val built = AuditJsonExport.build(bundle(), "1.1.0", exportedAt = "2026-10-11T08:00:00Z")
        assertEquals("web", built.target?.kind)
        assertEquals("https://news.example/", built.target?.url)
        val back = AuditBundles.decode(AuditBundles.encode(built))
        assertEquals(result, RulesEngine(catalog).evaluate(back.sessions))
        assertEquals("web", AuditBundles.metaOf(back).targetKind)
        val appBundle = bundle().copy(sessions = sessions.map { it.copy(web = null) }, meta = bundle().meta.copy(targetKind = null))
        val root = AuditBundles.element(AuditJsonExport.build(appBundle, "1.1.0", exportedAt = "2026-10-11T08:00:00Z")).jsonObject
        assertFalse(root.containsKey("target"))
        assertFalse(root.getValue("meta").jsonObject.containsKey("targetKind"))
    }

    @Test
    fun `a website report states the browser scope, not the DNS relay`() {
        val md = EvidenceReport.annotate("# report\n", sessions, "normal")
        assertTrue(md.contains(EvidenceReport.WEB_SCOPE))
        assertFalse(md.contains(EvidenceReport.LOCAL_SCOPE))
    }

    @Test
    fun `the trust screen lists both website network paths, and they are the only files using them`() {
        val paths = TrustText.networkPaths(false, DnsForwarder.FALLBACKS, onlineVoiceAvailable = true).associateBy { it.sourceFile }
        assertTrue(paths.getValue("webcheck/WebCheckBrowser.kt").line.contains("only while the check is on screen"))
        assertTrue(paths.getValue("webcheck/WebHostResolver.kt").line.contains("DNS resolver"))
        val kotlin = appRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertEquals(listOf("WebCheckBrowser.kt"), kotlin.filter { it.readText().contains("android.webkit.") }.map { it.name })
        // The browser never proceeds past a certificate error and never opens other apps' links.
        val browser = source("webcheck/WebCheckBrowser.kt")
        assertTrue(browser.contains("handler.cancel()"))
        assertFalse(browser.contains("handler.proceed("))
        assertFalse(browser.contains("addJavascriptInterface"))
        assertTrue(browser.contains("return null // the request goes out exactly as the page made it"))
        val internet = TrustText.purpose("android.permission.INTERNET", "com.dpdpxray.app", onlineVoiceAvailable = false)!!.second
        assertTrue(internet.contains("opens a website you ask TraceVeil to check"))
        assertTrue(HomeTrust.facts(false, false).first().detail.en.contains("website check"))
    }

    @Test
    fun `the manifest turns off the browser's usage statistics and Safe Browsing lookups`() {
        val manifest = dir("src/main", "app/src/main").resolve("AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:name=\"android.webkit.WebView.MetricsOptOut\" android:value=\"true\""))
        assertTrue(manifest.contains("android:name=\"android.webkit.WebView.EnableSafeBrowsing\" android:value=\"false\""))
    }

    @Test
    fun `cookie headers from Android's store are split into names and values`() {
        assertEquals(listOf("_ga" to "GA1.1.1.2", "IDE" to "x=y", "" to "flag"), WebCheckBrowser.parseCookieHeader("_ga=GA1.1.1.2; IDE=x=y; flag"))
        assertTrue(WebCheckBrowser.parseCookieHeader("").isEmpty())
    }
}
