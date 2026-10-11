package com.dpdpxray.core.law

import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.time.LocalDate

/** Verbatim discipline: law text is marked verbatim only when it was copied from an official source we can name. */
class LensPassagesTest {
    private val lens = LegalLens.loadDefault()

    /** Official publishers per lens: legislatures, official gazettes and journals, and regulators. */
    private val officialHosts = mapOf(
        "EU" to setOf("eur-lex.europa.eu", "www.edpb.europa.eu"),
        "UK" to setOf("www.legislation.gov.uk", "ico.org.uk"),
        "US_CALIFORNIA" to setOf("leginfo.legislature.ca.gov", "cppa.ca.gov"),
        "US_CHILDREN" to setOf("www.ecfr.gov"),
        "BRAZIL" to setOf("www.planalto.gov.br"),
        "SINGAPORE" to setOf("sso.agc.gov.sg", "www.pdpc.gov.sg"),
    )

    @Test
    fun `every verbatim passage names an official https source and the date it was read`() {
        val verbatim = lens.passages.filter { it.verbatim }
        assertTrue("expected verbatim passages", verbatim.size >= 40)
        for (p in verbatim) {
            val url = p.url
            assertTrue("${p.id} has no source URL", !url.isNullOrBlank())
            val uri = URI(url!!)
            assertEquals("${p.id} must use https", "https", uri.scheme)
            val allowed = officialHosts[p.profile].orEmpty()
            assertTrue("${p.id}: ${uri.host} is not an official source for ${p.profile}", uri.host in allowed)
            val read = LocalDate.parse(p.retrieved ?: error("${p.id} has no retrieval date"))
            assertFalse("${p.id} was read in the future", read.isAfter(LocalDate.of(2026, 10, 10)))
        }
    }

    @Test
    fun `summaries are labelled and never claim a source they were not copied from`() {
        lens.passages.filter { !it.verbatim }.forEach { p ->
            assertEquals("${p.id}: only the good-practice baseline is written by TraceVeil", PassageKind.GOOD_PRACTICE, p.kind)
            assertTrue(p.source.contains("not law"))
        }
    }

    @Test
    fun `non-English originals carry an English summary`() {
        val foreign = lens.passages.filter { it.language != "en" }
        assertTrue("the LGPD is quoted in Portuguese", foreign.any { it.profile == "BRAZIL" })
        foreign.forEach { p ->
            assertTrue("${p.id} needs an English summary", !p.englishSummary.isNullOrBlank())
            assertTrue(p.wording.contains("PT original"))
        }
        lens.passages.filter { it.language == "en" }.forEach { assertEquals(null, it.englishSummary) }
    }

    @Test
    fun `regulator guidance and recitals are labelled as such`() {
        val ico = lens.passages.filter { it.url?.contains("ico.org.uk") == true }
        assertTrue(ico.isNotEmpty())
        ico.forEach { assertEquals(it.id, PassageKind.GUIDANCE, it.kind) }
        lens.passages.filter { it.url?.contains("edpb.europa.eu") == true || it.url?.contains("pdpc.gov.sg") == true }
            .forEach { assertEquals(it.id, PassageKind.GUIDANCE, it.kind) }
        lens.passages.filter { it.source.contains("Recital") }.forEach { assertEquals(it.id, PassageKind.RECITAL, it.kind) }
        assertEquals("Regulator guidance, not law", PassageKind.GUIDANCE.label)
    }

    @Test
    fun `key statutory wording is present`() {
        fun text(id: String) = lens.passage(id)!!.text
        assertTrue(text("eu.eprivacy-5(3)").contains("is only allowed on condition that the subscriber or user concerned has given his or her consent"))
        assertTrue(text("eu.gdpr-recital-32").contains("Silence, pre-ticked boxes or inactivity should not therefore constitute consent."))
        assertTrue(text("ca.1798.120(c)").contains("less than 16 years of age"))
        assertTrue(text("coppa.312.5(a)").contains("verifiable parental consent before any collection"))
        assertTrue(text("br.lgpd-8-4").contains("as autorizações genéricas para o tratamento de dados pessoais serão nulas"))
        assertTrue(text("sg.pdpa-13").contains("collect, use or disclose personal data about an individual unless"))
    }

    @Test
    fun `existing DPDP passages are unchanged and not duplicated here`() {
        assertTrue(lens.passages.none { it.profile == "INDIA" })
        val dpdp = com.dpdpxray.core.dpdp.DpdpCorpus.loadDefault()
        assertEquals(17, dpdp.passages.size) // s8(5) (security safeguards, cited by C16) was added after the first 16
        assertTrue(dpdp.byId("s8(5)")!!.text.contains("reasonable security safeguards to prevent personal data breach"))
        assertTrue(dpdp.byId("s6(1)")!!.text.contains("free, specific, informed, unconditional and unambiguous with a clear affirmative action"))
    }

    @Test
    fun `explanations drop India's legal sentences under other lenses but keep the observation`() {
        val engine = RulesEngine(TrackerCatalog.loadDefault())
        val findings = (engine.evaluate(LensFixtures.childFacing).findings + engine.evaluate(LensFixtures.noScreen).findings)
        val checks = findings.map { it.checkId }.toSet()
        assertTrue("fixtures cover C1-C10 and NC: $checks", checks.containsAll(
            listOf(CheckId.C1, CheckId.C2, CheckId.C3, CheckId.C4, CheckId.C5, CheckId.C6, CheckId.C7, CheckId.C8, CheckId.C9, CheckId.C10, CheckId.NC),
        ))
        for (f in findings) {
            assertEquals(f.explanation, LensText.explanation(f, LegalProfile.INDIA).text)
            for (p in LegalProfile.entries.filter { it != LegalProfile.INDIA }) {
                val e = LensText.explanation(f, p)
                assertFalse("${f.checkId} under $p still cites India: ${e.text}", e.citesIndia)
                assertTrue(e.text.isNotBlank())
            }
        }
        val c1 = findings.first { it.checkId == CheckId.C1 }
        assertTrue(LensText.explanation(c1, LegalProfile.EU).text.contains("not proof that personal data was sent"))
    }

    @Test
    fun `an unknown DPDP wording is flagged rather than hidden`() {
        val f = RulesEngine(TrackerCatalog.loadDefault()).evaluate(LensFixtures.deviceLeaky).findings.first()
        val odd = f.copy(explanation = "Something new. DPDP section 99 says so.")
        val e = LensText.explanation(odd, LegalProfile.EU)
        assertTrue(e.citesIndia)
        assertEquals(odd.explanation, e.text)
    }
}
