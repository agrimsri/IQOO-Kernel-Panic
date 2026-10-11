package com.dpdpxray.core.rules

import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.Finding
import com.dpdpxray.core.model.PenaltyBand
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Test

class ScoreBandsTest {
    private fun finding(id: CheckId, severity: Severity, type: EvidenceType = EvidenceType.OBSERVED) = Finding(
        checkId = id, severity = severity, evidenceType = type, title = id.title, explanation = "", evidence = emptyList(),
        plans = listOf(AuditPlan.ACCEPT), dpdpRefs = emptyList(), penaltyBand = PenaltyBand.RESIDUAL_50,
    )

    @Test
    fun `a serious problem seen on the phone is never Ready, even at 85`() {
        assertEquals(85 to ScoreBand.NEEDS_WORK, ScoreCalculator.compute(listOf(finding(CheckId.C1, Severity.HIGH))))
        assertEquals(70 to ScoreBand.NEEDS_WORK, ScoreCalculator.compute(listOf(finding(CheckId.C2, Severity.CRITICAL))))
    }

    @Test
    fun `AI-inferred findings alone cannot block Ready, they only cost points`() {
        assertEquals(93 to ScoreBand.READY, ScoreCalculator.compute(listOf(finding(CheckId.C3, Severity.HIGH, EvidenceType.INFERRED))))
    }

    @Test
    fun `small problems keep the point bands`() {
        assertEquals(97 to ScoreBand.READY, ScoreCalculator.compute(listOf(finding(CheckId.C8, Severity.LOW), finding(CheckId.S1, Severity.INFO))))
        assertEquals(84 to ScoreBand.NEEDS_WORK, ScoreCalculator.compute(listOf(finding(CheckId.C6, Severity.MEDIUM), finding(CheckId.C7, Severity.MEDIUM))))
        assertEquals(55 to ScoreBand.AT_RISK, ScoreCalculator.compute(listOf(finding(CheckId.C2, Severity.CRITICAL), finding(CheckId.C1, Severity.HIGH))))
    }
}
