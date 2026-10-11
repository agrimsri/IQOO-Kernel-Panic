package com.dpdpxray.app.ui.guide

import com.dpdpxray.core.model.ScoreBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultGuideTest {
    private fun facts(
        band: ScoreBand = ScoreBand.AT_RISK, score: Int? = 21, findings: Int = 5, fixes: Int = 3, gaps: Boolean = false,
        rejectPlanned: Boolean = true, preview: Boolean = false, sample: Boolean = false,
    ) = ResultFacts(band, score, findings, fixes, gaps, rejectPlanned, preview, sample)

    private val forbidden = listOf("safe.", "is safe", "compliant", "certified", "malicious", "fraud", "illegal")

    @Test fun `a website result speaks of requests, never of DNS lookups`() {
        for (band in listOf(ScoreBand.READY, ScoreBand.NEEDS_WORK, ScoreBand.AT_RISK)) {
            val e = ResultGuide.explain(facts(band, if (band == ScoreBand.READY) 100 else 40, findings = 2).copy(web = true))
            val text = (e.meaning + e.next.flatMap { listOf(it.title, it.reason) }).joinToString(" ") { it.en }
            assertFalse(band.name + ": " + text, text.contains("lookup", ignoreCase = true) || text.contains("DNS"))
            assertTrue(band.name + ": " + text, text.contains("request"))
        }
        val ready = ResultGuide.explain(facts(ScoreBand.READY, 100, findings = 0, fixes = 0).copy(web = true))
        assertTrue(ready.meaning.first().en.contains("not a safety or compliance certificate"))
        assertTrue(ready.meaning.first().hi.contains("रिक्वेस्ट"))
    }

    @Test fun `a ready result says it is not a certificate`() {
        val e = ResultGuide.explain(facts(ScoreBand.READY, 100, findings = 0, fixes = 0))
        assertTrue(e.meaning.first().en.contains("not a safety or compliance certificate"))
        assertTrue(e.meaning.first().hi.contains("प्रमाणपत्र नहीं"))
    }

    @Test fun `inconclusive stays unknown and is not a pass`() {
        val e = ResultGuide.explain(facts(ScoreBand.INCONCLUSIVE, null, findings = 1))
        assertTrue(e.meaning.first().en.contains("not a pass"))
        assertTrue(e.next.any { it.action == NextAction.CHECK_AGAIN })
    }

    @Test fun `an unknown consent history is not described as too little captured`() {
        val f = facts(ScoreBand.INCONCLUSIVE, null, findings = 3)
            .copy(inconclusiveReason = com.dpdpxray.core.rules.Inconclusive.Reason.CONSENT_HISTORY_UNKNOWN)
        val first = ResultGuide.explain(f).meaning.first().en
        assertTrue(first, first.contains("kept its data"))
        assertFalse(first, first.contains("Too little was captured"))
        val lowVisibility = ResultGuide.explain(f.copy(inconclusiveReason = com.dpdpxray.core.rules.Inconclusive.Reason.LOW_VISIBILITY))
        assertTrue(lowVisibility.meaning.first().en.contains("Too little was captured"))
    }

    @Test fun `a consent-history range gives its verdict, never Inconclusive`() {
        val bounds = com.dpdpxray.core.model.HistoryBounds(65, ScoreBand.NEEDS_WORK, 99, ScoreBand.READY)
        val f = facts(ScoreBand.INCONCLUSIVE, null, findings = 3)
            .copy(inconclusiveReason = com.dpdpxray.core.rules.Inconclusive.Reason.CONSENT_HISTORY_UNKNOWN, bounds = bounds)
        val first = ResultGuide.explain(f).meaning.first()
        assertTrue(first.en, first.en.startsWith("Needs work whichever way you answer (65–99)."))
        assertTrue(first.en, first.en.contains("never Ready"))
        assertFalse(first.en, first.en.contains("Inconclusive", ignoreCase = true) || first.en.contains("No score"))
        assertTrue(first.hi, first.hi.contains("65–99"))
        val open = f.copy(bounds = com.dpdpxray.core.model.HistoryBounds(35, ScoreBand.AT_RISK, 69, ScoreBand.NEEDS_WORK))
        assertTrue(ResultGuide.explain(open).meaning.first().en.startsWith("At best Needs work (35–69)."))
        val spoken = ResultGuide.step(f).spoken
        assertTrue(spoken.en, spoken.en.contains("Needs work either way, 65 to 99"))
        assertFalse(spoken.en, spoken.en.contains("Inconclusive"))
        assertFalse(spoken.hi, spoken.hi.contains("अनिर्णायक"))
    }

    @Test fun `findings come with the lookup caveat and an evidence action`() {
        val e = ResultGuide.explain(facts())
        assertTrue(e.meaning.any { it.en.contains("not proof that personal data was sent") })
        assertEquals(NextAction.SEE_EVIDENCE, e.next.first().action)
        assertTrue(e.next.any { it.action == NextAction.HOW_TO_FIX && it.title.en == "How to fix (3)" })
    }

    @Test fun `coverage gaps, previews and missing reject runs are explained`() {
        assertTrue(ResultGuide.explain(facts(gaps = true)).meaning.any { it.en.contains("didn't finish") })
        val preview = ResultGuide.explain(facts(preview = true, rejectPlanned = false))
        assertTrue(preview.meaning.any { it.en.contains("Fast Preview") })
        assertFalse("preview already says Reject was not tested", preview.meaning.any { it.en.startsWith("Refusing consent") })
        assertTrue(ResultGuide.explain(facts(rejectPlanned = false)).meaning.any { it.en.startsWith("Refusing consent wasn't tested") })
    }

    @Test fun `incomplete checks offer to check again but complete ones do not`() {
        assertTrue(ResultGuide.explain(facts(gaps = true)).next.any { it.action == NextAction.CHECK_AGAIN })
        assertTrue(ResultGuide.explain(facts(rejectPlanned = false)).next.any { it.action == NextAction.CHECK_AGAIN })
        assertFalse(ResultGuide.explain(facts()).next.any { it.action == NextAction.CHECK_AGAIN })
    }

    @Test fun `samples are labelled and point to a real check`() {
        val e = ResultGuide.explain(facts(sample = true, rejectPlanned = true))
        assertTrue(e.meaning.any { it.en.contains("built-in sample") })
        assertTrue(e.next.any { it.action == NextAction.CHECK_REAL_APP })
        assertFalse(e.next.any { it.action == NextAction.CHECK_AGAIN })
    }

    @Test fun `ask and report are always offered so no existing action disappears`() {
        listOf(facts(), facts(ScoreBand.READY, 100, 0, 0), facts(ScoreBand.INCONCLUSIVE, null, 1, 0), facts(sample = true)).forEach { f ->
            val actions = ResultGuide.explain(f).next.map { it.action }
            assertTrue(NextAction.ASK in actions)
            assertTrue(NextAction.REPORT in actions)
        }
        assertFalse(ResultGuide.explain(facts(fixes = 0)).next.any { it.action == NextAction.HOW_TO_FIX })
    }

    @Test fun `never uses verdict words`() {
        ScoreBand.entries.forEach { band ->
            listOf(true, false).forEach { flag ->
                val e = ResultGuide.explain(facts(band, if (band == ScoreBand.INCONCLUSIVE) null else 50, gaps = flag, preview = flag, sample = flag))
                val all = (e.meaning.flatMap { listOf(it.en) } + e.next.flatMap { listOf(it.title.en, it.reason.en) } +
                    ResultGuide.step(facts(band)).let { listOf(it.title.en, it.body.en, it.spoken.en) }).joinToString(" ").lowercase()
                forbidden.forEach { word ->
                    // "not a safety or compliance certificate" is the required qualifier, not a verdict.
                    val cleaned = all.replace("not a safety or compliance certificate", "")
                    assertFalse("$band uses '$word': $cleaned", cleaned.contains(word))
                }
            }
        }
    }

    @Test fun `results step is step four and speaks score and band without names`() {
        val step = ResultGuide.step(facts())
        assertEquals(4, step.index)
        assertEquals("Step 4 of 4. Your result is ready. Score 21 out of 100: At risk. 5 findings.", step.spoken.en)
        val inconclusive = ResultGuide.step(facts(ScoreBand.INCONCLUSIVE, null, findings = 1))
        assertTrue(inconclusive.spoken.en.contains("Inconclusive"))
        assertFalse(inconclusive.spoken.en.contains("null"))
        assertTrue(ResultGuide.step(facts(gaps = true)).spoken.en.contains("Part of the check didn't finish."))
        assertTrue(ResultGuide.step(facts(sample = true)).spoken.en.contains("sample"))
    }

    @Test fun `a ready result is never read aloud without its caveat`() {
        val step = ResultGuide.step(facts(ScoreBand.READY, 100, findings = 0, fixes = 0))
        assertTrue(step.spoken.en, step.spoken.en.contains("not a safety certificate"))
        assertTrue(step.spoken.hi, step.spoken.hi.contains("प्रमाणपत्र नहीं"))
    }

    @Test fun `singular finding reads naturally`() {
        assertTrue(ResultGuide.step(facts(findings = 1)).spoken.en.endsWith("1 finding."))
        assertTrue(ResultGuide.explain(facts(ScoreBand.NEEDS_WORK, 80, findings = 1)).meaning.first().en.contains("1 finding "))
    }

    @Test fun `every line has hindi`() {
        val e = ResultGuide.explain(facts(gaps = true, rejectPlanned = false))
        (e.meaning + e.next.flatMap { listOf(it.title, it.reason) } + listOf(ResultGuide.meaningTitle, ResultGuide.nextTitle)).forEach {
            assertTrue(it.en, it.hi.any { c -> c in 'ऀ'..'ॿ' })
        }
    }
}
