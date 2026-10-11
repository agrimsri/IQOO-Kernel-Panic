package com.dpdpxray.core.web

import com.dpdpxray.core.consent.ConsentLexicon
import com.dpdpxray.core.darkpattern.CcpaPattern
import com.dpdpxray.core.darkpattern.DarkPatternScanner
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.RejectOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The consent-banner parsing, run on what TraceVeil's in-page probe returned for the synthetic HTML fixtures in
 * test resources web/fixtures. The .probe.json files are the probe's real output in headless desktop Chromium
 * (tools/webcheck/probe-fixtures.mjs), not hand-written; the script itself does not run in these JVM tests.
 */
class WebProbeFixturesTest {
    private fun probe(name: String): WebProbeResult {
        val text = javaClass.getResource("/web/fixtures/$name.probe.json")?.readText() ?: error("missing fixture $name")
        return WebProbe.parseScan(text) ?: error("unreadable fixture $name")
    }

    private fun banner(name: String) = probe(name).banner ?: error("$name: no banner")

    @Test
    fun `OneTrust banner - reject on the first screen, a pre-ticked box in the settings layer`() {
        val b = banner("onetrust")
        assertEquals("OneTrust", b.cmp)
        assertEquals(WebBannerDetection.CMP_ELEMENT, b.detection)
        val audit = WebBanners.screenAudit(b)
        assertTrue(audit.isConsentScreen)
        assertEquals(RejectOption.VISIBLE, audit.rejectOption)
        assertEquals(true, audit.preTicked)
        assertEquals(listOf("Performance Cookies"), WebBanners.preTicked(b).map { it.label })
        assertEquals(true, audit.withdrawalInfo)
        assertTrue("rejectOption" in audit.observedFields && "preTicked" in audit.observedFields)

        val reject = WebBanners.stepFor(AuditPlan.REJECT, b, settingsOpened = false) as WebStep.Choose
        assertEquals("Reject All", reject.control.text)
        assertFalse(reject.control.secondLayer)
        assertEquals(ConsentKind.REJECT, reject.kind)
        val accept = WebBanners.stepFor(AuditPlan.ACCEPT, b, settingsOpened = false) as WebStep.Choose
        assertEquals("Accept All Cookies", accept.control.text)
    }

    @Test
    fun `the banner tree holds only what is on screen and never makes an always-on box pre-ticked`() {
        val tree = WebBanners.tree(banner("onetrust"))
        val nodes = tree.flatten()
        assertEquals(listOf("Cookie Policy", "Cookies Settings", "Reject All", "Accept All Cookies"), nodes.filter { it.isClickable }.map { it.text })
        assertFalse(nodes.any { it.text == "Confirm My Choices" })
        val necessary = nodes.single { it.text.orEmpty().startsWith("Strictly Necessary Cookies") }
        assertFalse(necessary.isCheckable)
        assertTrue(nodes.single { it.text.orEmpty().startsWith("Performance Cookies") }.isChecked)
        // Physical pixels: the probe measured CSS pixels at a device pixel ratio of 2.625.
        assertEquals(380 * 2.625, nodes.single { it.text == "Reject All" }.bounds!!.let { it.right - it.left }.toDouble(), 1.0)
        assertTrue(ConsentLexicon.mentionsPrivacyPolicy(nodes.map { it.label }))
    }

    @Test
    fun `Cookiebot dialog - Deny is a visible refusal, Allow selection is neither, Necessary is not pre-ticked`() {
        val b = banner("cookiebot")
        assertEquals("Cookiebot", b.cmp)
        val audit = WebBanners.screenAudit(b)
        assertEquals(RejectOption.VISIBLE, audit.rejectOption)
        assertEquals(false, audit.preTicked)
        assertNull(WebBanners.kindOf(b.controls.single { it.text == "Allow selection" }))
        assertEquals("Deny", (WebBanners.stepFor(AuditPlan.REJECT, b, false) as WebStep.Choose).control.text)
        assertEquals("Allow all", (WebBanners.stepFor(AuditPlan.ACCEPT, b, false) as WebStep.Choose).control.text)
        assertEquals(4, b.checkboxes.size)
    }

    @Test
    fun `a home-grown notice found by its wording - refusal hidden behind Manage options`() {
        val b = banner("generic-hidden-reject")
        assertNull(b.cmp)
        assertEquals(WebBannerDetection.GENERIC_TEXT, b.detection)
        assertEquals(RejectOption.HIDDEN, WebBanners.screenAudit(b).rejectOption)
        val open = WebBanners.stepFor(AuditPlan.REJECT, b, settingsOpened = false) as WebStep.OpenSettings
        assertEquals("Manage options", open.control.text)
        val none = WebBanners.stepFor(AuditPlan.REJECT, b, settingsOpened = true) as WebStep.Unavailable
        assertTrue(none.reason.startsWith(WebRules.NO_REJECT_NOTE))
        assertEquals("Accept & continue", (WebBanners.stepFor(AuditPlan.ACCEPT, b, false) as WebStep.Choose).control.text)
    }

    @Test
    fun `the dark-pattern signals read a website banner like an app consent screen`() {
        val b = banner("generic-hidden-reject")
        val session = AuditSession(
            id = "a", targetPackage = "web:travel.example", targetLabel = "https://travel.example", plan = AuditPlan.ACCEPT,
            consentEvents = listOf(ConsentEvent(1_000_000_000L, ConsentKind.SCREEN_APPEARED, tree = WebBanners.tree(b))),
        )
        val signals = DarkPatternScanner.scan(listOf(session))
        val interference = signals.single { it.pattern == CcpaPattern.INTERFACE_INTERFERENCE }
        assertTrue(interference.evidence.joinToString(), interference.evidence.any { it.contains("plain text") })
    }

