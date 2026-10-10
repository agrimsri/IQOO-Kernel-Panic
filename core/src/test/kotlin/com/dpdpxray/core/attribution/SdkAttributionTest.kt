package com.dpdpxray.core.attribution

import com.dpdpxray.core.fixes.FixCatalog
import com.dpdpxray.core.model.AppManifestFacts
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.AuditSource
import com.dpdpxray.core.model.Category
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.RejectOption
import com.dpdpxray.core.model.ScreenAudit
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Which SDK caused this?": groups an audit's lookups by the catalogue service behind them, links each group to the
 * findings that name it, and offers the existing fix templates. Fixtures are test-only copies of the LeakyShop demo
 * shapes in DemoScoreRegressionTest, not device observations.
 */
class SdkAttributionTest {
    private val S = 1_000_000_000L
    private val catalog = TrackerCatalog.loadDefault()
    private val engine = RulesEngine(catalog)
    private val attribution = SdkAttribution(catalog, FixCatalog.loadDefault())

    private fun net(t: Double, host: String, cname: List<String> = emptyList()) =
        NetEvent(tNanos = (t * S).toLong(), hostname = host, cnameChain = cname)

    private val screen = ScreenAudit(
        isConsentScreen = true, preTicked = true, rejectOption = RejectOption.HIDDEN,
        source = AuditSource.DETERMINISTIC, observedFields = setOf("preTicked", "rejectOption"),
    )
    private val shown = ConsentEvent((2.1 * S).toLong(), ConsentKind.SCREEN_APPEARED)
    private val base = AuditSession(
        id = "", targetPackage = "com.leakyshop.demo", targetLabel = "LeakyShop (sample)", plan = AuditPlan.SILENT,
        screenAudit = screen, freshState = true,
    )

    private val leaky = listOf(
        base.copy(
            id = "c", plan = AuditPlan.SILENT, endedAtNanos = 30 * S, consentEvents = listOf(shown),
            netEvents = listOf(
                net(0.4, "api.leakyshop.example"), net(0.6, "app-measurement.com"), net(0.7, "graph.facebook.com"),
                net(0.9, "firebase-settings.crashlytics.com"), net(1.3, "launches.appsflyersdk.com"), net(6.0, "app-measurement.com"),
            ),
        ),
        base.copy(
            id = "b", plan = AuditPlan.REJECT, endedAtNanos = 35 * S,
            consentEvents = listOf(shown, ConsentEvent((8.3 * S).toLong(), ConsentKind.REJECT, "Reject all")),
            netEvents = listOf(
                net(0.4, "api.leakyshop.example"), net(0.6, "app-measurement.com"), net(0.8, "graph.facebook.com"),
                net(9.4, "graph.facebook.com"), net(11.2, "app-measurement.com"), net(12.0, "launches.appsflyersdk.com"),
            ),
        ),
        base.copy(
            id = "a", plan = AuditPlan.ACCEPT, endedAtNanos = 30 * S,
            consentEvents = listOf(shown, ConsentEvent((4.9 * S).toLong(), ConsentKind.ACCEPT, "Accept & Continue")),
            netEvents = listOf(
                net(0.4, "api.leakyshop.example"), net(0.6, "app-measurement.com"), net(0.7, "graph.facebook.com"),
                net(5.2, "cdn.leakyshop.example"), net(5.6, "googleads.g.doubleclick.net"), net(6.1, "in1.clevertap-prod.com"),
            ),
        ),
    )

    private fun groups(sessions: List<AuditSession>) = attribution.groups(engine.evaluate(sessions), sessions)

    @Test
    fun `leaky demo - the three SDKs behind C1, C2 and C10 come first, sorted by impact`() {
        val g = groups(leaky)
        val top = g.take(3)
        assertEquals(setOf("Google Analytics for Firebase", "Meta (Facebook SDK)", "AppsFlyer"), top.map { it.name }.toSet())
        top.forEach { assertTrue(it.name, it.checks.containsAll(listOf(CheckId.C1, CheckId.C2, CheckId.C10))) }
        // Impact never increases down the list; equal impact keeps the service with more lookups first.
        assertEquals(g.map { it.impact }.sortedDescending(), g.map { it.impact })
        assertEquals("Google Analytics for Firebase", g.first().name)
        // C1 15 + C2 30 + C10 (8, inferred, so half) = 49, the same weights the score uses.
        assertEquals(49.0, g.first().impact, 0.001)
    }

