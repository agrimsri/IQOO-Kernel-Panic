package com.dpdpxray.core.rules

import com.dpdpxray.core.copilot.VerdictWords
import com.dpdpxray.core.dpdp.CitationService
import com.dpdpxray.core.dpdp.DpdpCorpus
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.BackgroundWatch
import com.dpdpxray.core.model.BackgroundWatches
import com.dpdpxray.core.model.BlockedStep
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.ConsentHistory
import com.dpdpxray.core.model.PlanConditions
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.StartMode
import com.dpdpxray.core.model.WatchSpan
import com.dpdpxray.core.report.AuditJson
import com.dpdpxray.core.report.HtmlCoverage
import com.dpdpxray.core.report.HtmlReport
import com.dpdpxray.core.report.MarkdownReport
import com.dpdpxray.core.rules.QuietBackgroundAppReplay.ANALYTICS_ENTITY
import com.dpdpxray.core.rules.VisibilityDiagnosis.Action
import com.dpdpxray.core.rules.VisibilityDiagnosis.Cause
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * A background watch: a long capture of an app that works in the background, with no launch, restart or tap. It is
 * judged by the same rules and score; with enough lookups the normal checks apply, and a watch that still saw almost
 * nothing says so as an observation. Built on [QuietBackgroundAppReplay].
 */
class BackgroundWatchTest {
    private val s = 1_000_000_000L
    private val catalog = TrackerCatalog.loadDefault()
    private val engine = RulesEngine(catalog)
    private val citations = CitationService(DpdpCorpus.loadDefault())

    // --- The Quick check of a quiet background app: V0, with a watch as the one action ------------------------------------

    @Test fun `a Quick check that saw one own-server lookup in 15 s is inconclusive and offers a background watch`() {
        val quick = QuietBackgroundAppReplay.quickCheck()
        val r = engine.evaluate(listOf(quick))
        assertTrue(r.findings.any { it.checkId == CheckId.V0 })
        assertEquals(ScoreBand.INCONCLUSIVE, r.band)
        assertNull(r.score)
        val d = VisibilityDiagnosis.forResult(r, listOf(quick))
        assertEquals(Cause.ALREADY_RUNNING, d.first().cause)
        assertTrue(d.map { it.cause }.toString(), Cause.GATED in d.map { it.cause })
        assertTrue(d.map { it.cause }.toString(), Cause.WINDOW_TOO_SHORT in d.map { it.cause })
        assertEquals(Action.WATCH_LONGER, VisibilityDiagnosis.primaryAction(d))
        // The safeguard still decided the restart: the person chose not to restart, and that is said as a choice.
        assertTrue(d.first().sentence, d.first().sentence.startsWith("TraceVeil left the app running"))
        assertTrue(d.first().sentence, d.first().sentence.endsWith("Watching it in the background for longer needs no restart."))
    }

    @Test fun `already running, a quiet app, a short watch and a sign-in or passcode gate all point at a background watch`() {
        fun cause(c: PlanConditions, seconds: Long = 40) = VisibilityDiagnosis.diagnose(listOf(AuditSession(
            "p", "com.example.app", "App", AuditPlan.REJECT, endedAtNanos = seconds * s, freshState = false, conditions = c,
        )))
        val running = cause(PlanConditions(startMode = StartMode.NOT_RESTARTED, runningBeforeStart = true))
        assertEquals(Cause.ALREADY_RUNNING to Action.WATCH_LONGER, running.first().let { it.cause to it.action })
        val quiet = cause(PlanConditions(startMode = StartMode.RESTARTED, appTraffic = com.dpdpxray.core.model.AppTraffic(openConnections = 0)))
        assertEquals(Cause.APP_QUIET to Action.WATCH_LONGER, quiet.first().let { it.cause to it.action })
        val short = cause(PlanConditions(startMode = StartMode.RESTARTED, appTraffic = com.dpdpxray.core.model.AppTraffic(openConnections = 4)), seconds = 9)
        assertEquals(Action.WATCH_LONGER, short.single { it.cause == Cause.WINDOW_TOO_SHORT }.action)
        val gated = cause(PlanConditions(startMode = StartMode.RESTARTED, blocker = BlockedStep("SIGN_IN", handedOff = true, consentReached = false)))
        val gate = gated.single { it.cause == Cause.GATED }
        assertEquals(Action.WATCH_LONGER, gate.action)
        assertEquals("Plan B · Reject: a sign-in screen appeared before any consent screen and was not passed (you were asked to complete it).", gate.evidence.single())
        assertEquals(Action.WATCH_LONGER, VisibilityDiagnosis.primaryAction(gated))
    }