    @Test
    fun `a Hindi notice is classified by the shared consent wording`() {
        val b = banner("generic-hindi")
        assertEquals(RejectOption.VISIBLE, WebBanners.screenAudit(b).rejectOption)
        assertEquals("अस्वीकार करें", (WebBanners.stepFor(AuditPlan.REJECT, b, false) as WebStep.Choose).control.text)
        assertEquals("सभी स्वीकार करें", (WebBanners.stepFor(AuditPlan.ACCEPT, b, false) as WebStep.Choose).control.text)
        assertEquals(true, WebBanners.screenAudit(b).withdrawalInfo)
    }

    @Test
    fun `Didomi - the consent tool's own element makes "Continue without agreeing" a refusal`() {
        val b = banner("didomi")
        val reject = WebBanners.stepFor(AuditPlan.REJECT, b, false) as WebStep.Choose
        assertEquals("Continue without agreeing", reject.control.text)
        assertEquals("reject", reject.control.cmpRole)
        assertFalse(reject.control.isButton)
        assertEquals(RejectOption.VISIBLE, WebBanners.screenAudit(b).rejectOption)
    }

    @Test
    fun `Usercentrics inside a shadow root is read`() {
        val b = banner("usercentrics-shadow")
        assertEquals("Usercentrics", b.cmp)
        assertEquals("Deny", (WebBanners.stepFor(AuditPlan.REJECT, b, false) as WebStep.Choose).control.text)
        assertEquals("Accept All", (WebBanners.stepFor(AuditPlan.ACCEPT, b, false) as WebStep.Choose).control.text)
    }

    @Test
    fun `a consent tool's frame from another site is noticed but cannot be read or tapped`() {
        val b = banner("sourcepoint-frame")
        assertEquals(WebBannerDetection.FRAME_ONLY, b.detection)
        assertEquals("Sourcepoint", b.cmp)
        assertEquals("cdn.privacy-mgmt.com", b.frameHost)
        val audit = WebBanners.screenAudit(b)
        assertTrue(audit.isConsentScreen)
        assertEquals(RejectOption.UNKNOWN, audit.rejectOption)
        assertNull(audit.preTicked)
        assertTrue(WebBanners.stepFor(AuditPlan.REJECT, b, false) is WebStep.Unavailable)
    }

    @Test
    fun `a page without a banner still reports its storage keys and value lengths, never values`() {
        val p = probe("no-banner")
        assertNull(p.banner)
        assertEquals(listOf(WebStorageKey("theme", 4), WebStorageKey("visitor_id", 12)), p.localStorage)
        assertEquals(listOf(WebStorageKey("tab", 1)), p.sessionStorage)
        assertEquals("https://fixture.example/no-banner.html", p.url)
    }

    @Test
    fun `a probe click is reported as a script click, which tells TraceVeil's taps from the person's`() {
        val text = javaClass.getResource("/web/fixtures/onetrust-click.probe.json")!!.readText()
        val root = kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        val click = WebProbe.parseClick(root["click"].toString())
        assertNotNull(click)
        assertTrue(click!!.ok)
        assertEquals("Accept All Cookies", click.text)
        val after = WebProbe.parseScan(root["after"].toString())!!
        assertEquals(listOf(WebClick(after.clicks.single().epochMs, "Accept All Cookies", trusted = false)), after.clicks)
    }

    @Test
    fun `the parser is tolerant - JSON-string wrapping, nulls, junk and errors`() {
        assertNull(WebProbe.parseScan(null))
        assertNull(WebProbe.parseScan("null"))
        assertNull(WebProbe.parseScan("not json"))
        val wrapped = WebProbe.parseScan("\"{\\\"v\\\":1,\\\"url\\\":\\\"https://a.example/\\\"}\"")
        assertEquals("https://a.example/", wrapped?.url)
        assertEquals("boom", WebProbe.parseScan("{\"v\":1,\"error\":\"boom\"}")?.error)
        val unknown = WebProbe.parseScan("{\"v\":1,\"url\":\"https://a.example/\",\"future\":[1,2],\"banner\":{\"how\":\"cmp\",\"cmp\":\"X\",\"controls\":[{\"i\":0,\"text\":\"Accept\",\"role\":\"weird\",\"vis\":true}]}}")
        assertNull(unknown!!.banner!!.controls.single().cmpRole)
    }

    @Test
    fun `the probe script targets the consent tools the fixtures use`() {
        val js = WebProbe.script
        assertTrue(js.startsWith("(function (mode, arg)"))
        for (id in listOf("#onetrust-banner-sdk", "#onetrust-reject-all-handler", "#CybotCookiebotDialog", "#CybotCookiebotDialogBodyButtonDecline",
            "#didomi-notice", ".didomi-continue-without-agreeing", "uc-deny-all-button", "sp_message_iframe", "#qc-cmp2-container", "#truste-consent-track")) {
            assertTrue("probe does not look for $id", js.contains(id))
        }
        assertTrue(WebProbe.scanCall().endsWith("('scan', null)"))
        assertTrue(WebProbe.clickCall(3).endsWith("('click', 3)"))
    }

    @Test
    fun `Resource Timing initiator types map to request types`() {
        assertEquals(WebResourceType.BEACON, WebProbe.initiatorType("beacon"))
        assertEquals(WebResourceType.IMAGE, WebProbe.initiatorType("img"))
        assertEquals(WebResourceType.XHR_FETCH, WebProbe.initiatorType("fetch"))
        assertEquals(WebResourceType.SCRIPT, WebProbe.initiatorType("script"))
        assertEquals(WebResourceType.UNKNOWN, WebProbe.initiatorType(""))
        assertEquals(WebResourceType.OTHER, WebProbe.initiatorType("other"))
    }
}
