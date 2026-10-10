package com.dpdpxray.app.ui.guide

import com.dpdpxray.app.control.Preflight
import com.dpdpxray.app.data.AuditMeta
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.ScoreBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupAndRecentTest {
    private fun pf(vpn: Boolean = true, watcher: Boolean = true, privateDns: Boolean = false, running: Boolean = false, granted: Boolean = false) =
        Preflight(vpn, watcher, running, granted, privateDns, modelFound = false, modelLoaded = false)

    @Test fun `required items keep the canAudit meaning`() {
        listOf(pf(), pf(vpn = false), pf(watcher = false), pf(vpn = false, watcher = false)).forEach { p ->
            val requiredDone = SetupSteps.from(p).filter { it.need == SetupNeed.REQUIRED }.all { it.done }
            assertEquals(p.canAudit, requiredDone)
        }
    }

    @Test fun `todo lists required before recommended and skips optional`() {
        val todo = SetupSteps.todo(SetupSteps.from(pf(vpn = false, watcher = false, privateDns = true)))
        assertEquals(listOf(SetupKind.VPN, SetupKind.WATCHER, SetupKind.PRIVATE_DNS), todo.map { it.kind })
        assertTrue(todo.all { it.fixable })
        assertTrue(SetupSteps.todo(SetupSteps.from(pf())).isEmpty())
    }

    @Test fun `shizuku is optional and only fixable when it is running`() {
        val off = SetupSteps.from(pf()).single { it.kind == SetupKind.SHIZUKU }
        assertEquals(SetupNeed.OPTIONAL, off.need)
        assertFalse(off.done)
        assertFalse(off.fixable)
        val running = SetupSteps.from(pf(running = true)).single { it.kind == SetupKind.SHIZUKU }
        assertTrue(running.fixable)
        assertTrue(SetupSteps.from(pf(running = true, granted = true)).single { it.kind == SetupKind.SHIZUKU }.done)
    }

    @Test fun `private dns is done only when it is off`() {
        assertTrue(SetupSteps.from(pf()).single { it.kind == SetupKind.PRIVATE_DNS }.done)
        assertFalse(SetupSteps.from(pf(privateDns = true)).single { it.kind == SetupKind.PRIVATE_DNS }.done)
    }

    private fun meta(band: ScoreBand = ScoreBand.AT_RISK, score: Int? = 21, plans: List<AuditPlan> = AuditPlan.entries, sample: Boolean = false, profile: String? = "normal") =
        AuditMeta("a", "com.leakyshop.demo", "LeakyShop", 0, score, band, 5, plans.map { it.label }, sample, profile)

    @Test fun `recent check coverage warnings come from saved metadata`() {
        assertNull(RecentChecks.coverageWarning(meta()))
        assertNull(RecentChecks.coverageWarning(meta(sample = true, profile = null)))
        assertEquals("Inconclusive: too little was captured", RecentChecks.coverageWarning(meta(ScoreBand.INCONCLUSIVE, null)))
        assertEquals("Fast Preview: limited evidence", RecentChecks.coverageWarning(meta(plans = listOf(AuditPlan.ACCEPT), profile = "fast_preview")))
        assertEquals("Single run: Reject not tested", RecentChecks.coverageWarning(meta(plans = listOf(AuditPlan.ACCEPT))))
        assertEquals("Timing not recorded", RecentChecks.coverageWarning(meta(profile = null)))
    }

    @Test fun `score text follows the results screen rule`() {
        assertEquals("21", RecentChecks.scoreText(meta()))
        assertEquals("?", RecentChecks.scoreText(meta(ScoreBand.INCONCLUSIVE, null)))
        assertEquals("?", RecentChecks.scoreText(meta(ScoreBand.INCONCLUSIVE, 40)))
    }

    @Test fun `onboarding shows once and never hides a running check`() {
        assertEquals("onboarding", OnboardingGate.startRoute(onboardingDone = false, auditRunning = false))
        assertEquals("home", OnboardingGate.startRoute(onboardingDone = true, auditRunning = false))
        assertEquals("home", OnboardingGate.startRoute(onboardingDone = false, auditRunning = true))
    }

    @Test fun `the same step is not repeated within the gap but a new language or step is`() {
        val d = SpeechDedupe(gapMs = 45_000)
        assertTrue(d.shouldSpeak("home.ready", GuideLang.EN, 0))
        assertFalse(d.shouldSpeak("home.ready", GuideLang.EN, 10_000))
        assertTrue(d.shouldSpeak("home.ready", GuideLang.HI, 11_000))
        assertTrue(d.shouldSpeak("pick", GuideLang.HI, 12_000))
        assertTrue(d.shouldSpeak("home.ready", GuideLang.HI, 13_000))
        assertTrue(d.shouldSpeak("home.ready", GuideLang.HI, 13_000 + 45_000))
        d.reset()
        assertTrue(d.shouldSpeak("home.ready", GuideLang.HI, 58_001))
    }
}
