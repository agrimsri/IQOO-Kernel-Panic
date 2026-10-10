package com.dpdpxray.core.rules.science

import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.AuditSource
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.RejectOption
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.ScreenAudit
import com.dpdpxray.core.model.Severity
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.rules.ScoreCalculator
import com.dpdpxray.core.rules.ScoreRobustness
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The robustness interval: how far AI or documentation readings (INFERRED findings) could move a result. Expected values
 * are worked out by hand from the weights (Critical 30, High 15, Medium 8, Low 3; INFERRED counts half) and checked
 * against the real fixtures run through the rules engine.
 */
class ScoreRobustnessTest {
    private val engine = RulesEngine(TrackerCatalog.loadDefault())
    private fun v(id: CheckId, s: Severity, e: EvidenceType) = Variant(id, s, e).finding

    @Test
    fun `LeakyShop with the AI screen reading scores 8 and can move between 0 and 25`() {
        val r = engine.evaluate(LeakyShopShapes.aiAssisted)
        assertEquals(8, r.score)
        // Observed: C2 Critical 30 + C1, C3, C4 High 15 each = 75. Inferred: C5, C6, C7, C10 Medium 8 each + C8 Low 3 = 35.
        // Actual 100 - 75 - 35/2 = 7.5 -> 8. Worst 100 - 75 - 35 = -10 -> 0. Best 100 - 75 = 25.
        val rob = ScoreCalculator.robustness(r.findings)!!
        assertEquals(
            ScoreRobustness(8, ScoreBand.AT_RISK, 0, ScoreBand.AT_RISK, 25, ScoreBand.AT_RISK,
                listOf(CheckId.C5, CheckId.C6, CheckId.C7, CheckId.C8, CheckId.C10)),
            rob,
        )
        assertTrue(rob.bandStable)
        assertTrue(rob.movable)
        assertEquals("AI and documentation readings can move this between 0 and 25 · the band stays At risk", rob.line())
        assertEquals(
            "The 5 findings marked INFERRED (C5, C6, C7, C8, C10) come from AI screen review or SDK documentation and count " +
                "half. If all were confirmed on the phone the score would be 0 (At risk); if all were wrong, 25 (At risk). " +
                "The score shown does not change.",
            rob.detail(),
        )
        assertEquals("AI और दस्तावेज़ों से लिए गए निष्कर्ष इस स्कोर को 0 से 25 के बीच ले जा सकते हैं · श्रेणी जोखिम में ही रहती है", rob.line(hindi = true))
    }

    @Test
    fun `LeakyShop rules-only scores 21 and can move between 17 and 25`() {
        val r = engine.evaluate(LeakyShopShapes.rulesOnly)
        assertEquals(21, r.score)
        // Observed 75 as above; only C10 (Medium, 8) is inferred: actual 100 - 75 - 4 = 21, worst 17, best 25.
        val rob = ScoreCalculator.robustness(r.findings)!!
        assertEquals(ScoreRobustness(21, ScoreBand.AT_RISK, 17, ScoreBand.AT_RISK, 25, ScoreBand.AT_RISK, listOf(CheckId.C10)), rob)
        assertEquals("AI and documentation readings can move this between 17 and 25 · the band stays At risk", rob.line())
        assertEquals(
            "The finding marked INFERRED (C10) comes from AI screen review or SDK documentation and counts half. If it were " +
                "confirmed on the phone the score would be 17 (At risk); if it were wrong, 25 (At risk). The score shown does not change.",
            rob.detail(),
        )
        // Before clamping, the score is the exact midpoint of the interval: (17 + 25) / 2 = 21.
        assertEquals(21, (rob.worstCase + rob.bestCase) / 2)
    }

    @Test
    fun `fixed LeakyShop and the recorded Facebook capture have nothing inferred to move`() {
        for (sessions in listOf(LeakyShopShapes.fixed, LeakyShopShapes.facebook)) {
            val r = engine.evaluate(sessions)
            assertEquals(100, r.score)
            val rob = ScoreCalculator.robustness(r.findings)!!
            assertEquals(ScoreRobustness(100, ScoreBand.READY, 100, ScoreBand.READY, 100, ScoreBand.READY, emptyList()), rob)
            assertFalse(rob.movable)
            assertTrue(rob.bandStable)
        }
    }

