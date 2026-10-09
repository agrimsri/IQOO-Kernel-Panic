package com.dpdpxray.app.voice

import com.dpdpxray.app.data.AuditMeta
import com.dpdpxray.app.data.SampleAudit
import com.dpdpxray.core.copilot.*
import com.dpdpxray.core.dpdp.*
import com.dpdpxray.core.model.*
import org.junit.Assert.*
import org.junit.Test

class IncompleteAuditCopilotTest {
    @Test fun `saved missed refusal reaches voice as an uncompleted plan`() {
        val sessions = SampleAudit.sessions(1000).map { if (it.plan == AuditPlan.REJECT) it.copy(consentEvents = emptyList()) else it }
        val result = AuditResult(emptyList(), 51, ScoreBand.AT_RISK)
        val meta = AuditMeta("id", "com.leakyshop.demo", "LeakyShop", 1000, 51, ScoreBand.AT_RISK, 0, sessions.map { it.plan.label })
        val evidence = copilotEvidence(meta, sessions, result)
        assertFalse(evidence.completedPlans!!.contains(AuditPlan.REJECT))
        val corpus = DpdpCorpus.loadDefault()
        val answer = GroundedAnswerer(corpus, Bm25Retriever(corpus.passages)).answer("What happened after I rejected?", evidence, CopilotLanguage.EN)
        assertTrue(answer.text, answer.text.contains("C2 not tested"))
    }
}
