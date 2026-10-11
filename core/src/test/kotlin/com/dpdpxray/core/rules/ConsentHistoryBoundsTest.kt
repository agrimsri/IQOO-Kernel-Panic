package com.dpdpxray.core.rules

import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentHistory
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.Finding
import com.dpdpxray.core.model.HistoryBounds
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.PenaltyBand
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.Severity
import com.dpdpxray.core.report.AuditJson
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A kept-data run that showed no consent screen cannot see whether the person agreed earlier (U1). Instead of a bare
 * "Inconclusive" the engine bounds the verdict by the two answers the person could give: "if you agreed earlier" (the
 * same findings without U1) and "if you never agreed" (the same plans judged as a fresh plan would be). The person's
 * answer picks one end, labelled as theirs; the result is never Ready unless both ends are.
 */
class ConsentHistoryBoundsTest {
    private val s = 1_000_000_000L
    private val engine = RulesEngine(TrackerCatalog.loadDefault())

    private val trackers = listOf("launches.appsflyersdk.com", "ms.applovin.com", "googleads.g.doubleclick.net")
    private val ownHosts = listOf("api.game.example", "cdn.game.example")

    private fun kept(hosts: List<String>, childFacing: Boolean = false, plan: AuditPlan = AuditPlan.REJECT) = AuditSession(
        id = "kept-${plan.name}", targetPackage = "com.example.game", targetLabel = "Example Game", plan = plan,
        startedAtNanos = 0, endedAtNanos = 45 * s, childFacing = childFacing,
        netEvents = hosts.mapIndexed { i, h -> NetEvent((i + 1) * s, h) }, freshState = false,
    )

    private fun ids(r: AuditResult) = r.findings.map { it.checkId }.toSet()

    // --- the two ends ----------------------------------------------------------------------------------------------------

    @Test fun `the agreed-earlier end is today's findings without U1`() {
        val r = engine.evaluate(listOf(kept(trackers + ownHosts, childFacing = true)))
        assertNotNull(r.historyBounds)
        val b = r.historyBounds!!
        assertNull("a truly unknown history keeps no single score", r.score)
        assertEquals(ScoreBand.INCONCLUSIVE, r.band)
        assertTrue(CheckId.U1 in ids(r))
        assertEquals(ScoreCalculator.compute(r.findings.filterNot { it.checkId == CheckId.U1 }), b.highScore to b.highBand)
        assertNull(b.answer)
    }

    @Test fun `the never-agreed end judges the kept plans exactly as a fresh plan would be judged`() {
        val sessions = listOf(kept(trackers + ownHosts, childFacing = true))
        val b = engine.evaluate(sessions).historyBounds!!
        val fresh = engine.evaluate(sessions.map { it.copy(freshState = true) })
        assertEquals(fresh.score, b.lowScore)
        assertEquals(fresh.band, b.lowBand)
        assertTrue(CheckId.C1 in ids(fresh) && CheckId.NC in ids(fresh))
        assertTrue(b.lowScore < b.highScore)
    }

    @Test fun `low visibility stays inconclusive with no bounds - nothing was seen, so there is nothing to bound`() {
        val r = engine.evaluate(listOf(kept(listOf("api.game.example"))))
        assertEquals(Inconclusive.Reason.LOW_VISIBILITY, Inconclusive.reason(r.findings))
        assertNull(r.historyBounds)
        assertNull(r.score)
    }

    @Test fun `a fresh-install audit needs no bounds and scores as before`() {
        val r = engine.evaluate(listOf(kept(trackers + ownHosts).copy(freshState = true)))
        assertNull(r.historyBounds)
        assertNotNull(r.score)
    }

    @Test fun `a kept-data run with only the app's own lookups keeps its plain score`() {
        val r = engine.evaluate(listOf(kept(ownHosts + "img.game.example")))
        assertNull(r.historyBounds)
        assertEquals(100, r.score)
    }

    // --- same band: no question ------------------------------------------------------------------------------------------

    @Test fun `when both ends give the same band that band is the verdict and no question is needed`() {
        val b = engine.evaluate(BlockBlastReplay.sessions()).historyBounds!!
        assertTrue(b.bandSettled)
        assertFalse(b.questionNeeded)
        assertEquals(ScoreBand.NEEDS_WORK, b.verdictBand)
        assertEquals("Needs work either way · ${b.lowScore}–${b.highScore}", b.verdict())
    }

    // --- different bands: the one question -------------------------------------------------------------------------------

    private val split = listOf(kept(trackers + ownHosts, childFacing = true))

    @Test fun `when the ends differ the range shows the best band it could reach and asks the question`() {
        val b = engine.evaluate(split).historyBounds!!
        assertEquals(ScoreBand.AT_RISK, b.lowBand)
        assertEquals(ScoreBand.NEEDS_WORK, b.highBand)
        assertTrue(b.questionNeeded)
        assertNull(b.verdictBand)
        assertEquals("At best Needs work · ${b.lowScore}–${b.highScore}", b.verdict())
    }