    @Test
    fun `a child audience read only by the screen review is Ready at 85 but the band could change`() {
        val s = 1_000_000_000L
        val screen = ScreenAudit(
            isConsentScreen = true, preTicked = false, rejectOption = RejectOption.VISIBLE, purposesCount = 1, separateChoices = true,
            itemisedData = true, purposeStated = true, withdrawalInfo = true, languageOptions = listOf("English", "Hindi"),
            childAudienceSignals = true, source = AuditSource.MERGED, confidence = 0.7,
            observedFields = setOf("preTicked", "rejectOption", "separateChoices", "itemisedData", "purposeStated", "withdrawalInfo", "languageOptions"),
        )
        val session = AuditSession(
            id = "a", targetPackage = "com.kids.game", targetLabel = "Kids", plan = AuditPlan.ACCEPT, endedAtNanos = 30 * s, freshState = true,
            consentEvents = listOf(ConsentEvent(2 * s, ConsentKind.SCREEN_APPEARED), ConsentEvent(5 * s, ConsentKind.ACCEPT)),
            netEvents = listOf(NetEvent(1 * s, "api.kids.game"), NetEvent(8 * s, "graph.facebook.com")), screenAudit = screen,
        )
        val r = engine.evaluate(listOf(session))
        assertEquals(listOf(CheckId.C9), r.findings.map { it.checkId })
        assertEquals(EvidenceType.INFERRED, r.findings.single().evidenceType)
        assertEquals(85 to ScoreBand.READY, r.score to r.band)
        val rob = ScoreCalculator.robustness(r.findings)!!
        assertEquals(ScoreRobustness(85, ScoreBand.READY, 70, ScoreBand.NEEDS_WORK, 100, ScoreBand.READY, listOf(CheckId.C9)), rob)
        assertFalse(rob.bandStable)
        assertEquals("AI and documentation readings can move this between 70 and 100 · the band could change (Needs work to Ready)", rob.line())
        assertEquals(
            "AI और दस्तावेज़ों से लिए गए निष्कर्ष इस स्कोर को 70 से 100 के बीच ले जा सकते हैं · श्रेणी बदल सकती है (सुधार की ज़रूरत से तैयार तक)",
            rob.line(hindi = true),
        )
    }

    @Test
    fun `the worst case also closes the Ready gate for an inferred High`() {
        // One inferred High: 100 - 7.5 = 92.5 -> 93, Ready. Confirmed it is an observed High: 85, held out of Ready.
        val rob = ScoreCalculator.robustness(listOf(v(CheckId.C3, Severity.HIGH, EvidenceType.INFERRED)))!!
        assertEquals(ScoreRobustness(93, ScoreBand.READY, 85, ScoreBand.NEEDS_WORK, 100, ScoreBand.READY, listOf(CheckId.C3)), rob)
        assertFalse(rob.bandStable)
    }

    @Test
    fun `inconclusive results have no interval`() {
        val v0 = Variant(CheckId.V0, Severity.INFO, EvidenceType.OBSERVED).finding
        val u1 = Variant(CheckId.U1, Severity.INFO, EvidenceType.OBSERVED, withEntities = true).finding
        val c10 = v(CheckId.C10, Severity.MEDIUM, EvidenceType.INFERRED)
        assertNull(ScoreCalculator.robustness(listOf(v0, c10)))
        assertNull(ScoreCalculator.robustness(listOf(u1, c10)))
        // U1 without tracker entities is only a note: the result has a score and an interval.
        val u1Quiet = Variant(CheckId.U1, Severity.INFO, EvidenceType.OBSERVED).finding
        assertEquals(96 to 92, ScoreCalculator.robustness(listOf(u1Quiet, c10))!!.let { it.score to it.worstCase })
    }

    @Test
    fun `observed findings alone at 0 leave nothing for the inferred ones to move`() {
        val heavy = listOf(
            v(CheckId.C2, Severity.CRITICAL, EvidenceType.OBSERVED), v(CheckId.C9, Severity.CRITICAL, EvidenceType.OBSERVED),
            v(CheckId.C1, Severity.HIGH, EvidenceType.OBSERVED), v(CheckId.NC, Severity.HIGH, EvidenceType.OBSERVED),
            v(CheckId.C3, Severity.HIGH, EvidenceType.OBSERVED), v(CheckId.C10, Severity.MEDIUM, EvidenceType.INFERRED),
        )
        val rob = ScoreCalculator.robustness(heavy)!!
        assertEquals(listOf(0, 0, 0), listOf(rob.worstCase, rob.score, rob.bestCase))
        assertEquals(listOf(CheckId.C10), rob.inferred)
        assertFalse("pinned at 0: not shown", rob.movable)
    }

    @Test
    fun `the interval never changes the score or band`() {
        val findings = engine.evaluate(LeakyShopShapes.aiAssisted).findings
        val before = ScoreCalculator.compute(findings)
        val rob = ScoreCalculator.robustness(findings)!!
        assertEquals(before, ScoreCalculator.compute(findings))
        assertEquals(before, rob.score to rob.band)
    }
}
