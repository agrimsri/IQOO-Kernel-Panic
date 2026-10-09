package com.dpdpxray.app.voice

import com.dpdpxray.app.data.AuditMeta
import com.dpdpxray.app.data.SampleAudit
import com.dpdpxray.core.copilot.CopilotLanguage
import com.dpdpxray.core.copilot.GroundedAnswerer
import com.dpdpxray.core.copilot.NO_EVIDENCE
import com.dpdpxray.core.dpdp.Bm25Retriever
import com.dpdpxray.core.dpdp.DpdpCorpus
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the in-app sample audit through the real rules engine and BM25 retrieval, exactly as the screen does. */
class SampleAuditCopilotTest {
    private val sessions = SampleAudit.sessions(0)
    private val result = RulesEngine(TrackerCatalog.loadDefault()).evaluate(sessions)
    private val meta = AuditMeta("sample-1", sessions.first().targetPackage, sessions.first().targetLabel, 0, result.score, result.band,
        result.findings.size, sessions.map { it.plan.label }, sample = true)
    private val evidence = copilotEvidence(meta, sessions, result)
    private val corpus = DpdpCorpus.loadDefault()
    private val answerer = GroundedAnswerer(corpus, Bm25Retriever(corpus.passages))

    @Test
    fun `sample has no C9 so the children answer must come from retrieval`() {
        assertFalse(result.findings.any { it.checkId == CheckId.C9 })
    }

    @Test
    fun `safe for children on the sample cites an s9 passage`() {
        val a = answerer.answer("Safe for children?", evidence, CopilotLanguage.EN)
        assertFalse(a.refused)
        assertTrue(a.citations.map { it.id }.toString(), a.citations.any { it.id.startsWith("s9") })
        assertTrue(a.text, a.text.startsWith("Sample audit"))
    }

    @Test
    fun `off-topic question on the sample is refused exactly`() {
        assertEquals(NO_EVIDENCE, answerer.answer("Book me a train ticket", evidence, CopilotLanguage.EN).text)
    }

    @Test
    fun `sample evidence keeps its plans and sample flag`() {
        assertTrue(evidence.sample)
        assertEquals(3, evidence.plans.size)
    }
}