    @Test fun `answering No picks the never-agreed end, labelled as based on the answer`() {
        val b = engine.evaluate(split).historyBounds!!
        val r = engine.evaluate(split, ConsentHistory.DECLARED_NEVER)
        assertEquals(b.lowScore, r.score)
        assertEquals(b.lowBand, r.band)
        assertEquals(b.copy(answer = ConsentHistory.DECLARED_NEVER), r.historyBounds)
        assertNull(Inconclusive.reason(r.findings))
        val c1 = r.findings.single { it.checkId == CheckId.C1 }
        assertTrue(c1.declared)
        assertEquals(Severity.HIGH, c1.severity)
        assertEquals("Tracker hosts looked up although you say you never agreed", c1.title)
        assertTrue(c1.explanation, c1.explanation.contains("based on your answer"))
        assertTrue(c1.confidence < 1.0)
        assertTrue(r.findings.single { it.checkId == CheckId.NC }.declared)
        val u1 = r.findings.single { it.checkId == CheckId.U1 }
        assertTrue(u1.declared)
        assertEquals("Consent history from your answer: never agreed", u1.title)
        assertTrue(u1.explanation, u1.explanation.contains("If you had agreed earlier, the score would be ${b.highScore}"))
    }

    @Test fun `answering Yes picks the agreed-earlier end, checked as after the agreement`() {
        val b = engine.evaluate(split).historyBounds!!
        val r = engine.evaluate(split, ConsentHistory.DECLARED_AGREED)
        assertEquals(b.highScore, r.score)
        assertEquals(b.bestBand, r.band)
        assertFalse("lookups after an agreed consent are not C1", CheckId.C1 in ids(r))
        assertFalse(CheckId.NC in ids(r))
        val u1 = r.findings.single { it.checkId == CheckId.U1 }
        assertTrue(u1.declared)
        assertEquals("Consent history from your answer: agreed earlier", u1.title)
        assertTrue(u1.explanation, u1.explanation.contains("checked as after you agreed"))
        assertEquals(ConsentHistory.DECLARED_AGREED, r.historyBounds?.answer)
    }

    @Test fun `answering Not sure keeps the range and records the answer`() {
        val unknown = engine.evaluate(split)
        val r = engine.evaluate(split, ConsentHistory.NOT_SURE)
        assertNull(r.score)
        assertEquals(ScoreBand.INCONCLUSIVE, r.band)
        assertEquals(unknown.findings, r.findings)
        assertEquals(unknown.historyBounds!!.copy(answer = ConsentHistory.NOT_SURE), r.historyBounds)
        assertTrue(r.historyBounds!!.questionNeeded)
    }

    @Test fun `an answer never changes a result whose history is known`() {
        val fresh = listOf(kept(trackers + ownHosts).copy(freshState = true))
        assertEquals(engine.evaluate(fresh), engine.evaluate(fresh, ConsentHistory.DECLARED_AGREED))
        assertEquals(engine.evaluate(fresh), engine.evaluate(fresh, ConsentHistory.DECLARED_NEVER))
    }

    // --- the Ready gate --------------------------------------------------------------------------------------------------

    @Test fun `never Ready unless both ends are Ready`() {
        // Only tracker lookups: if the person agreed earlier nothing else is wrong (100, Ready), if not it is C1 and NC.
        val sessions = listOf(kept(trackers + ownHosts))
        val b = engine.evaluate(sessions).historyBounds!!
        assertEquals(ScoreBand.READY, b.highBand)
        assertEquals(ScoreBand.NEEDS_WORK, b.bestBand)
        val agreed = engine.evaluate(sessions, ConsentHistory.DECLARED_AGREED)
        assertEquals(100, agreed.score)
        assertEquals("an unchecked answer never makes a pass", ScoreBand.NEEDS_WORK, agreed.band)
        assertEquals(agreed.score to agreed.band, ScoreCalculator.compute(agreed.findings))

        assertEquals(ScoreBand.READY, HistoryBounds(90, ScoreBand.READY, 100, ScoreBand.READY).bestBand)
        assertEquals(ScoreBand.READY, HistoryBounds(90, ScoreBand.READY, 100, ScoreBand.READY).verdictBand)
        assertEquals(ScoreBand.NEEDS_WORK, HistoryBounds(66, ScoreBand.NEEDS_WORK, 100, ScoreBand.READY).verdictBand)
    }

    @Test fun `a declared history finding closes the Ready gate in the calculator itself`() {
        val u1 = Finding(CheckId.U1, Severity.INFO, EvidenceType.OBSERVED, CheckId.U1.title, "", emptyList(), listOf(AuditPlan.REJECT),
            emptyList(), PenaltyBand.NONE, entities = listOf("AppLovin"))
        assertEquals(null to ScoreBand.INCONCLUSIVE, ScoreCalculator.compute(listOf(u1)))
        assertEquals(100 to ScoreBand.NEEDS_WORK, ScoreCalculator.compute(listOf(u1.copy(declared = true))))
        assertNull(Inconclusive.reason(listOf(u1.copy(declared = true))))
    }

