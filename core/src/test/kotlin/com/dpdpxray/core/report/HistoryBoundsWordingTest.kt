package com.dpdpxray.core.report

import com.dpdpxray.core.assistant.AuditSpokenSummary
import com.dpdpxray.core.copilot.AuditEvidence
import com.dpdpxray.core.copilot.CopilotIntent
import com.dpdpxray.core.copilot.CopilotLanguage
import com.dpdpxray.core.copilot.GroundedAnswerer
import com.dpdpxray.core.dpdp.Bm25Retriever
import com.dpdpxray.core.dpdp.CitationService
import com.dpdpxray.core.dpdp.DpdpCorpus
import com.dpdpxray.core.law.LegalLens
import com.dpdpxray.core.law.LegalProfile
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.ConsentHistory
import com.dpdpxray.core.rules.BlockBlastReplay
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * Every surface words a consent-history range by its bounds, never as a bare "Inconclusive": the report (Markdown, and the
 * PDF made from it), the HTML report, the legal lens line, the copilot's answers and the spoken summary.
 */
class HistoryBoundsWordingTest {
    private val catalog = TrackerCatalog.loadDefault()
    private val engine = RulesEngine(catalog)
    private val corpus = DpdpCorpus.loadDefault()
    private val citations = CitationService(corpus)
    private val answerer = GroundedAnswerer(corpus, Bm25Retriever(corpus.passages))
    private val sessions = BlockBlastReplay.sessions()
    private val unknown = engine.evaluate(sessions)
    private val never = engine.evaluate(sessions, ConsentHistory.DECLARED_NEVER)
    private val agreed = engine.evaluate(sessions, ConsentHistory.DECLARED_AGREED)

    private fun md(r: com.dpdpxray.core.model.AuditResult) = MarkdownReport.build(r, sessions, emptyList(), citations)

    @Test fun `the Markdown report states the range and both ends, never Inconclusive`() {
        val text = md(unknown)
        assertTrue(text, text.contains("**Consent history unknown · Needs work either way · 65–99**"))
        assertTrue(text, text.contains("If you never agreed: 65 (Needs work). If you agreed earlier: 99 (Needs work, never Ready on an unchecked answer)."))
        assertFalse(text, text.contains("**Inconclusive**"))
    }

    @Test fun `an answered result says whose answer it rests on`() {
        val text = md(never)
        assertTrue(text, text.contains("**Score: 65/100 · Needs work · based on your answer about consent history**"))
        assertTrue(text, text.contains("Consent history: declared by the person: never agreed"))
        val yes = md(agreed)
        assertTrue(yes, yes.contains("**Score: 99/100 · Needs work · based on your answer about consent history**"))
        assertTrue(yes, yes.contains("Consent history: declared by the person: agreed earlier"))
    }

    @Test fun `the HTML report shows the range in the dial`() {
        val html = HtmlReport.build(unknown, sessions, emptyList(), citations, catalog, HtmlCoverage(emptyList(), "On this phone."), ZoneId.of("UTC"))
        assertTrue(html.contains("<span class=\"dial-num\">65–99</span>"))
        assertTrue(html.contains("Needs work either way"))
        assertTrue(html.contains("Score between 65 and 99, depending on consent history"))
        assertFalse(html.contains("Score inconclusive"))
    }

    @Test fun `the legal lens line names the range instead of inconclusive`() {
        val line = LegalLens.default.view(unknown, LegalProfile.INDIA).scoreLine
        assertTrue(line, line.contains("depends on consent history"))
        assertTrue(line, line.contains("65–99"))
        assertFalse(line, line.contains("inconclusive"))
    }

    private val evidence = AuditEvidence(BlockBlastReplay.LABEL, sample = false, result = unknown, plans = setOf(AuditPlan.REJECT))

    @Test fun `the copilot answers the score question with the range`() {
        val en = answerer.answer(CopilotIntent.SCORE, "what is the score", evidence, CopilotLanguage.EN).text
        assertTrue(en, en.contains("Needs work either way · 65–99"))
        assertTrue(en, en.contains("If you never agreed: 65"))
        assertFalse(en, en.contains("inconclusive"))
        val hi = answerer.answer(CopilotIntent.SCORE, "स्कोर", evidence, CopilotLanguage.HI).text
        assertTrue(hi, hi.contains("दोनों हाल में सुधार की ज़रूरत · 65–99"))
        assertFalse(hi, hi.contains("अनिर्णायक"))
        val answered = answerer.answer(CopilotIntent.SCORE, "score", evidence.copy(result = never), CopilotLanguage.EN).text
        assertTrue(answered, answered.contains("65 out of 100"))
        assertTrue(answered, answered.contains("based on your answer"))
    }

    @Test fun `the spoken summary gives the range and never says Inconclusive`() {
        val en = AuditSpokenSummary.summarize(evidence, CopilotLanguage.EN)
        assertTrue(en, en.contains("Needs work whichever way you answer"))
        assertTrue(en, en.contains("65 to 99"))
        assertFalse(en, en.contains("Inconclusive"))
        val hi = AuditSpokenSummary.summarize(evidence, CopilotLanguage.HI)
        assertTrue(hi, hi.contains("65 से 99"))
        assertFalse(hi, hi.contains("अनिर्णायक"))
        val answered = AuditSpokenSummary.summarize(evidence.copy(result = never), CopilotLanguage.EN)
        assertTrue(answered, answered.contains("scored 65 out of 100"))
        assertTrue(answered, answered.contains("based on your answer that you never agreed"))
    }
}