    @Test fun `other causes keep their own action, and a passed gate or a permission dialog is not a gate`() {
        fun d(c: PlanConditions, privateDns: Boolean? = false) = VisibilityDiagnosis.diagnose(listOf(AuditSession(
            "p", "com.example.app", "App", AuditPlan.REJECT, endedAtNanos = 40 * s, freshState = false, conditions = c, privateDnsActive = privateDns,
        )))
        assertEquals(Action.TURN_OFF_PRIVATE_DNS, VisibilityDiagnosis.primaryAction(d(PlanConditions(startMode = StartMode.NOT_RESTARTED), privateDns = true)))
        assertEquals(Action.TURN_OFF_OTHER_VPN, VisibilityDiagnosis.primaryAction(d(PlanConditions(startMode = StartMode.NOT_RESTARTED, otherVpnAtEnd = true))))
        assertEquals(Action.CHECK_AGAIN, d(PlanConditions(startMode = StartMode.RESTARTED, appCrashed = true)).first().action)
        val passed = d(PlanConditions(startMode = StartMode.RESTARTED, blocker = BlockedStep("SIGN_IN", consentReached = true)))
        assertFalse(Cause.GATED in passed.map { it.cause })
        val permission = d(PlanConditions(startMode = StartMode.RESTARTED, blocker = BlockedStep("PERMISSION", handedOff = true)))
        assertFalse(Cause.GATED in permission.map { it.cause })
    }

    // --- The 10-minute watch of the same app: scored like any check -----------------------------------------------------

    @Test fun `a 10-minute watch with 16 lookups is judged by the same rules - no V0, C12 for the analytics host, a range`() {
        val watch = QuietBackgroundAppReplay.backgroundWatch()
        val r = engine.evaluate(listOf(watch))
        assertFalse(r.findings.map { it.checkId }.toString(), r.findings.any { it.checkId == CheckId.V0 })
        assertTrue(VisibilityDiagnosis.forResult(r, listOf(watch)).isEmpty())
        val c12 = r.findings.single { it.checkId == CheckId.C12 }
        assertEquals(listOf(ANALYTICS_ENTITY), c12.entities)
        assertTrue(c12.explanation, c12.explanation.startsWith("During a background watch TraceVeil did not open, restart or tap the app"))
        assertTrue(c12.evidence.toString(), c12.evidence.first().contains("into the background watch, app not on screen"))
        assertTrue(c12.evidence.toString(), c12.evidence.first().endsWith("[Background watch]"))
        assertTrue(c12.evidence.toString(), c12.evidence.any { it.startsWith("Background watch: watched 599.5 s from when the app was first seen off screen") })
        // Kept data and no consent screen: the bounded verdict, never "too little was captured".
        assertEquals(Inconclusive.Reason.CONSENT_HISTORY_UNKNOWN, Inconclusive.reason(r.findings))
        assertNotNull(r.historyBounds)
        val b = r.historyBounds!!
        assertTrue("${b.lowScore}..${b.highScore}", b.lowScore < b.highScore)
        assertEquals(listOf(ANALYTICS_ENTITY), r.findings.single { it.checkId == CheckId.U1 }.entities)
        // The person's answer picks an end and the watch is scored.
        val never = engine.evaluate(listOf(watch), ConsentHistory.DECLARED_NEVER)
        assertEquals(b.lowScore, never.score)
        assertTrue(never.findings.any { it.checkId == CheckId.C1 })
        assertTrue(never.findings.any { it.checkId == CheckId.C12 })
    }

