package com.dpdpxray.core.law

import com.dpdpxray.core.dpdp.CitationService
import com.dpdpxray.core.dpdp.DpdpCorpus
import com.dpdpxray.core.export.AuditBundles
import com.dpdpxray.core.export.BundleApp
import com.dpdpxray.core.fixes.FixCatalog
import com.dpdpxray.core.report.HtmlCoverage
import com.dpdpxray.core.report.HtmlReport
import com.dpdpxray.core.report.MarkdownReport
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** The lens appears on the Markdown/PDF, HTML and JSON exports; only India shows penalty ceilings. */
class LensReportTest {
    private val catalog = TrackerCatalog.loadDefault()
    private val citations = CitationService(DpdpCorpus.loadDefault())
    private val sessions = LensFixtures.deviceLeaky
    private val result = RulesEngine(catalog).evaluate(sessions)
    private val fixes = FixCatalog.loadDefault().fixesFor(result, catalog)
    private val coverage = HtmlCoverage(statements = emptyList(), scope = "On this phone.")

    private fun md(p: LegalProfile) = MarkdownReport.build(result, sessions, fixes, citations, p)
    private fun html(p: LegalProfile) = HtmlReport.build(result, sessions, fixes, citations, catalog, coverage, ZoneId.of("UTC"), p)

    @Test
    fun `india markdown keeps the DPDP basis and penalty ceilings`() {
        val m = md(LegalProfile.INDIA)
        assertTrue(m.contains("Score: 21/100 · At risk"))
        assertTrue(m.contains("Legal lens: India · Digital Personal Data Protection Act 2023 and DPDP Rules 2025 (`INDIA`)"))
        assertTrue(m.contains("| Check | Finding | Severity | Evidence | Under this lens | Penalty ceiling |"))
        assertTrue(m.contains("**DPDP basis**"))
        assertTrue(m.contains("Up to ₹50 crore"))
        assertTrue(m.contains("legitimate use (DPDP s.7)"))
        assertTrue(m.contains("Consent readiness out of 100"))
    }

    @Test
    fun `other lenses quote their own law and show no penalty figure`() {
        for (p in LegalProfile.entries.filter { it != LegalProfile.INDIA }) {
            val m = md(p)
            assertFalse("$p markdown shows rupees", m.contains("₹") || m.contains("crore"))
            assertFalse("$p markdown has a penalty column", m.contains("Penalty ceiling"))
            assertFalse("$p markdown labels the band DPDP-ready", m.contains("DPDP-ready"))
            assertTrue(m.contains("(`${p.name}`)"))
            assertTrue(m.contains("Score under the ${LegalLens.default.profile(p).name} lens:"))
        }
        val eu = md(LegalProfile.EU)
        assertTrue(eu.contains("**Under EU / EEA: Required.**"))
        assertTrue(eu.contains("is only allowed on condition that the subscriber or user concerned has given his or her consent"))
        assertTrue(eu.contains("https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32009L0136, read 2026-10-10"))
        assertFalse("EU explanations drop the DPDP sentence", eu.contains("Under the DPDP Act"))
        val ca = md(LegalProfile.US_CALIFORNIA)
        assertTrue(ca.contains("**Under California (US): Context.**"))
        assertTrue(ca.contains("| C2 | Tracker hosts looked up after the user refused | Critical | observed | Required |"))
        val br = md(LegalProfile.BRAZIL)
        assertTrue(br.contains("English summary (not an official translation)"))
        val baseline = md(LegalProfile.GLOBAL_BASELINE)
        assertTrue(baseline.contains("**Under Global baseline: Good practice.**"))
        assertTrue(baseline.contains("(good practice, not law) (summary) · Good practice, not law"))
    }

    @Test
    fun `coppa skips banner checks in its lens score`() {
        assertTrue(md(LegalProfile.US_CHILDREN).contains("Score under the US children (COPPA) lens: 51/100 · At risk"))
        assertTrue(md(LegalProfile.US_CHILDREN).contains("skips 2 not applicable here (C3, C4)"))
    }

    @Test
    fun `html shows the lens, relation chips, a compare fold and the neutral score label`() {
        val eu = html(LegalProfile.EU)
        assertTrue(eu.contains("Consent readiness out of 100"))
        assertFalse(eu.contains("DPDP readiness"))
        assertTrue(eu.contains("data-lens=\"EU\""))
        assertTrue(eu.contains("<dt>Legal lens</dt><dd>EU / EEA · GDPR"))
        assertTrue(eu.contains("class=\"chip rel-required\""))
        assertTrue(eu.contains("<summary>Compare laws</summary>"))
        assertTrue(eu.contains("href=\"https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32009L0136\""))
        assertTrue(eu.contains(HtmlReport.LENS_NOTE))
        assertFalse(eu.contains("₹") || eu.contains("crore"))
        val india = html(LegalProfile.INDIA)
        assertTrue(india.contains("main provisions are scheduled to commence on 13 May 2027"))
        assertTrue(india.contains("DPDP Act 2023, Section 4(1)"))
        assertTrue(html(LegalProfile.BRAZIL).contains("<blockquote lang=\"pt\">"))
        assertTrue(eu.length < HtmlReport.SIZE_BUDGET_BYTES)
    }

    @Test
    fun `audit bundle carries the lens id only when set`() {
        val plain = AuditBundles.audit(BundleApp("com.leakyshop.demo", "LeakyShop"), JsonObject(emptyMap()), sessions, result, null, "test", "2026-10-10T00:00:00Z")
        assertFalse(AuditBundles.encode(plain).contains("legalProfile"))
        val eu = plain.copy(legalProfile = LegalProfile.EU.name)
        val text = AuditBundles.encode(eu)
        assertTrue(text.contains("\"legalProfile\": \"EU\""))
        assertEquals(eu, AuditBundles.decode(text))
        assertEquals(null, AuditBundles.decode(AuditBundles.encode(plain)).legalProfile)
    }
}
