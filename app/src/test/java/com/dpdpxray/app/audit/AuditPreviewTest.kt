package com.dpdpxray.app.audit

import com.dpdpxray.app.data.AuditBundle
import com.dpdpxray.app.data.AuditComparison
import com.dpdpxray.app.data.AuditComparisonResult
import com.dpdpxray.app.data.AuditMeta
import com.dpdpxray.app.data.SampleAudit
import com.dpdpxray.app.report.EvidenceReport
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.report.AuditJson
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Protects against shrinking normal coverage, losing preview provenance or false score improvements. */
@OptIn(ExperimentalCoroutinesApi::class)
class AuditPreviewTest {
    private val full = AuditConfig("demo.test", "Demo", listOf(AuditPlan.SILENT, AuditPlan.REJECT, AuditPlan.ACCEPT))
    private val quick = full.copy(plans = listOf(AuditPlan.ACCEPT))

    @Test fun `preview finishes observation before a later burst that normal capture would see`() = runTest {
        val preview = AuditTimingPolicy.forAudit(quick.copy(fastPreview = true))
        var laterBurst = false
        var finished = false
        launch { delay(14_000); laterBurst = true }
        launch { preview.observe(AuditPlan.ACCEPT); finished = true }
        advanceTimeBy(8_001)
        assertTrue("Preview must finish its shorter observation, not wait the normal 15 seconds", finished)
        assertFalse(laterBurst)
        advanceUntilIdle()
    }

    @Test fun `normal full keeps observing when delayed activity arrives after preview has ended`() = runTest {
        var laterBurst = false
        var finished = false
        launch { delay(14_000); laterBurst = true }
        launch { AuditTimingPolicy.forAudit(full).observe(AuditPlan.REJECT); finished = true }
        advanceTimeBy(14_001)
        assertTrue(laterBurst)
        assertFalse("Normal Full must not inherit Preview's shortened observation", finished)
        advanceUntilIdle()
        assertEquals(15_000L, currentTime)
    }

    @Test fun `normal silent plan does not inherit the old short demo window`() = runTest {
        var finished = false
        launch { AuditTimingPolicy.forAudit(full).observe(AuditPlan.SILENT); finished = true }
        advanceTimeBy(12_001)
        assertFalse(finished)
        advanceUntilIdle()
        assertEquals(25_000L, currentTime)
    }

    @Test fun `normal quick still observes delayed activity`() = runTest {
        var finished = false
        launch { AuditTimingPolicy.forAudit(quick).observe(AuditPlan.ACCEPT); finished = true }
        advanceTimeBy(8_001)
        assertFalse(finished)
        advanceUntilIdle()
        assertEquals(15_000L, currentTime)
    }

    @Test fun `preview cannot be disguised as a three-plan full config`() {
        try {
            full.copy(fastPreview = true)
            fail("Fast Preview must be an explicitly separate single-plan check")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun `old metadata has unknown timing rather than invented normal provenance`() {
        val old = """{"id":"old","targetPackage":"demo.test","targetLabel":"Demo","createdAtMs":1,"score":100,"band":"READY","findingCount":0,"plans":["accept"]}"""
        val meta = AuditJson.json.decodeFromString(AuditMeta.serializer(), old)
        assertNull(meta.timingProfile)
        assertTrue(AuditTimingPolicy.coverageHint(meta.timingProfile).contains("not recorded"))
    }

    @Test fun `saved preview mode survives the metadata boundary`() {
        val meta = bundle("new", 2, "fast_preview").meta
        val saved = AuditJson.json.encodeToString(AuditMeta.serializer(), meta)
        val loaded = AuditJson.json.decodeFromString(AuditMeta.serializer(), saved)
        assertTrue(AuditTimingPolicy.coverageHint(loaded.timingProfile).contains("Fast Preview"))
        assertTrue(AuditTimingPolicy.coverageHint(loaded.timingProfile).contains("delayed"))
    }

    @Test fun `preview report explains its short window and lack of refusal testing`() {
        val report = EvidenceReport.annotate("# Live audit", bundle("new", 2, "fast_preview").sessions, "fast_preview")
        assertTrue(report.contains("Fast Preview"))
        assertTrue(report.contains("delayed"))
        assertTrue(report.contains("Reject"))
        assertEquals(report, EvidenceReport.annotate(report, bundle("new", 2, "fast_preview").sessions, "fast_preview"))
    }

    @Test fun `shorter capture cannot be presented as a before-after score improvement`() {
        val comparison = AuditComparison.compare(bundle("new", 2, "fast_preview"), bundle("old", 1, "normal"))
        assertTrue(comparison is AuditComparisonResult.Unavailable)
        assertTrue((comparison as AuditComparisonResult.Unavailable).reason.contains("timing"))
    }

    @Test fun `unknown historical capture mode cannot establish comparable timing`() {
        assertTrue(AuditComparison.compare(bundle("new", 2, "normal"), bundle("old", 1, null)) is AuditComparisonResult.Unavailable)
    }

    @Test fun `same known timing and completed plans remain comparable`() {
        assertTrue(AuditComparison.compare(bundle("new", 2, "normal"), bundle("old", 1, "normal")) is AuditComparisonResult.Available)
    }

    @Test fun `preview consent wait is shorter but normal wait is retained`() = runTest {
        suspend fun missedScreen(config: AuditConfig): Long {
            val began = currentTime
            val screen = ConsentScreenWaiter(probe = { null }, clockMs = { currentTime }, pause = { delay(it) })
                .await(AuditTimingPolicy.forAudit(config).waitConsentMs)
            assertNull(screen)
            return currentTime - began
        }
        assertEquals(12_000L, missedScreen(quick.copy(fastPreview = true)))
        assertEquals(20_000L, missedScreen(full))
    }

    @Test fun `preview dwell is shorter and normal dwell stays unchanged`() {
        assertEquals(1_500L, AuditTimingPolicy.forAudit(quick.copy(fastPreview = true)).dwellMs)
        assertEquals(2_500L, AuditTimingPolicy.forAudit(full).dwellMs)
    }

    @Test fun `legacy live exports disclose unknown timing without duplicate coverage`() {
        val legacy = "## Audit coverage\n\nExisting evidence limits.\n\n---\n\n# Old audit"
        val sessions = bundle("old", 1, null).sessions
        val rendered = EvidenceReport.annotate(legacy, sessions)
        assertTrue(rendered.contains("timing profile not recorded"))
        assertEquals(1, Regex("## Audit coverage").findAll(rendered).count())
        assertEquals(rendered, EvidenceReport.annotate(rendered, sessions))
    }

    @Test fun `raw legacy live reports also disclose unknown timing`() {
        assertTrue(EvidenceReport.annotate("# Old audit", bundle("old", 1, null).sessions)
            .contains("timing profile not recorded"))
    }

    @Test fun `synthetic reports do not claim an older live timing profile`() {
        val report = EvidenceReport.annotate("# Synthetic sample", bundle("sample", 1, null).sessions, sample = true)
        assertFalse(report.contains("older audit"))
        assertTrue(report.contains("Synthetic sample"))
    }

    private fun bundle(id: String, time: Long, profile: String?): AuditBundle {
        val sessions = SampleAudit.sessions(1000).filter { it.plan == AuditPlan.ACCEPT }
        val result = RulesEngine(TrackerCatalog.loadDefault()).evaluate(sessions)
        return AuditBundle(AuditMeta(id, "com.leakyshop.demo", "LeakyShop", time, result.score, result.band,
            result.findings.size, sessions.map { it.plan.label }, timingProfile = profile), sessions, result, File("unused"))
    }
}
