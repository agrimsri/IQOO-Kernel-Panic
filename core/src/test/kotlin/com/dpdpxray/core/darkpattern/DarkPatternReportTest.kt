package com.dpdpxray.core.darkpattern

import com.dpdpxray.core.copilot.VerdictWords
import com.dpdpxray.core.dpdp.CitationService
import com.dpdpxray.core.dpdp.DpdpCorpus
import com.dpdpxray.core.export.AuditBundles
import com.dpdpxray.core.export.BundleApp
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.report.HtmlCoverage
import com.dpdpxray.core.report.HtmlReport
import com.dpdpxray.core.report.MarkdownReport
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** Card copy, report sections and the optional export field for the dark-pattern signals. Test data only. */
class DarkPatternReportTest {
    private val catalog = TrackerCatalog.loadDefault()
    private val engine = RulesEngine(catalog)
    private val citations = CitationService(DpdpCorpus.loadDefault())
    private val leaky = DarkPatternFixtures.leakySessions()
    private val fixed = DarkPatternFixtures.fixedSessions()

    // --- Card and everyday copy ----------------------------------------------------------------------------------------------

    @Test
    fun `card summary counts patterns, says none seen, or says nothing was checked`() {
        assertEquals("Dark-pattern signals (India CCPA 2023)", DarkPatternText.CARD_TITLE)
        assertEquals("4 of 6 checkable patterns seen at the consent moment", DarkPatternText.summary(DarkPatternScanner.scan(leaky), 4))
        assertEquals("None seen in this capture", DarkPatternText.summary(emptyList(), 3))
        assertEquals("Not checked: no consent-screen tree was captured", DarkPatternText.summary(emptyList(), 0))
        val inferredOnly = listOf(DarkPatternSignal(CcpaPattern.NAGGING, listOf("x"), EvidenceType.INFERRED))
        assertEquals("1 of 6 checkable patterns seen at the consent moment (1 from the AI reading only)", DarkPatternText.summary(inferredOnly, 0))
    }

    @Test
    fun `each row says resembles, names its Annexure item and labels its evidence`() {
        val nag = DarkPatternScanner.scan(leaky).single { it.pattern == CcpaPattern.NAGGING }
        assertEquals("Nagging · Annexure 1 #10", DarkPatternText.rowTitle(nag))
        assertEquals("Resembles the pattern described in CCPA 2023 Annexure 1 #10 (Nagging).", DarkPatternText.resembles(nag))
        assertEquals("Observed", DarkPatternText.chip(nag))
        assertEquals("AI-inferred", DarkPatternText.chip(nag.copy(evidenceType = EvidenceType.INFERRED)))
        assertEquals("Plan B · Reject", DarkPatternText.plans(nag))
    }

    @Test
    fun `everyday mode gets one calm line`() {
        assertEquals(
            "Consent popup design: 4 signals resembling India's CCPA dark-pattern list (false urgency, confirm shaming, interface interference, nagging). Not part of the score.",
            DarkPatternText.everydayLine(DarkPatternScanner.scan(leaky), 4),
        )
        assertEquals(
            "Consent popup design: 1 signal resembling India's CCPA dark-pattern list (nagging). Not part of the score.",
            DarkPatternText.everydayLine(listOf(DarkPatternSignal(CcpaPattern.NAGGING, listOf("x"), EvidenceType.OBSERVED)), 1),
        )
        assertEquals("Consent popup design: no dark-pattern signals seen in this capture.", DarkPatternText.everydayLine(emptyList(), 3))
        assertNull(DarkPatternText.everydayLine(emptyList(), 0))
    }

    @Test
    fun `copy uses neutral words only`() {
        val texts = listOf(DarkPatternText.CARD_TITLE, DarkPatternText.FOOTER, DarkPatternText.BASIS, DarkPatternText.AI_NOTE) +
            DarkPatternScanner.scan(leaky).flatMap { listOf(DarkPatternText.resembles(it), DarkPatternText.rowTitle(it)) } +
            listOfNotNull(DarkPatternText.everydayLine(DarkPatternScanner.scan(leaky), 4), DarkPatternText.everydayLine(emptyList(), 3))
        texts.forEach { assertNull(it, VerdictWords.find(it)) }
        texts.forEach { assertFalse(it, it.contains(" is a dark pattern", ignoreCase = true)) }
        assertTrue(DarkPatternText.FOOTER.contains("only guidance"))
        assertTrue(DarkPatternText.FOOTER.contains("not counted in the score"))
    }

    // --- Reports -------------------------------------------------------------------------------------------------------------