    @Test
    fun `each group says where its hosts were first seen relative to consent`() {
        val meta = groups(leaky).first { it.name == "Meta (Facebook SDK)" }
        assertEquals("Meta", meta.company)
        assertEquals(Category.ADVERTISING, meta.category)
        val host = meta.hosts.single()
        assertEquals("graph.facebook.com", host.host)
        assertEquals(4, host.lookups)
        assertEquals(0.7, host.firstSeenSeconds!!, 0.001)
        assertEquals(SdkAttribution.BEFORE_CONSENT, host.firstRelation)
        // Accept plan: graph.facebook.com was only looked up before the user accepted.
        assertEquals(listOf(SdkAttribution.BEFORE_CONSENT, SdkAttribution.AFTER_REJECT), host.relations)
        assertEquals("first seen 0.7 s after launch · before consent", SdkAttribution.firstSeenLine(meta))
    }

    @Test
    fun `catalogue wording and evidence label are honest`() {
        val g = groups(leaky)
        val meta = g.first { it.name == "Meta (Facebook SDK)" }
        assertEquals(EvidenceType.OBSERVED, meta.evidence)
        assertEquals("This hostname belongs to Meta (Facebook SDK) in TraceVeil's catalogue.", meta.catalogueMatch)
        // The company is named when the service name does not already start with it.
        val crash = g.first { it.name == "Firebase Crashlytics" }
        assertEquals("This hostname belongs to Google's Firebase Crashlytics in TraceVeil's catalogue.", crash.catalogueMatch)
    }

    @Test
    fun `fix steps reuse the existing templates, only for checks they apply to`() {
        val g = groups(leaky).associateBy { it.name }
        assertEquals(listOf("facebook"), g.getValue("Meta (Facebook SDK)").fixIds)
        assertEquals(listOf("firebase-analytics"), g.getValue("Google Analytics for Firebase").fixIds)
        assertEquals(listOf("appsflyer"), g.getValue("AppsFlyer").fixIds)
        // Looked up only after Accept, or not a tracking category: no finding, so nothing to fix.
        assertEquals(emptyList<String>(), g.getValue("Firebase Crashlytics").fixIds)
        assertEquals(emptyList<CheckId>(), g.getValue("Google Ads / AdMob").checks)
        assertEquals(emptyList<String>(), g.getValue("Google Ads / AdMob").fixIds)
    }

    @Test
    fun `hosts outside the catalogue go last into an honestly labelled unknown group`() {
        val g = groups(leaky)
        val unknown = g.last()
        assertFalse(unknown.known)
        assertEquals(SdkAttribution.UNKNOWN_GROUP, unknown.name)
        assertNull(unknown.company)
        assertEquals(listOf("api.leakyshop.example", "cdn.leakyshop.example"), unknown.hosts.map { it.host })
        assertTrue(unknown.checks.isEmpty() && unknown.fixIds.isEmpty())
        assertTrue(unknown.catalogueMatch, unknown.catalogueMatch.startsWith("Not in TraceVeil's tracker catalogue"))
        assertTrue(unknown.catalogueMatch, unknown.catalogueMatch.contains("does not guess"))
        assertEquals(1, g.count { !it.known })
    }

    @Test
    fun `fixed demo has no group linked to a finding, and no headline`() {
        val fixed = leaky.map { s ->
            s.copy(netEvents = listOf(net(0.4, "api.leakyshop.example"), net(5.2, "cdn.leakyshop.example")), screenAudit = null)
        }
        val g = groups(fixed)
        assertTrue(g.all { it.impact == 0.0 && it.checks.isEmpty() })
        assertNull(SdkAttribution.headline(g, byCompany = false))
        assertNull(SdkAttribution.headline(emptyList(), byCompany = true))
    }

