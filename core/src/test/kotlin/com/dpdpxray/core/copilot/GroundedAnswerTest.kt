package com.dpdpxray.core.copilot

import com.dpdpxray.core.dpdp.Bm25Retriever
import com.dpdpxray.core.dpdp.DpdpCorpus
import com.dpdpxray.core.dpdp.Passage
import com.dpdpxray.core.dpdp.PassageRetriever
import com.dpdpxray.core.dpdp.ScoredPassage
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.Finding
import com.dpdpxray.core.model.PenaltyBand
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundedAnswerTest {
    private val corpus = DpdpCorpus.loadDefault()
    private val answerer = GroundedAnswerer(corpus, Bm25Retriever(corpus.passages))

    private fun finding(id: CheckId, entities: List<String> = emptyList(), refs: List<String> = emptyList()) = Finding(
        checkId = id, severity = Severity.HIGH, evidenceType = EvidenceType.OBSERVED, title = id.title,
        explanation = "${id.title}.", evidence = listOf("evidence for ${id.code}"), plans = listOf(AuditPlan.REJECT),
        dpdpRefs = refs, penaltyBand = PenaltyBand.RESIDUAL_50, entities = entities,
    )

    private val leaky = AuditEvidence(
        label = "LeakyShop (sample)", sample = true,
        result = AuditResult(
            findings = listOf(
                finding(CheckId.C2, listOf("Meta", "AppsFlyer"), listOf("s6(4)", "s6(6)")),
                finding(CheckId.C1, listOf("Google Analytics for Firebase", "Meta"), listOf("s6(1)")),
                finding(CheckId.C3, refs = listOf("s6(1)")),
            ),
            score = 21, band = ScoreBand.AT_RISK,
        ),
    )

    private val live = leaky.copy(label = "LeakyShop", sample = false)

    @Test fun `missed refusal is not described as a completed clean refusal test`() {
        val missed = live.copy(result = AuditResult(emptyList(), 51, ScoreBand.AT_RISK),
            plans = setOf(AuditPlan.REJECT), completedPlans = emptySet())
        val a = answerer.answer("What happened after I rejected?", missed, CopilotLanguage.EN)
        assertTrue(a.text, a.text.contains("C2 not tested"))
        assertFalse(a.text, a.text.contains("did not run"))
        assertFalse(a.text, a.text.contains("did not record C2"))
    }

    @Test fun `score reply qualifies an incomplete choice in both languages`() {
        val missed = live.copy(result = AuditResult(emptyList(), 51, ScoreBand.AT_RISK),
            plans = setOf(AuditPlan.REJECT), completedPlans = emptySet())
        val en = answerer.answer("What is the score?", missed, CopilotLanguage.EN)
        val hi = answerer.answer(CopilotQuestions.byIntent(CopilotIntent.SCORE).hi, missed, CopilotLanguage.HI)
        assertTrue(en.text, en.text.contains("C2 not tested"))
        assertTrue(hi.text, hi.text.contains("C2 की जाँच नहीं हुई"))
    }

    @Test fun `missing Accept qualifies score in both languages without claiming C2 was untested`() {
        val missed = live.copy(result = AuditResult(emptyList(), 51, ScoreBand.AT_RISK),
            plans = setOf(AuditPlan.ACCEPT), completedPlans = emptySet())
        val en = answerer.answer(CopilotIntent.SCORE, "score", missed, CopilotLanguage.EN)
        val hi = answerer.answer(CopilotIntent.SCORE, "स्कोर", missed, CopilotLanguage.HI)
        assertTrue(en.text, en.text.contains("Accept choice was not recorded"))
        assertTrue(en.text, en.text.contains("incomplete"))
        assertTrue(hi.text, hi.text.contains("स्वीकार करने का चुनाव दर्ज नहीं हुआ"))
        assertTrue(hi.text, hi.text.contains("अधूरा"))
        assertFalse(en.text, en.text.contains("C2 not tested"))
        assertFalse(hi.text, hi.text.contains("C2 की जाँच नहीं हुई"))
    }

    @Test fun `all missing planned choices are qualified even on a refusal question`() {
        val missed = live.copy(result = AuditResult(emptyList(), 51, ScoreBand.AT_RISK),
            plans = setOf(AuditPlan.ACCEPT, AuditPlan.REJECT), completedPlans = emptySet())
        val answer = answerer.answer(CopilotIntent.AFTER_REJECT, "after reject", missed, CopilotLanguage.EN)
        assertTrue(answer.text, answer.text.contains("Accept choice was not recorded"))
        assertEquals(1, Regex("C2 not tested").findAll(answer.text).count())
    }

    @Test
    fun `children question without C9 cites an s9 passage and never calls the app safe`() {
        val a = answerer.answer("Safe for children?", leaky, CopilotLanguage.EN)
        assertFalse(a.refused)
        assertTrue("cites s9: ${a.citations.map { it.id }}", a.citations.any { it.id.startsWith("s9") })
        assertTrue(a.citations.size <= 3)
        assertFalse(a.text, Regex("\\b(is|are) safe\\b", RegexOption.IGNORE_CASE).containsMatchIn(a.text))
        assertTrue(a.text, a.text.contains("cannot say", ignoreCase = true))
        assertTrue("names observed trackers", a.text.contains("Meta"))
    }

    @Test
    fun `children answer with a C9 finding reports it`() {
        val withC9 = live.copy(result = live.result.copy(findings = live.result.findings + finding(CheckId.C9, listOf("Meta"), listOf("s9(1)", "s9(3)"))))
        val a = answerer.answer("Is this app okay for kids?", withC9, CopilotLanguage.EN)
        assertTrue(a.text, a.text.contains(CheckId.C9.title, ignoreCase = true))
        assertTrue(a.citations.any { it.id.startsWith("s9") })
    }

    @Test
    fun `off-topic questions return exactly the refusal`() {
        listOf("What is the weather in Delhi?", "Tell me a joke", "", "   ", "Who won the cricket match?").forEach { q ->
            val a = answerer.answer(q, leaky, CopilotLanguage.EN)
            assertTrue(q, a.refused)
            assertEquals(NO_EVIDENCE, a.text)
            assertTrue(a.citations.isEmpty())
        }
    }

    @Test
    fun `refusal is the exact English string in Hindi mode too`() {
        val a = answerer.answer("आज मौसम कैसा है?", leaky, CopilotLanguage.HI)
        assertTrue(a.refused)
        assertEquals("I have no evidence about that in this audit", a.text)
    }

    @Test
    fun `after reject question uses only the C2 finding entities`() {
        val a = answerer.answer("What happened after I rejected?", live, CopilotLanguage.EN)
        assertFalse(a.refused)
        assertTrue(a.text, a.text.contains("AppsFlyer"))
        assertFalse("sample prefix only for samples", a.text.startsWith("Sample"))
    }

    @Test
    fun `absent finding is reported as not observed, not as compliant`() {
        val clean = live.copy(result = AuditResult(emptyList(), 100, ScoreBand.READY))
        val a = answerer.answer("Did it contact trackers after I rejected?", clean, CopilotLanguage.EN)
        assertFalse(a.refused)
        assertTrue(a.text, a.text.contains("did not record", ignoreCase = true))
        assertFalse(a.text, a.text.contains("compliant", ignoreCase = true))
    }

    @Test
    fun `sample audits stay visibly sample`() {
        val a = answerer.answer("What is the score?", leaky, CopilotLanguage.EN)
        assertTrue(a.text, a.text.startsWith("Sample audit"))
        assertTrue(a.text.contains("21"))
    }

    @Test
    fun `hindi answer is in Devanagari and keeps the s9 citation`() {
        val a = answerer.answer(CopilotQuestions.byIntent(CopilotIntent.CHILDREN).hi, leaky, CopilotLanguage.HI)
        assertFalse(a.refused)
        assertTrue(a.text, a.text.any { it in 'ऀ'..'ॿ' })
        assertTrue(a.citations.any { it.id.startsWith("s9") })
    }

    @Test
    fun `every chip produces an answer and at most three citations`() {
        CopilotQuestions.chips.forEach { q ->
            CopilotLanguage.entries.forEach { lang ->
                val text = if (lang == CopilotLanguage.EN) q.en else q.hi
                val a = answerer.answer(text, leaky, lang)
                assertFalse("${q.intent} $lang", a.refused)
                assertTrue(a.citations.size <= 3)
            }
        }
    }

    @Test
    fun `each chip reaches its own intent and gives a distinct grounded reply`() {
        CopilotLanguage.entries.forEach { language ->
            val answers = CopilotQuestions.chips.map { q ->
                val text = if (language == CopilotLanguage.EN) q.en else q.hi
                answerer.answer(text, leaky, language).also { assertEquals(q.intent, it.intent) }
            }
            assertEquals(8, answers.map { it.text }.toSet().size)
        }
    }

    @Test
    fun `citations come only from the top three retrieved passages`() {
        val fixed = Passage("s6(1)", "x", "x", "x")
        val onlyOne = PassageRetriever { _, k -> listOf(ScoredPassage(fixed, 1.0)).take(k) }
        val a = GroundedAnswerer(corpus, onlyOne).answer("Safe for children?", leaky, CopilotLanguage.EN)
        assertFalse(a.citations.any { it.id.startsWith("s9") })
        assertTrue(a.citations.all { it.id == "s6(1)" } || a.citations.isEmpty())
    }

    @Test
    fun `retriever is asked for at most three passages`() {
        var asked = -1
        val spy = PassageRetriever { q, k -> asked = k; Bm25Retriever(corpus.passages).search(q, k) }
        GroundedAnswerer(corpus, spy).answer("Safe for children?", leaky, CopilotLanguage.EN)
        assertEquals(3, asked)
    }
}
