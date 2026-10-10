package com.dpdpxray.core.copilot

import com.dpdpxray.core.dpdp.Bm25Retriever
import com.dpdpxray.core.dpdp.DpdpCorpus
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.rules.science.LeakyShopShapes
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The copilot's score answer carries the robustness range when inferred findings can move the score. */
class ScoreAnswerRobustnessTest {
    private val corpus = DpdpCorpus.loadDefault()
    private val answerer = GroundedAnswerer(corpus, Bm25Retriever(corpus.passages))
    private val engine = RulesEngine(TrackerCatalog.loadDefault())

    @Test
    fun `score answer adds the range in both languages`() {
        val ev = AuditEvidence(label = "LeakyShop", sample = false, result = engine.evaluate(LeakyShopShapes.rulesOnly))
        val en = answerer.answer(CopilotIntent.SCORE, "score", ev, CopilotLanguage.EN).text
        assertTrue(en, en.contains("It scored 21 out of 100 (At risk)"))
        assertTrue(en, en.contains(" AI and documentation readings can move this between 17 and 25; the band stays At risk."))
        assertNull(VerdictWords.find(en))
        val hi = answerer.answer(CopilotIntent.SCORE, "स्कोर", ev, CopilotLanguage.HI).text
        assertTrue(hi, hi.contains(" AI और दस्तावेज़ों से लिए गए निष्कर्ष इस स्कोर को 17 से 25 के बीच ले जा सकते हैं; श्रेणी जोखिम में ही रहती है।"))
        assertNull(VerdictWords.find(hi))
    }

    @Test
    fun `no range when nothing inferred carries points or the result is inconclusive`() {
        val fixed = AuditEvidence(label = "LeakyShop", sample = false, result = engine.evaluate(LeakyShopShapes.fixed))
        assertFalse(answerer.answer(CopilotIntent.SCORE, "score", fixed, CopilotLanguage.EN).text.contains("readings can move"))
        val quiet = AuditEvidence(label = "LeakyShop", sample = false, result = engine.evaluate(LeakyShopShapes.fixed.map { it.copy(netEvents = emptyList()) }))
        assertFalse(answerer.answer(CopilotIntent.SCORE, "score", quiet, CopilotLanguage.EN).text.contains("readings can move"))
    }
}