    @Test
    fun `headline names the services behind the findings, and says most only when one dominates`() {
        val g = groups(leaky)
        assertEquals(
            "What this check found involves Google Analytics for Firebase, Meta (Facebook SDK) and AppsFlyer.",
            SdkAttribution.headline(g, byCompany = false),
        )
        assertEquals("What this check found involves Google, Meta and AppsFlyer.", SdkAttribution.headline(g, byCompany = true))
        // Only Meta looked up after Reject: C2 (30) outweighs everything else.
        val metaOnly = listOf(
            leaky[1].copy(netEvents = listOf(net(0.6, "app-measurement.com"), net(9.4, "graph.facebook.com"), net(9.8, "graph.facebook.com"))),
        )
        assertEquals("Most of what this check found involves Meta.", SdkAttribution.headline(groups(metaOnly), byCompany = true))
    }

    @Test
    fun `host preview shows two hosts and counts the rest`() {
        val hosts = listOf("a.example", "b.example", "c.example", "d.example").map {
            HostSighting(it, 1, 1.0, AuditPlan.SILENT, SdkAttribution.BEFORE_CONSENT, listOf(SdkAttribution.BEFORE_CONSENT))
        }
        assertEquals("a.example, b.example +2 more", SdkAttribution.hostPreview(hosts))
        assertEquals("a.example", SdkAttribution.hostPreview(hosts.take(1)))
        assertEquals("", SdkAttribution.hostPreview(emptyList()))
    }

    @Test
    fun `an SDK named only by the manifest check is listed as inferred, without hosts`() {
        val manifest = AppManifestFacts(
            versionName = "1.0", versionCode = 1,
            sdkComponents = listOf("com.facebook.internal.FacebookInitProvider"),
            partsRead = listOf(AppManifestFacts.PART_META_DATA) + AppManifestFacts.COMPONENT_PARTS,
        )
        val sessions = listOf(
            base.copy(
                id = "m", plan = AuditPlan.SILENT, endedAtNanos = 30 * S, consentEvents = listOf(shown), manifestFacts = manifest,
                netEvents = listOf(net(0.4, "api.leakyshop.example"), net(0.6, "app-measurement.com")),
            ),
        )
        val meta = groups(sessions).first { it.name == "Meta (Facebook SDK)" }
        assertEquals(listOf(CheckId.C13), meta.checks)
        assertTrue(meta.hosts.isEmpty())
        assertEquals(EvidenceType.INFERRED, meta.evidence)
        assertEquals(listOf("facebook"), meta.fixIds)
        assertEquals("", SdkAttribution.firstSeenLine(meta))
        assertTrue(meta.catalogueMatch, meta.catalogueMatch.contains("manifest"))
    }

    @Test
    fun `kept-data plans without a consent screen say consent history unknown, never before consent`() {
        val kept = listOf(
            base.copy(
                id = "k", plan = AuditPlan.SILENT, endedAtNanos = 30 * S, freshState = false, consentEvents = emptyList(),
                netEvents = listOf(net(1.5, "graph.facebook.com")),
            ),
        )
        val meta = groups(kept).first { it.name == "Meta (Facebook SDK)" }
        assertEquals(SdkAttribution.HISTORY_UNKNOWN, meta.hosts.single().firstRelation)
        assertEquals(listOf(CheckId.U1), meta.checks)
        // U1 is information only: it never picks a fix template.
        assertEquals(emptyList<String>(), meta.fixIds)
    }

    @Test
    fun `an alias that decided the match is kept on the sighting`() {
        val aliased = listOf(
            base.copy(
                id = "x", plan = AuditPlan.SILENT, endedAtNanos = 30 * S, consentEvents = listOf(shown),
                netEvents = listOf(net(0.5, "metrics.leakyshop.example", listOf("metrics.leakyshop.example", "launches.appsflyersdk.com"))),
            ),
        )
        val af = groups(aliased).first { it.name == "AppsFlyer" }
        val sighting = af.hosts.single()
        assertEquals("metrics.leakyshop.example", sighting.host)
        assertEquals("launches.appsflyersdk.com", sighting.viaAlias)
        assertTrue(af.checks.containsAll(listOf(CheckId.C1, CheckId.C11)))
    }

    @Test
    fun `groups survive the bundle's JSON settings`() {
        val g = groups(leaky)
        val json = com.dpdpxray.core.report.AuditJson.json
        val text = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(SdkGroup.serializer()), g)
        assertEquals(g, json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(SdkGroup.serializer()), text))
    }
}