    @Test
    fun `markdown report lists the signals in their own section, apart from the findings`() {
        val md = MarkdownReport.build(engine.evaluate(leaky), leaky, emptyList(), citations)
        assertTrue(md, md.contains("## Dark-pattern signals (India CCPA 2023)"))
        assertTrue(md, md.contains("### Nagging · Annexure 1 #10"))
        assertTrue(md, md.contains("*Observed · Plan B · Reject*"))
        assertTrue(md, md.contains("- The consent popup came back 5.2 s after the recorded Reject"))
        assertTrue(md, md.contains("Resembles the pattern described in CCPA 2023 Annexure 1 #3 (Confirm shaming)."))
        assertTrue(md, md.contains(DarkPatternText.FOOTER))
        assertTrue(md, md.indexOf("## Dark-pattern signals") < md.indexOf("## Network timeline"))
        assertTrue(md, md.contains(DarkPatternText.SOURCE_URL))
    }

    @Test
    fun `markdown report says none seen for the fixed build and not checked without a tree`() {
        val md = MarkdownReport.build(engine.evaluate(fixed), fixed, emptyList(), citations)
        assertTrue(md, md.contains("## Dark-pattern signals (India CCPA 2023)\n\nNone seen in this capture."))
        val noTree = fixed.map { s -> s.copy(consentEvents = s.consentEvents.map { it.copy(tree = null) }) }
        assertTrue(MarkdownReport.build(engine.evaluate(noTree), noTree, emptyList(), citations).contains("Not checked: no consent-screen tree was captured."))
    }

    @Test
    fun `html report has a dark-pattern plate between findings and the network table`() {
        val cov = HtmlCoverage(statements = emptyList(), scope = "On this phone.")
        val html = HtmlReport.build(engine.evaluate(leaky), leaky, emptyList(), citations, catalog, cov, ZoneId.of("UTC"))
        assertTrue(html.contains("<section id=\"darkpatterns\""))
        assertTrue(html.contains("href=\"#darkpatterns\""))
        assertTrue(html.contains("Nagging · Annexure 1 #10"))
        assertTrue(html.contains("<span class=\"chip ev-observed\">Observed</span>"))
        assertTrue(html.indexOf("id=\"findings\"") < html.indexOf("id=\"darkpatterns\""))
        assertTrue(html.indexOf("id=\"darkpatterns\"") < html.indexOf("id=\"network\""))
        assertTrue(html.contains("&quot;No, I prefer paying more&quot;"))
        val none = HtmlReport.build(engine.evaluate(fixed), fixed, emptyList(), citations, catalog, cov, ZoneId.of("UTC"))
        assertTrue(none.contains("None seen in this capture."))
    }

    // --- Export ---------------------------------------------------------------------------------------------------------------

    private fun bundle() = AuditBundles.audit(
        app = BundleApp("com.leakyshop.demo", "LeakyShop"), meta = JsonObject(emptyMap()), sessions = leaky, result = engine.evaluate(leaky),
        networkReadings = null, toolVersion = "1.1.0", exportedAt = "2026-10-11T08:00:00Z",
    )

    @Test
    fun `export field is optional, written only when set, and round-trips`() {
        val plain = AuditBundles.encode(bundle())
        assertFalse("darkPatterns" in Json.parseToJsonElement(plain).jsonObject)
        val export = DarkPatternExport.of(leaky)
        assertEquals(4, export.screensChecked)
        assertEquals(DarkPatternText.BASIS, export.basis)
        val withSignals = bundle().copy(darkPatterns = export)
        val obj = Json.parseToJsonElement(AuditBundles.encode(withSignals)).jsonObject
        assertEquals(listOf("schema", "exportedAt", "app", "meta", "sessions", "result", "networkReadings", "tool", "darkPatterns"), obj.keys.toList())
        val first = obj["darkPatterns"]!!.jsonObject["signals"]!!.jsonArray[0].jsonObject
        assertEquals("FALSE_URGENCY", first["pattern"]!!.jsonPrimitive.content)
        assertEquals("OBSERVED", first["evidenceType"]!!.jsonPrimitive.content)
        assertEquals("CCPA India 2023 Annexure 1 #1", first["source"]!!.jsonPrimitive.content)
        assertEquals(withSignals, AuditBundles.decode(AuditBundles.encode(withSignals)))
        // A reader that predates the field gets the same evidence and result.
        assertEquals(bundle(), AuditBundles.decode(AuditBundles.encode(withSignals)).copy(darkPatterns = null))
    }

    @Test
    fun `an empty export still says what was checked`() {
        val export = DarkPatternExport.of(fixed)
        assertEquals(emptyList<DarkPatternSignal>(), export.signals)
        assertEquals(3, export.screensChecked)
        assertEquals(listOf(AuditPlan.REJECT), DarkPatternExport.of(leaky).signals.single { it.pattern == CcpaPattern.NAGGING }.plans)
    }
}