    @Test fun `lookups while the app was on screen during a watch are not counted as background`() {
        val watch = QuietBackgroundAppReplay.backgroundWatch()
        val onScreen = watch.copy(watch = watch.watch!!.copy(onScreen = listOf(WatchSpan(240 * s, 270 * s), WatchSpan(500 * s, 530 * s))))
        assertTrue(engine.evaluate(listOf(onScreen)).findings.none { it.checkId == CheckId.C12 })
        val partly = watch.copy(watch = watch.watch!!.copy(onScreen = listOf(WatchSpan(240 * s, 270 * s))))
        val c12 = engine.evaluate(listOf(partly)).findings.single { it.checkId == CheckId.C12 }
        assertEquals(1, c12.evidence.count { it.startsWith(QuietBackgroundAppReplay.ANALYTICS_HOST) })
        assertTrue(c12.evidence.toString(), c12.evidence.any { it.contains("about 30.0 s of it with the app on screen") })
        assertFalse(BackgroundWatches.inBackground(partly, 250 * s))
        assertTrue(BackgroundWatches.inBackground(partly, 520 * s))
        assertFalse("before the app was seen off screen", BackgroundWatches.inBackground(partly, 0))
    }

    // --- A watch that still saw almost nothing ----------------------------------------------------------------------------

    @Test fun `a quiet 10-minute watch stays inconclusive and says the app made almost no connections in 10 minutes`() {
        val quiet = QuietBackgroundAppReplay.quietWatch()
        val r = engine.evaluate(listOf(quiet))
        val v0 = r.findings.single { it.checkId == CheckId.V0 }
        assertEquals(ScoreBand.INCONCLUSIVE, r.band)
        assertTrue(v0.explanation, v0.explanation.startsWith("In 10 minutes of watching RollCall in the background, TraceVeil saw almost no network use"))
        assertFalse(v0.explanation, v0.explanation.contains("too little was captured", ignoreCase = true))
        assertTrue(v0.evidence.toString(), v0.evidence.first() == "Background watch: 1 lookups")
        val d = VisibilityDiagnosis.forResult(r, listOf(quiet))
        // The phone was locked during the watch (as expected) and the app was left running on purpose: neither is a cause.
        assertEquals(listOf(Cause.QUIET_WATCH), d.map { it.cause })
        val item = d.single()
        assertEquals("Background watch: watched 10 minutes; 1 lookup, 1 connection; it was running when the watch began.", item.evidence.single())
        assertTrue(item.sentence, item.sentence.startsWith("TraceVeil watched RollCall in the background for 10 minutes and saw it make almost no connections (1 lookup, 1 connection)."))
        assertTrue(item.sentence, item.sentence.contains("That is what this watch observed"))
        assertEquals("Watching longer (30 minutes) is still offered", Action.WATCH_LONGER, item.action)
    }

    @Test fun `after the longest watch nothing more is offered, and a watch stopped early says so`() {
        val long = QuietBackgroundAppReplay.quietWatch().let { it.copy(watch = BackgroundWatch(1_800_000, 1_800_000)) }
        assertEquals(Action.NONE, VisibilityDiagnosis.diagnose(listOf(long)).single().action)
        val early = QuietBackgroundAppReplay.quietWatch().let { it.copy(watch = BackgroundWatch(600_000, 252_000, stoppedEarly = true)) }
        val d = VisibilityDiagnosis.diagnose(listOf(early))
        assertEquals(listOf(Cause.QUIET_WATCH), d.map { it.cause })
        assertTrue(d.single().evidence.single(), d.single().evidence.single().contains("watched 4 min 12 s of a planned 10 minutes (stopped early)"))
    }

    @Test fun `a locked phone is still named for an ordinary check, never for a watch`() {
        val plain = AuditSession("p", "com.example.app", "App", AuditPlan.REJECT, endedAtNanos = 40 * s, freshState = true,
            conditions = PlanConditions(startMode = StartMode.RESTARTED, phoneLocked = true))
        assertTrue(Cause.PHONE_LOCKED in VisibilityDiagnosis.diagnose(listOf(plain)).map { it.cause })
        val watch = QuietBackgroundAppReplay.quietWatch()
        assertEquals(true, watch.conditions?.phoneLocked)
        assertFalse(Cause.PHONE_LOCKED in VisibilityDiagnosis.diagnose(listOf(watch)).map { it.cause })
    }

    @Test fun `the command-line words for a watch`() {
        assertEquals("watch it in the background for 10 minutes or longer (TraceVeil does not open, restart or tap it; its data stays)",
            VisibilityDiagnosis.hint(Action.WATCH_LONGER))
        assertEquals("the app made almost no connections while it was watched in the background", VisibilityDiagnosis.phrase(Cause.QUIET_WATCH))
        assertEquals("a sign-in or passcode screen was in the way", VisibilityDiagnosis.phrase(Cause.GATED))
    }

