package com.dpdpxray.app.ui.screens

import com.dpdpxray.app.audit.DiagnosisCopy
import com.dpdpxray.app.data.AuditBundle
import com.dpdpxray.app.data.AuditMeta
import com.dpdpxray.app.ui.guide.ResultFacts
import com.dpdpxray.app.ui.guide.ResultGuide
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.rules.Inconclusive
import com.dpdpxray.core.rules.QuietBackgroundAppReplay
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.rules.VisibilityDiagnosis
import com.dpdpxray.core.rules.VisibilityDiagnosis.Action
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The result of a quiet background app, before and after a background watch: the one action offered, and how the result
 * says what a watch checked so it never reads as a test of the consent popup.
 */
class WatchResultTest {
    private val engine = RulesEngine(TrackerCatalog.loadDefault())

    private fun bundle(s: AuditSession): AuditBundle {
        val r = engine.evaluate(listOf(s))
        val meta = AuditMeta("audit-1", s.targetPackage, s.targetLabel, 1, r.score, r.band, r.findings.size, listOf(s.plan.label))
        return AuditBundle(meta, listOf(s), r, File("."))
    }

    @Test fun `the Quick check of a quiet background app offers one filled action - watch it for 10 minutes`() {
        val b = bundle(QuietBackgroundAppReplay.quickCheck())
        val items = NoScoreHelp.items(b)
        assertEquals(Action.WATCH_LONGER, VisibilityDiagnosis.primaryAction(items))
        assertTrue(NoScoreHelp.hasPrimary(b))
        assertEquals(10, NoScoreHelp.watchMinutes(b))
        assertEquals("Watch RollCall in the background for 10 minutes", DiagnosisCopy.action(Action.WATCH_LONGER, b.meta.targetLabel, 10)!!.en)
        // Restarting stays a link, behind the restart safeguard (it would switch off the app's device admin).
        assertEquals(listOf(Action.CHECK_AGAIN_WITH_RESTART), NoScoreHelp.otherActions(items))
        assertEquals(listOf("DEVICE_ADMIN", "FOREGROUND_SERVICE"), com.dpdpxray.app.audit.RestartReminder.recorded(b.sessions).map { it.name })
    }

    @Test fun `a 10-minute watch with enough lookups has no no-score section and is bounded, not inconclusive`() {
        val b = bundle(QuietBackgroundAppReplay.backgroundWatch())
        assertTrue(NoScoreHelp.items(b).isEmpty())
        assertFalse(NoScoreHelp.historyUnknown(b))
        assertTrue(b.result.historyBounds != null)
        assertFalse(Inconclusive.reason(b.result.findings) == Inconclusive.Reason.LOW_VISIBILITY)
    }

    @Test fun `a quiet watch offers the next length and no restart, and its headline says what it observed`() {
        val b = bundle(QuietBackgroundAppReplay.quietWatch())
        val items = NoScoreHelp.items(b)
        assertEquals(Action.WATCH_LONGER, VisibilityDiagnosis.primaryAction(items))
        assertEquals(30, NoScoreHelp.watchMinutes(b))
        assertEquals(emptyList<Action>(), NoScoreHelp.otherActions(items, watch = true))
        assertEquals("In 10 minutes of watching, RollCall made almost no connections. Why, and what to try next, is just below.",
            headline(b.result.findings, b.sessions))
        assertEquals("Too few lookups were captured to judge this app. Why, and what to try next, is just below.",
            headline(bundle(QuietBackgroundAppReplay.quickCheck()).result.findings, listOf(QuietBackgroundAppReplay.quickCheck())))
    }

    @Test fun `the recent checks list names a watch, never as an untested Reject`() {
        fun meta(band: ScoreBand, reason: String?) = AuditMeta("audit-w", "com.example.rollcall", "RollCall", 1, if (band == ScoreBand.INCONCLUSIVE) null else 90,
            band, 1, listOf(com.dpdpxray.core.model.AuditPlan.SILENT.label), timingProfile = com.dpdpxray.app.audit.AuditTimingPolicy.BACKGROUND_WATCH,
            inconclusiveReason = reason)
        val recent = com.dpdpxray.app.ui.guide.RecentChecks
        assertEquals("Background watch: almost no connections seen", recent.coverageWarning(meta(ScoreBand.INCONCLUSIVE, "LOW_VISIBILITY")))
        assertEquals("Background watch: consent screen not tested", recent.coverageWarning(meta(ScoreBand.NEEDS_WORK, null)))
    }

    @Test fun `the result guide says it was a background watch whose consent screen was not reached`() {
        val quiet = ResultFacts(ScoreBand.INCONCLUSIVE, null, 1, 0, coverageGaps = false, rejectPlanned = false, fastPreview = false, sample = false,
            inconclusiveReason = Inconclusive.Reason.LOW_VISIBILITY, watchMinutes = 10)
        val e = ResultGuide.explain(quiet)
        val text = e.meaning.joinToString(" ") { it.en }
        assertTrue(text, e.meaning.first().en.startsWith("The app made almost no connections in 10 minutes of watching"))
        assertTrue(text, text.contains("This was a 10-minute background watch: it shows what the app sends while it runs in the background. " +
            "Its consent screen was not reached, so the popup itself was not tested."))
        assertFalse(text, text.contains("too little was captured", ignoreCase = true))
        assertFalse("not reported as an untested Reject", text.contains("Refusing consent wasn't tested"))
        e.meaning.forEach { assertTrue(it.hi.isNotBlank()) }
        val spoken = ResultGuide.step(quiet).spoken.en
        assertTrue(spoken, spoken.contains("Inconclusive: the app made almost no connections in 10 minutes of watching."))
        val ready = ResultGuide.explain(quiet.copy(band = ScoreBand.READY, score = 100, findingCount = 0, inconclusiveReason = null))
        assertTrue(ready.meaning.first().en.endsWith("it covers only what the app did in this 10-minute background watch."))
    }
}