    // --- Block Blast replay ----------------------------------------------------------------------------------------------

    @Test fun `Block Blast replay - kept data, no consent screen, background trackers and Firebase defaults`() {
        val r = engine.evaluate(BlockBlastReplay.sessions())
        assertEquals(Inconclusive.Reason.CONSENT_HISTORY_UNKNOWN, Inconclusive.reason(r.findings))
        assertEquals(setOf(CheckId.U1, CheckId.C12, CheckId.C13), ids(r))
        assertEquals(BlockBlastReplay.U1_ENTITIES, r.findings.single { it.checkId == CheckId.U1 }.entities.toSet())
        assertEquals(setOf("AppLovin", "Vungle / Liftoff"), r.findings.single { it.checkId == CheckId.C12 }.entities.toSet())
        assertEquals(listOf("Google Analytics for Firebase"), r.findings.single { it.checkId == CheckId.C13 }.entities)

        val b = r.historyBounds!!
        // Agreed earlier: C13 (Low, inferred: 1.5) only. Never agreed: + C1 and NC (High, observed) and C10 (Medium, inferred).
        assertEquals(HistoryBounds(65, ScoreBand.NEEDS_WORK, 99, ScoreBand.READY), b)
        assertEquals(ScoreBand.NEEDS_WORK, b.verdictBand)

        val never = engine.evaluate(BlockBlastReplay.sessions(), ConsentHistory.DECLARED_NEVER)
        assertEquals(65 to ScoreBand.NEEDS_WORK, never.score to never.band)
        assertEquals(setOf(CheckId.U1, CheckId.C1, CheckId.NC, CheckId.C10, CheckId.C12, CheckId.C13), ids(never))
        val agreed = engine.evaluate(BlockBlastReplay.sessions(), ConsentHistory.DECLARED_AGREED)
        assertEquals(99 to ScoreBand.NEEDS_WORK, agreed.score to agreed.band)
        assertEquals(setOf(CheckId.U1, CheckId.C12, CheckId.C13), ids(agreed))
    }

    // --- saved files -----------------------------------------------------------------------------------------------------

    @Test fun `bounds and answers survive a save, and results without them are written exactly as before`() {
        val r = engine.evaluate(split, ConsentHistory.DECLARED_NEVER)
        val back = AuditJson.json.decodeFromString(AuditResult.serializer(), AuditJson.encodeResult(r))
        assertEquals(r, back)
        val plain = AuditJson.encodeResult(engine.evaluate(listOf(kept(ownHosts).copy(freshState = true))))
        assertFalse(plain.contains("historyBounds"))
        assertFalse(plain.contains("declared"))
        val unanswered = AuditJson.encodeResult(engine.evaluate(split))
        assertTrue(unanswered.contains("historyBounds"))
        assertFalse("no answer is written until the person gives one", unanswered.contains("answer"))
    }

    @Test fun `the bounded wording in both languages`() {
        val settled = HistoryBounds(65, ScoreBand.NEEDS_WORK, 99, ScoreBand.READY)
        assertEquals("Needs work either way · 65–99", settled.verdict())
        assertEquals("दोनों हाल में सुधार की ज़रूरत · 65–99", settled.verdict(hindi = true))
        val open = HistoryBounds(41, ScoreBand.AT_RISK, 77, ScoreBand.NEEDS_WORK)
        assertEquals("At best Needs work · 41–77", open.verdict())
        assertEquals("ज़्यादा से ज़्यादा सुधार की ज़रूरत · 41–77", open.verdict(hindi = true))
        assertEquals("41 · At risk · based on your answer", open.copy(answer = ConsentHistory.DECLARED_NEVER).verdict())
        assertEquals("77 · Needs work · based on your answer", open.copy(answer = ConsentHistory.DECLARED_AGREED).verdict())
        assertEquals("At best Needs work · 41–77", open.copy(answer = ConsentHistory.NOT_SURE).verdict())
        assertEquals("Consent history: declared by the person: never agreed", ConsentHistory.DECLARED_NEVER.line())
        assertEquals("Consent history: declared by the person: agreed earlier", ConsentHistory.DECLARED_AGREED.line())
        assertEquals("Consent history: the person is not sure (unknown)", ConsentHistory.NOT_SURE.line())
    }

    @Test fun `consent events are untouched - a plan that showed a consent screen is never bounded`() {
        val shown = kept(trackers + ownHosts, plan = AuditPlan.ACCEPT).copy(consentEvents = listOf(ConsentEvent(5 * s, ConsentKind.SCREEN_APPEARED)))
        val r = engine.evaluate(listOf(shown))
        assertNull(r.historyBounds)
        assertTrue(CheckId.C1 in ids(r))
    }
}
