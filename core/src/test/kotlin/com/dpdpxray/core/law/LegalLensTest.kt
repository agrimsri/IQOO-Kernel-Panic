package com.dpdpxray.core.law

import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.Finding
import com.dpdpxray.core.model.PenaltyBand
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.Severity
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegalLensTest {
    private val lens = LegalLens.loadDefault()
    private val engine = RulesEngine(TrackerCatalog.loadDefault())

    /** The checks every profile must map explicitly (later checks may use the fallback until they are mapped). */
    private val core = listOf("C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8", "C9", "C10", "NC", "U1", "S1", "V0")

    private fun finding(id: CheckId, sev: Severity, ev: EvidenceType = EvidenceType.OBSERVED) = Finding(
        checkId = id, severity = sev, evidenceType = ev, title = id.title, explanation = "", evidence = emptyList(),
        plans = listOf(AuditPlan.ACCEPT), dpdpRefs = emptyList(), penaltyBand = PenaltyBand.NONE,
    )

    @Test
    fun `every profile has data and every data profile is a known lens`() {
        assertEquals(LegalProfile.entries.toList(), lens.profiles)
        LegalProfile.entries.forEach { assertEquals(it.name, lens.profile(it).id) }
    }

    @Test
    fun `every profile maps C1 to C10, NC, U1, S1 and V0 with a note`() {
        for (p in LegalProfile.entries) for (code in core) {
            assertTrue("$p has no entry for $code", lens.isMapped(p, code))
            assertTrue("$p $code note is empty", lens.mapping(p, code).note.isNotBlank())
        }
    }

    @Test
    fun `mapping keys are check codes that exist`() {
        val codes = CheckId.entries.map { it.code }.toSet()
        for (p in LegalProfile.entries) {
            val unknown = lens.profile(p).checks.keys - codes
            assertTrue("$p maps unknown checks $unknown", unknown.isEmpty())
        }
    }

    @Test
    fun `a check without an entry falls back to the profile's generic context note`() {
        for (p in LegalProfile.entries) {
            val m = lens.mapping(p, "C99")
            assertFalse(lens.isMapped(p, "C99"))
            assertEquals(LawRelation.CONTEXT, m.relation)
            assertTrue(m.note.isNotBlank())
            assertTrue(m.refs.isEmpty())
        }
    }

    @Test
    fun `every reference resolves to a passage of the same profile`() {
        for (p in LegalProfile.entries) {
            val prof = lens.profile(p)
            for ((code, m) in prof.checks + ("fallback" to prof.fallback)) for (ref in m.refs) {
                val passage = lens.passage(ref)
                assertTrue("$p $code refers to missing passage $ref", passage != null)
                assertEquals("$ref belongs to another profile", p.name, passage!!.profile)
            }
        }
        val ids = lens.passages.map { it.id }
        assertEquals("passage ids are unique", ids.size, ids.toSet().size)
    }

    @Test
    fun `india keeps the DPDP corpus and requires every consent check`() {
        val india = lens.profile(LegalProfile.INDIA)
        assertEquals(PassageSource.DPDP_CORPUS, india.passageSource)
        assertTrue(india.penaltyFigures)
        for (code in listOf("C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8", "C9", "C10", "NC")) {
            assertEquals(code, LawRelation.REQUIRED, lens.mapping(LegalProfile.INDIA, code).relation)
        }
        assertTrue(india.status.contains("13 May 2027"))
    }

    @Test
    fun `only india shows penalty figures`() {
        LegalProfile.entries.filter { it != LegalProfile.INDIA }.forEach { p ->
            assertFalse("$p must not show penalty figures", lens.profile(p).penaltyFigures)
            val text = lens.profile(p).let { it.limitations + it.status + it.checks.values.map { m -> m.note } }.joinToString()
            assertFalse("$p mentions rupees", text.contains("₹") || text.contains("crore"))
        }
    }

    @Test
    fun `california is an opt-out model`() {
        fun r(code: String) = lens.mapping(LegalProfile.US_CALIFORNIA, code).relation
        assertEquals(LawRelation.CONTEXT, r("C1"))
        assertEquals(LawRelation.REQUIRED, r("C2"))
        assertEquals(LawRelation.REQUIRED, r("C9"))
        assertEquals(LawRelation.CONTEXT, r("C10"))
        assertEquals(LawRelation.CONTEXT, r("NC"))
        val c1 = lens.mapping(LegalProfile.US_CALIFORNIA, "C1").note
        assertTrue(c1.contains("opt out") || c1.contains("Opt-out"))
        assertTrue(c1.contains("Global Privacy Control"))
        assertTrue(lens.mapping(LegalProfile.US_CALIFORNIA, "C9").note.contains("under 16"))
        assertTrue(lens.mapping(LegalProfile.US_CALIFORNIA, "C1").refs.contains("ca.reg-7025"))
    }

    @Test
    fun `ePrivacy and PECR require prior consent for device access`() {
        assertEquals(LawRelation.REQUIRED, lens.mapping(LegalProfile.EU, "C1").relation)
        assertTrue(lens.mapping(LegalProfile.EU, "C1").refs.contains("eu.eprivacy-5(3)"))
        assertEquals(LawRelation.REQUIRED, lens.mapping(LegalProfile.UK, "C1").relation)
        assertTrue(lens.mapping(LegalProfile.UK, "C1").note.contains("exception"))
        assertEquals(LawRelation.NOT_APPLICABLE, lens.mapping(LegalProfile.UK, "C8").relation)
        assertEquals(LawRelation.CONTEXT, lens.mapping(LegalProfile.EU, "C8").relation)
    }

    @Test
    fun `coppa is about children only`() {
        assertEquals(LawRelation.REQUIRED, lens.mapping(LegalProfile.US_CHILDREN, "C9").relation)
        assertEquals(LawRelation.CONTEXT, lens.mapping(LegalProfile.US_CHILDREN, "C1").relation)
        listOf("C3", "C4", "C8").forEach { assertEquals(it, LawRelation.NOT_APPLICABLE, lens.mapping(LegalProfile.US_CHILDREN, it).relation) }
    }

    @Test
    fun `the global baseline is good practice, never law`() {
        val b = lens.profile(LegalProfile.GLOBAL_BASELINE)
        assertEquals(ProfileKind.GOOD_PRACTICE, b.kind)
        assertEquals("Good practice", b.relationLabel(LawRelation.REQUIRED))
        assertEquals("Required", lens.profile(LegalProfile.EU).relationLabel(LawRelation.REQUIRED))
        lens.passages.filter { it.profile == "GLOBAL_BASELINE" }.forEach {
            assertEquals(PassageKind.GOOD_PRACTICE, it.kind)
            assertFalse(it.verbatim)
        }
        assertTrue(lens.passages.filter { it.kind == PassageKind.GOOD_PRACTICE }.all { it.profile == "GLOBAL_BASELINE" })
        assertTrue(b.checks.values.none { it.note.contains("law requires", ignoreCase = true) })
    }

    @Test
    fun `V0 keeps every lens score inconclusive`() {
        val r = AuditResult(listOf(finding(CheckId.V0, Severity.INFO), finding(CheckId.C8, Severity.LOW)), null, ScoreBand.INCONCLUSIVE)
        for (p in LegalProfile.entries) {
            val v = lens.view(r, p)
            assertNull(v.score)
            assertEquals(ScoreBand.INCONCLUSIVE, v.band)
            assertTrue(v.scoreLine.endsWith("inconclusive"))
        }
    }

    @Test
    fun `score under a lens skips only not-applicable findings`() {
        val findings = listOf(
            finding(CheckId.C2, Severity.CRITICAL), finding(CheckId.C3, Severity.HIGH), finding(CheckId.C4, Severity.HIGH),
            finding(CheckId.C8, Severity.LOW), finding(CheckId.C10, Severity.MEDIUM, EvidenceType.INFERRED),
        )
        val r = AuditResult(findings, 26, ScoreBand.AT_RISK)
        // 100 - 30 - 15 - 15 - 3 - 4 = 33 with everything counted; COPPA skips C3, C4 and C8; the UK skips C8.
        assertEquals(33, lens.view(r, LegalProfile.EU).score)
        assertEquals(33, lens.view(r, LegalProfile.INDIA).score)
        assertEquals(36, lens.view(r, LegalProfile.UK).score)
        assertEquals(66, lens.view(r, LegalProfile.US_CHILDREN).score)
        assertEquals(ScoreBand.NEEDS_WORK, lens.view(r, LegalProfile.US_CHILDREN).band)
        val coppa = lens.view(r, LegalProfile.US_CHILDREN)
        assertEquals(listOf(CheckId.C3, CheckId.C4, CheckId.C8), coppa.notApplicable.map { it.finding.checkId })
        assertTrue(coppa.scoreNote.contains("skips 3 not applicable here (C3, C4, C8)"))
        assertTrue(coppa.scoreNote.contains("not a legal verdict"))
        // CONTEXT findings still count: California keeps C10 and C3/C4/C8 as context.
        assertEquals(33, lens.view(r, LegalProfile.US_CALIFORNIA).score)
    }

    @Test
    fun `leakyshop keeps 21 and 100 under the default standard and every lens reads the same findings`() {
        val leaky = engine.evaluate(LensFixtures.deviceLeaky)
        val fixed = engine.evaluate(LensFixtures.deviceFixed)
        assertEquals(21, leaky.score)
        assertEquals(100, fixed.score)
        assertEquals(ScoreBand.READY, fixed.band)
        for (p in LegalProfile.entries) {
            val v = lens.view(leaky, p)
            assertEquals(leaky.findings, v.findings.map { it.finding })
            assertEquals(100, lens.view(fixed, p).score)
        }
        assertEquals(21, lens.view(leaky, LegalProfile.INDIA).score)
        assertEquals(21, lens.view(leaky, LegalProfile.EU).score)
        assertEquals(21, lens.view(leaky, LegalProfile.GLOBAL_BASELINE).score)
        // COPPA does not test banner design: C3 and C4 (High, observed) are skipped.
        assertEquals(51, lens.view(leaky, LegalProfile.US_CHILDREN).score)
    }

    @Test
    fun `the full leaky sample moves only where a lens marks a check not applicable`() {
        val r = engine.evaluate(LensFixtures.leaky)
        assertEquals(8, r.score)
        assertEquals(8, lens.view(r, LegalProfile.EU).score)
        assertEquals(9, lens.view(r, LegalProfile.UK).score)
        assertEquals(9, lens.view(r, LegalProfile.SINGAPORE).score)
        assertEquals(39, lens.view(r, LegalProfile.US_CHILDREN).score)
    }

    @Test
    fun `india passages come from each finding's own DPDP references`() {
        val r = engine.evaluate(LensFixtures.deviceLeaky)
        val c1 = r.findings.first { it.checkId == CheckId.C1 }
        val citations = com.dpdpxray.core.dpdp.CitationService(com.dpdpxray.core.dpdp.DpdpCorpus.loadDefault())
        assertEquals(listOf("s4(1)", "s6(1)"), lens.passagesFor(LegalProfile.INDIA, c1, citations).map { it.id })
        val eu = lens.passagesFor(LegalProfile.EU, c1, citations)
        assertEquals("eu.eprivacy-5(3)", eu.first().id)
        assertTrue(eu.all { it.profile == "EU" })
    }

    @Test
    fun `compare lists every lens for a check`() {
        val c = lens.compare("C1")
        assertEquals(LegalProfile.entries.toList(), c.map { it.first })
        assertEquals(LawRelation.CONTEXT, c.toMap()[LegalProfile.US_CALIFORNIA]!!.relation)
    }

    @Test
    fun `profile ids parse leniently and unknown ones are rejected`() {
        assertEquals(LegalProfile.EU, LegalProfile.parse("eu"))
        assertEquals(LegalProfile.US_CALIFORNIA, LegalProfile.parse("us-california"))
        assertEquals(LegalProfile.US_CHILDREN, LegalProfile.parse(" US_CHILDREN "))
        assertEquals(LegalProfile.GLOBAL_BASELINE, LegalProfile.parse("global-baseline"))
        assertNull(LegalProfile.parse("gdpr"))
        assertNull(LegalProfile.parse(""))
        assertNull(LegalProfile.parse(null))
        assertEquals(LegalProfile.GLOBAL_BASELINE, LegalProfile.DEFAULT)
    }

    @Test
    fun `device region picks the lens, else the global baseline`() {
        assertEquals(LegalProfile.INDIA, LensRegions.forRegion("IN", lens))
        assertEquals(LegalProfile.EU, LensRegions.forRegion("de", lens))
        assertEquals(LegalProfile.EU, LensRegions.forRegion("NO", lens))
        assertEquals(LegalProfile.UK, LensRegions.forRegion("GB", lens))
        assertEquals(LegalProfile.BRAZIL, LensRegions.forRegion("BR", lens))
        assertEquals(LegalProfile.SINGAPORE, LensRegions.forRegion("SG", lens))
        // No single US law covers every app, so the US stays on the baseline with a hint.
        assertEquals(LegalProfile.GLOBAL_BASELINE, LensRegions.forRegion("US", lens))
        assertTrue(LensRegions.hint("US")!!.contains("California"))
        for (cc in listOf(null, "", "XX", "JP", "419")) assertEquals(LegalProfile.GLOBAL_BASELINE, LensRegions.forRegion(cc, lens))
        val all = LegalProfile.entries.flatMap { lens.profile(it).regions }
        assertEquals("a region belongs to one lens", all.size, all.toSet().size)
    }

    @Test
    fun `band label is neutral`() {
        assertEquals("Ready", ScoreBand.READY.label)
        ScoreBand.entries.forEach { assertFalse(it.label.contains("DPDP")) }
    }
}
