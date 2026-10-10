package com.dpdpxray.core.report

import com.dpdpxray.core.dpdp.CitationService
import com.dpdpxray.core.dpdp.DpdpCorpus
import com.dpdpxray.core.fixes.FixCatalog
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.AuditSource
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.RejectOption
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.ScreenAudit
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class HtmlReportTest {
    private val catalog = TrackerCatalog.loadDefault()
    private val citations = CitationService(DpdpCorpus.loadDefault())
    private val fixCatalog = FixCatalog.loadDefault()
    private val s = 1_000_000_000L
    private val utc = ZoneId.of("UTC")
    private val coverage = HtmlCoverage(
        statements = listOf("Plan B never reached refusal; C2 not tested."),
        scope = "Audit analysis and report generation run on this phone.",
    )

    private val screen = ScreenAudit(
        isConsentScreen = true, preTicked = true, rejectOption = RejectOption.HIDDEN, purposesCount = 3,
        separateChoices = false, itemisedData = false, purposeStated = true, withdrawalInfo = false,
        languageOptions = listOf("English"), source = AuditSource.MERGED, observedFields = setOf("preTicked", "rejectOption"),
    )

    private fun session(plan: AuditPlan, events: List<NetEvent>, consent: List<ConsentEvent>) = AuditSession(
        id = "s-${plan.name}", targetPackage = "com.leakyshop.demo", targetLabel = "LeakyShop", plan = plan,
        startedAtEpochMs = 1_791_590_915_883, endedAtNanos = 30 * s, netEvents = events, consentEvents = consent,
        screenAudit = screen, deviceModel = "Test device", androidVersion = "16",
    )

    private val sessions = listOf(
        session(
            AuditPlan.REJECT,
            listOf(
                NetEvent(1 * s, "app-measurement.com"), NetEvent(2 * s, "graph.facebook.com"),
                NetEvent(9 * s, "graph.facebook.com"), NetEvent(11 * s, "launches.appsflyersdk.com"),
                NetEvent(12 * s, "api.leakyshop.example"),
                NetEvent(13 * s, "metrics.leakyshop.example", cnameChain = listOf("metrics.leakyshop.example", "graph.facebook.com")),
            ),
            listOf(ConsentEvent(2 * s, ConsentKind.SCREEN_APPEARED), ConsentEvent(8 * s, ConsentKind.REJECT, "Reject all")),
        ),
        session(
            AuditPlan.ACCEPT,
            listOf(NetEvent(1 * s, "app-measurement.com"), NetEvent(6 * s, "googleads.g.doubleclick.net"), NetEvent(7 * s, "cdn.leakyshop.example")),
            listOf(ConsentEvent(2 * s, ConsentKind.SCREEN_APPEARED), ConsentEvent(5 * s, ConsentKind.ACCEPT, "Accept & Continue")),
        ),
    )

    private fun build(
        list: List<AuditSession> = sessions,
        cov: HtmlCoverage = coverage,
    ): String {
        val result = RulesEngine(catalog).evaluate(list)
        return HtmlReport.build(result, list, fixCatalog.fixesFor(result, catalog), citations, catalog, cov, utc)
    }

    private fun withoutScripts(html: String) = html
        .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("<style>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")

    private fun count(text: String, regex: String) = Regex(regex).findAll(text).count()

    private fun graph(html: String): JsonObject {
        val raw = Regex("<script type=\"application/json\" id=\"tv-graph\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html)
        assertNotNull("graph data block present", raw)
        return Json.parseToJsonElement(raw!!.groupValues[1]).jsonObject
    }

    @Test
    fun `document is well formed enough - key tags balance`() {
        val html = build()
        assertTrue(html.startsWith("<!doctype html>"))
        assertTrue(html.trimEnd().endsWith("</html>"))
        val body = withoutScripts(html)
        for (tag in listOf("section", "article", "details", "summary", "svg", "table", "thead", "tbody", "tr", "ul", "li", "div", "p", "span", "a", "g", "text", "header", "main", "footer", "nav", "blockquote", "pre", "code", "select", "label", "dl", "dt", "dd", "aside", "h1", "h2", "h3", "h4")) {
            assertEquals("<$tag> open/close balance", count(body, "<$tag[\\s>]"), count(body, "</$tag>"))
        }
        assertEquals(count(html, "<script[\\s>]"), count(html, "</script>"))
        assertEquals(1, count(html, "<style>"))
    }

    @Test
    fun `every finding has its own card`() {
        val result = RulesEngine(catalog).evaluate(sessions)
        assertTrue("fixture should raise several findings", result.findings.size >= 3)
        val html = build()
        for (f in result.findings) assertTrue("card for ${f.checkId.code}", html.contains("id=\"finding-${f.checkId.code}\""))
        assertTrue(html.contains("Copy"))
        assertTrue("fix code is present", html.contains("AutoInitEnabled"))
        assertTrue("law passages are present", html.contains("clear affirmative action"))
    }

    @Test
    fun `coverage and scope come before the score and findings`() {
        val html = build()
        val coverageAt = html.indexOf("Audit coverage</h2>")
        val statementAt = html.indexOf(coverage.statements.single())
        val scopeAt = html.indexOf(coverage.scope)
        val heroAt = html.indexOf("class=\"hero\"")
        val firstFinding = html.indexOf("id=\"finding-")
        assertTrue(coverageAt in 1 until heroAt)
        assertTrue(statementAt in coverageAt until heroAt)
        assertTrue(scopeAt in coverageAt until heroAt)
        assertTrue(heroAt < firstFinding)
        assertTrue(html.contains("not legal advice"))
    }

    @Test
    fun `nothing is fetched from the network`() {
        val html = build()
        assertTrue(html.contains("Content-Security-Policy\" content=\"default-src 'none'"))
        assertFalse(Regex("<script[^>]*\\ssrc=", RegexOption.IGNORE_CASE).containsMatchIn(html))
        assertFalse(Regex("<link[\\s>]", RegexOption.IGNORE_CASE).containsMatchIn(html))
        assertFalse(Regex("<(img|iframe|object|embed|audio|video|source)[\\s>]", RegexOption.IGNORE_CASE).containsMatchIn(html))
        assertFalse(html.contains("@import"))
        assertFalse(Regex("url\\(\\s*['\"]?(https?:)?//", RegexOption.IGNORE_CASE).containsMatchIn(html))
        assertFalse(Regex("\\bfetch\\(|XMLHttpRequest|WebSocket|EventSource|sendBeacon").containsMatchIn(html))
        // Remaining web addresses may only be cited sources: plain anchors, or a source link in the graph data.
        val stripped = html
            .replace(Regex("<a\\s[^>]*>.*?</a>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<script type=\"application/json\" id=\"tv-graph\">.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
        assertFalse(stripped.contains("http://"))
        assertFalse(stripped.contains("https://"))
    }

    @Test
    fun `host names, labels and consent text cannot inject markup`() {
        val evil = "<script>alert(1)</script>.evil.example"
        val list = listOf(
            sessions.first().copy(
                targetLabel = "</title><script>alert('t')</script>",
                netEvents = sessions.first().netEvents + NetEvent(14 * s, evil, cnameChain = listOf(evil, "x\"><b>.example")),
                consentEvents = sessions.first().consentEvents + ConsentEvent(15 * s, ConsentKind.SETTINGS, "\"><img src=x onerror=alert(2)>"),
            ),
        )
        val html = build(list)
        assertFalse(html.contains("<script>alert"))
        assertFalse(html.contains("<img"))
        assertFalse(html.contains("<b>.example"))
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;.evil.example"))
        assertTrue("JSON escapes angle brackets", html.contains("\\u003cscript\\u003ealert(1)"))
        assertEquals("one inline script plus the data block", 2, count(html, "<script[\\s>]"))
        // The escaped host still round-trips through the graph data unchanged.
        val hosts = graph(html)["nodes"]!!.jsonArray.map { it.jsonObject }.filter { it["k"]!!.jsonPrimitive.content == "h" }
            .map { it["label"]!!.jsonPrimitive.content }
        assertTrue(hosts.contains(evil))
    }

    @Test
    fun `graph contains only hosts and entities from the sessions`() {
        val html = build()
        val g = graph(html)
        val nodes = g["nodes"]!!.jsonArray.map { it.jsonObject }
        val recorded = sessions.flatMap { it.netEvents }.map { it.hostname.lowercase().trimEnd('.') }.toSet()
        val hostNodes = nodes.filter { it["k"]!!.jsonPrimitive.content == "h" }
        assertEquals("every recorded host appears exactly once", recorded, hostNodes.map { it["label"]!!.jsonPrimitive.content }.toSet())
        assertEquals(recorded.size, hostNodes.size)
        val entities = sessions.flatMap { it.netEvents }.mapNotNull { catalog.classify(it.hostname, it.cnameChain).entity }.toSet()
        val entityNodes = nodes.filter { it["k"]!!.jsonPrimitive.content == "e" }.map { it["label"]!!.jsonPrimitive.content }.toSet()
        assertEquals(entities, entityNodes)
        assertTrue("only hosts and entities are nodes", nodes.all { it["k"]!!.jsonPrimitive.content in setOf("h", "e") })
        val unclassified = hostNodes.single { it["label"]!!.jsonPrimitive.content == "api.leakyshop.example" }
        assertFalse("unclassified hosts carry no invented entity", unclassified.containsKey("entity"))
        assertEquals("Unclassified", unclassified["cat"]!!.jsonPrimitive.content)
        val ids = nodes.map { it["id"]!!.jsonPrimitive.content }.toSet() + "app"
        for (e in g["edges"]!!.jsonArray.map { it.jsonObject }) {
            assertTrue(e["s"]!!.jsonPrimitive.content in ids && e["t"]!!.jsonPrimitive.content in ids)
        }
        val alias = hostNodes.single { it["label"]!!.jsonPrimitive.content == "metrics.leakyshop.example" }
        assertEquals("Meta (Facebook SDK)", alias["entity"]!!.jsonPrimitive.content)
        val aliasEdge = g["edges"]!!.jsonArray.map { it.jsonObject }.single { it["s"]!!.jsonPrimitive.content == alias["id"]!!.jsonPrimitive.content }
        assertTrue(aliasEdge["alias"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `timeline marks the recorded choice and colours trackers by phase`() {
        val html = build()
        assertTrue(html.contains("REJECT · 8.0 s"))
        assertTrue(html.contains("ACCEPT · 5.0 s"))
        assertTrue(html.contains("Reject recorded at 8.0 s · button “Reject all”"))
        // graph.facebook.com at 9 s in the Reject plan is after the refusal.
        assertTrue(Regex("data-h=\"graph.facebook.com\"[^>]*data-t=\"9.0\" data-p=\"AFTER_REJECT\"").containsMatchIn(html))
        assertTrue(Regex("data-h=\"app-measurement.com\"[^>]*data-p=\"BEFORE_CONSENT\"").containsMatchIn(html))
        assertTrue(html.contains("graph.facebook.com → metrics") || html.contains("metrics.leakyshop.example → graph.facebook.com"))
    }

    @Test
    fun `missing choice stays unknown and no penalty figures appear`() {
        val noChoice = listOf(sessions.first().copy(consentEvents = listOf(ConsentEvent(2 * s, ConsentKind.SCREEN_APPEARED))))
        val html = build(noChoice)
        assertTrue(html.contains("no Accept or Reject was recorded; the choice is unknown"))
        assertFalse(html.contains("₹"))
        assertFalse(html.contains("crore"))
    }

    @Test
    fun `inconclusive result shows no score arc`() {
        val quiet = listOf(sessions.first().copy(netEvents = listOf(NetEvent(1 * s, "a.example")), screenAudit = null))
        val result = RulesEngine(catalog).evaluate(quiet)
        assertEquals(ScoreBand.INCONCLUSIVE, result.band)
        val html = HtmlReport.build(result, quiet, emptyList(), citations, catalog, coverage, utc)
        assertTrue(html.contains("Score inconclusive"))
        assertFalse(html.contains("class=\"dial-arc\""))
    }

    @Test
    fun `sample report says so before anything else and does not grade confidence`() {
        val html = build(cov = coverage.copy(sample = true, sampleNotes = listOf("Sample audit generated in-app.")))
        val banner = html.indexOf(HtmlReport.SAMPLE_BANNER)
        assertTrue(banner in 1 until html.indexOf("class=\"hero\""))
        assertTrue(html.contains("Sample audit generated in-app."))
        assertTrue(html.contains("capture confidence was not measured"))
        assertFalse(html.contains("Evidence confidence:"))
    }

    @Test
    fun `a 200-lookup audit stays within the size budget`() {
        val hosts = listOf(
            "app-measurement.com", "graph.facebook.com", "launches.appsflyersdk.com", "googleads.g.doubleclick.net",
            "firebase-settings.crashlytics.com", "in1.clevertap-prod.com", "api.leakyshop.example", "cdn.leakyshop.example",
        )
        val plans = AuditPlan.entries
        val big = plans.mapIndexed { p, plan ->
            val n = if (p == 0) 68 else 66
            session(
                plan,
                (0 until n).map { i -> NetEvent((i * 0.4 * s).toLong() + s / 3, if (i % 5 == 4) "edge-$p-$i.cdn-host.example" else hosts[(i + p) % hosts.size]) },
                listOf(ConsentEvent(2 * s, ConsentKind.SCREEN_APPEARED)) +
                    if (plan == AuditPlan.SILENT) emptyList() else listOf(ConsentEvent(6 * s, if (plan == AuditPlan.ACCEPT) ConsentKind.ACCEPT else ConsentKind.REJECT, "Choice")),
            )
        }
        assertEquals(200, big.sumOf { it.netEvents.size })
        val html = build(big)
        val bytes = html.toByteArray(Charsets.UTF_8).size
        assertTrue("HTML is $bytes bytes, budget ${HtmlReport.SIZE_BUDGET_BYTES}", bytes < HtmlReport.SIZE_BUDGET_BYTES)
        assertEquals(200, count(html, "<tr data-lane="))
    }

    @Test
    fun `marks that share a moment are stacked onto separate levels`() {
        assertEquals(listOf(0, -1, 1, 0), HtmlReport.stack(listOf(10.0, 10.0, 12.0, 30.0), 6.0, 2))
    }
}
