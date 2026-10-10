package com.dpdpxray.app.data

import com.dpdpxray.app.data.SampleAudit
import com.dpdpxray.core.model.*
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AuditComparisonTest {
    private val sessions = SampleAudit.sessions(1000)
    private val result = RulesEngine(TrackerCatalog.loadDefault()).evaluate(sessions)
    private fun bundle(id: String, time: Long, score: Int? = 21, sample: Boolean = false) = AuditBundle(
        AuditMeta(id, "com.leakyshop.demo", "LeakyShop", time, score,
            if (score == null) ScoreBand.INCONCLUSIVE else ScoreBand.AT_RISK, result.findings.size,
            sessions.map { it.plan.label }, sample, timingProfile = "normal"), sessions,
        result.copy(score = score, band = if (score == null) ScoreBand.INCONCLUSIVE else ScoreBand.AT_RISK), File("unused"),
    )

    @Test fun `selects closest earlier audit of same package and evidence mode`() {
        val current = bundle("now", 4000)
        val history = listOf(bundle("old", 1000), bundle("closest", 3000), bundle("future", 5000),
            bundle("sample", 3500, sample = true), bundle("other", 3800).let { it.copy(meta = it.meta.copy(targetPackage = "other.app")) })
        assertEquals("closest", AuditComparison.previousId(current.meta, history.map { it.meta } + current.meta))
    }

    @Test fun `same package and plans produces plus79 with findings no longer recorded`() {
        val old = bundle("old", 1000)
        val current = bundle("now", 2000, 100).copy(result = AuditResult(emptyList(), 100, ScoreBand.READY))
        val comparison = AuditComparison.compare(current, old) as AuditComparisonResult.Available
        assertEquals(79, comparison.scoreDelta)
        // C15 (info): the sample's saved consent tree, like LeakyShop's dialog, names no privacy policy.
        assertEquals(setOf(CheckId.C1, CheckId.C2, CheckId.C3, CheckId.C4, CheckId.C5, CheckId.C6, CheckId.C7, CheckId.C8, CheckId.C10, CheckId.C15), comparison.noLongerRecorded)
        assertTrue(comparison.newlyRecorded.isEmpty())
    }

    @Test fun `separate demo flavour packages are not silently compared`() {
        val now = bundle("now", 2000).let { it.copy(meta = it.meta.copy(targetPackage = "com.leakyshop.demo.fixed")) }
        assertTrue(AuditComparison.compare(now, bundle("old", 1000)) is AuditComparisonResult.Unavailable)
    }

    @Test fun `fewer audit plans cannot claim an improvement`() {
        val now = bundle("now", 2000).let { it.copy(meta = it.meta.copy(plans = listOf(AuditPlan.ACCEPT.label))) }
        assertTrue(AuditComparison.compare(now, bundle("old", 1000)) is AuditComparisonResult.Unavailable)
    }

    @Test fun `an inconclusive result cannot acquire a score delta`() {
        assertTrue(AuditComparison.compare(bundle("now", 2000, null), bundle("old", 1000)) is AuditComparisonResult.Unavailable)
    }

    @Test fun `samples never compare with a real audit`() {
        assertTrue(AuditComparison.compare(bundle("now", 2000), bundle("old", 1000, sample = true)) is AuditComparisonResult.Unavailable)
    }

    @Test fun `regression reports a negative score change and new check`() {
        val old = bundle("old", 1000, 100).copy(result = AuditResult(emptyList(), 100, ScoreBand.READY))
        val now = bundle("now", 2000, 80).copy(result = result.copy(findings = result.findings.filter { it.checkId == CheckId.C2 }, score = 80))
        val comparison = AuditComparison.compare(now, old) as AuditComparisonResult.Available
        assertEquals(-20, comparison.scoreDelta)
        assertEquals(setOf(CheckId.C2), comparison.newlyRecorded)
        assertTrue(comparison.noLongerRecorded.isEmpty())
    }

    @Test fun `a missing saved audit stays unavailable`() {
        assertTrue(AuditComparison.compare(bundle("now", 2000), null) is AuditComparisonResult.Unavailable)
    }

    @Test fun `missed choice cannot masquerade as score improvement`() {
        val now = bundle("now", 2000, 51).let { b -> b.copy(sessions = b.sessions.map {
            if (it.plan == AuditPlan.REJECT) it.copy(consentEvents = emptyList()) else it
        }) }
        val result = AuditComparison.compare(now, bundle("old", 1000))
        assertTrue(result is AuditComparisonResult.Unavailable)
        assertTrue((result as AuditComparisonResult.Unavailable).reason.contains("choice"))
    }
}
