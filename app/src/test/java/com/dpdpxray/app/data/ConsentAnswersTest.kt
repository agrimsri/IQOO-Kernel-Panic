package com.dpdpxray.app.data

import com.dpdpxray.app.ui.guide.RecentChecks
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.ConsentHistory
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.rules.BlockBlastReplay
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The person's answer about their consent history, applied to a saved check: only a tap sets it, it re-scores the same
 * sessions, and it can be changed or cleared. Block Blast-shaped test data (core test fixtures), not a device capture.
 */
class ConsentAnswersTest {
    private val engine = RulesEngine(TrackerCatalog.loadDefault())
    private val evaluate: (List<AuditSession>, ConsentHistory) -> com.dpdpxray.core.model.AuditResult = { s, h -> engine.evaluate(s, h) }

    private fun bundle(sessions: List<AuditSession> = BlockBlastReplay.sessions(), sample: Boolean = false): AuditBundle {
        val result = engine.evaluate(sessions)
        val meta = AuditMeta("audit-1", BlockBlastReplay.PACKAGE, BlockBlastReplay.LABEL, 1, result.score, result.band, result.findings.size,
            listOf(sessions.first().plan.label), sample = sample, timingProfile = "normal",
            inconclusiveReason = "CONSENT_HISTORY_UNKNOWN", historyBounds = result.historyBounds)
        return AuditBundle(meta, sessions, result, File("."))
    }

    /** The same capture, declared child-facing: the two answers give different bands, so the question is asked. */
    private val split = bundle(BlockBlastReplay.sessions().map { it.copy(childFacing = true) })

    @Test fun `the question state follows the bounds`() {
        assertEquals(ConsentAnswers.State.SETTLED, ConsentAnswers.state(bundle()))
        assertEquals(ConsentAnswers.State.ASK, ConsentAnswers.state(split))
        assertEquals(ConsentAnswers.State.NONE, ConsentAnswers.state(bundle(sample = true)))
        val fresh = bundle(BlockBlastReplay.sessions().map { it.copy(freshState = true) })
        assertEquals(ConsentAnswers.State.NONE, ConsentAnswers.state(fresh))
    }

    @Test fun `No, never picks the never-agreed end and makes the saved check scored`() {
        val (meta, result) = ConsentAnswers.apply(split, ConsentHistory.DECLARED_NEVER, evaluate)
        val b = split.result.historyBounds!!
        assertEquals(b.lowScore, meta.score)
        assertEquals(b.lowBand, meta.band)
        assertNull(meta.inconclusiveReason)
        assertEquals(ConsentHistory.DECLARED_NEVER, meta.historyBounds?.answer)
        assertEquals(result.findings.size, meta.findingCount)
        assertEquals(ConsentAnswers.State.ANSWERED, ConsentAnswers.state(split.copy(meta = meta, result = result)))
    }

    @Test fun `Yes, I agreed picks the agreed end and is never Ready`() {
        val (meta, _) = ConsentAnswers.apply(bundle(), ConsentHistory.DECLARED_AGREED, evaluate)
        assertEquals(99, meta.score)
        assertEquals(ScoreBand.NEEDS_WORK, meta.band)
    }

    @Test fun `Not sure keeps the range, and clearing removes the answer`() {
        val (unsure, unsureResult) = ConsentAnswers.apply(split, ConsentHistory.NOT_SURE, evaluate)
        assertNull(unsure.score)
        assertEquals(ScoreBand.INCONCLUSIVE, unsure.band)
        assertEquals("CONSENT_HISTORY_UNKNOWN", unsure.inconclusiveReason)
        assertEquals(ConsentHistory.NOT_SURE, unsure.historyBounds?.answer)
        assertEquals(ConsentAnswers.State.NOT_SURE, ConsentAnswers.state(split.copy(meta = unsure, result = unsureResult)))

        val answered = split.copy(meta = ConsentAnswers.apply(split, ConsentHistory.DECLARED_NEVER, evaluate).first)
        val (cleared, clearedResult) = ConsentAnswers.apply(answered, null, evaluate)
        assertNull(cleared.score)
        assertNull(cleared.historyBounds?.answer)
        assertEquals(split.result, clearedResult)
    }

    @Test fun `a check saved before ranges gains its range when nothing else changes`() {
        val old = bundle().let { it.copy(result = it.result.copy(historyBounds = null), meta = it.meta.copy(historyBounds = null)) }
        assertEquals(bundle().result, ConsentAnswers.rangeFor(old, evaluate))
        assertNull("a result that already has its range is left alone", ConsentAnswers.rangeFor(bundle(), evaluate))
        val different = old.copy(result = old.result.copy(findings = old.result.findings.dropLast(1)))
        assertNull("rules that changed the checks never rewrite a saved result", ConsentAnswers.rangeFor(different, evaluate))
    }

    @Test fun `saved metadata without bounds reads and writes exactly as before`() {
        val plain = AuditMeta("a", "p", "P", 0, 21, ScoreBand.AT_RISK, 5, listOf("Plan A · Accept"))
        val text = com.dpdpxray.core.report.AuditJson.json.encodeToString(AuditMeta.serializer(), plain)
        assertFalse(text.contains("historyBounds"))
        assertTrue(com.dpdpxray.core.report.AuditJson.json.encodeToString(AuditMeta.serializer(), bundle().meta).contains("historyBounds"))
    }

    @Test fun `lists show the range, file it under the worse band, and never say Inconclusive for it`() {
        val settled = bundle().meta
        assertEquals("65–99", RecentChecks.scoreText(settled))
        assertEquals(ScoreBand.NEEDS_WORK, RecentChecks.listBand(settled))
        assertEquals("Consent history unknown: score range", RecentChecks.coverageWarning(settled))
        val open = split.meta
        assertEquals(open.historyBounds!!.lowBand, RecentChecks.listBand(open))
        assertEquals(ScoreBand.AT_RISK, RecentChecks.listBand(open))
        val answered = ConsentAnswers.apply(split, ConsentHistory.DECLARED_NEVER, evaluate).first
        assertEquals(answered.score.toString(), RecentChecks.scoreText(answered))
        assertEquals(answered.band, RecentChecks.listBand(answered))
        assertEquals("Score based on your answer", RecentChecks.coverageWarning(answered))
    }
}