    // --- The watch's own words ------------------------------------------------------------------------------------------

    @Test fun `lengths, the next length to offer and the statement of what was checked`() {
        assertEquals(listOf(5, 10, 30), BackgroundWatches.MINUTES)
        assertEquals(10, BackgroundWatches.nextMinutes(listOf(QuietBackgroundAppReplay.quickCheck())))
        assertEquals(30, BackgroundWatches.nextMinutes(listOf(QuietBackgroundAppReplay.backgroundWatch())))
        val five = QuietBackgroundAppReplay.backgroundWatch().copy(watch = BackgroundWatch(300_000, 300_000))
        assertEquals(10, BackgroundWatches.nextMinutes(listOf(five)))
        assertEquals("10 minutes", BackgroundWatches.length(BackgroundWatch(600_000, 600_000)))
        assertEquals("45 s of a planned 5 minutes (stopped early)", BackgroundWatches.length(BackgroundWatch(300_000, 45_000, stoppedEarly = true)))
        val (en, hi) = BackgroundWatches.whatWasChecked("RollCall", BackgroundWatch(600_000, 600_000))
        assertEquals("What was checked: what RollCall sends while it runs in the background, watched for 10 minutes; its consent screen was not reached.", en)
        assertTrue(hi, hi.contains("RollCall") && hi.contains("10 मिनट") && hi.any { it in 'ऀ'..'ॿ' })
        assertNull(VerdictWords.find(en))
        assertNull(VerdictWords.find(hi))
        assertNull(VerdictWords.find(BackgroundWatches.LIMIT))
    }

    @Test fun `a watch survives a save, and other plans are written exactly as before`() {
        val watch = QuietBackgroundAppReplay.backgroundWatch().copy(watch = BackgroundWatch(600_000, 252_000, stoppedEarly = true,
            onScreen = listOf(WatchSpan(10 * s, 20 * s))))
        val back = AuditJson.decodeSessions(AuditJson.encodeSessions(listOf(watch))).single()
        assertEquals(watch.watch, back.watch)
        val plain = AuditJson.encodeSessions(listOf(QuietBackgroundAppReplay.quickCheck()))
        assertFalse(plain.contains("\"watch\""))
        val full = AuditJson.encodeSessions(listOf(QuietBackgroundAppReplay.backgroundWatch()))
        assertFalse("defaults are not written", full.contains("stoppedEarly") || full.contains("onScreen"))
    }

    // --- Reports ---------------------------------------------------------------------------------------------------------

    @Test fun `the reports say it was a background watch of 10 minutes with no consent screen driven`() {
        val sessions = listOf(QuietBackgroundAppReplay.backgroundWatch())
        val r = engine.evaluate(sessions)
        val md = MarkdownReport.build(r, sessions, emptyList(), citations)
        assertTrue(md, md.contains("- What was checked: what RollCall sends while it runs in the background, watched for 10 minutes; its consent screen was not reached."))
        assertTrue(md, md.contains("**Background watch** — 16 lookups, watched 10 minutes; the app was not opened or tapped, first seen off screen at 0.5 s"))
        assertTrue(md, md.contains("| 250.0 | app-measurement.com (app in background) |"))
        assertTrue(md, md.contains("- ${BackgroundWatches.LIMIT}"))
        val html = HtmlReport.build(r, sessions, emptyList(), citations, catalog, HtmlCoverage(statements = emptyList(), scope = "On this phone."), ZoneId.of("UTC"))
        assertTrue(html.contains("<dt>What was checked</dt><dd>What RollCall sends while it runs in the background, watched for 10 minutes; its consent screen was not reached.</dd>"))
        assertTrue(html.contains("<dt>Plans</dt><dd>Background watch</dd>"))
    }

    @Test fun `a report of an ordinary check has no watch lines`() {
        val sessions = listOf(QuietBackgroundAppReplay.quickCheck())
        val md = MarkdownReport.build(engine.evaluate(sessions), sessions, emptyList(), citations)
        assertFalse(md.contains("What was checked"))
        assertFalse(md.contains("Background watch"))
        assertTrue(md.contains("**Plan B · Reject** — 1 lookups"))
    }
}
