package com.dpdpxray.app.data

import com.dpdpxray.core.model.AppManifestFacts
import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import com.dpdpxray.core.update.AppVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime

class UpdateDiffTest {
    private val sessions = SampleAudit.sessions(1000)
    private val result = RulesEngine(TrackerCatalog.loadDefault()).evaluate(sessions)
    private val oct10 = ZonedDateTime.of(2026, 10, 10, 14, 30, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    /** A saved audit of LeakyShop at [version] (null = not recorded) with exactly [checks] as findings. */
    private fun bundle(id: String, time: Long, version: AppVersion?, checks: Set<CheckId>, score: Int, metaVersion: Boolean = true) = AuditBundle(
        AuditMeta(id, "com.leakyshop.demo", "LeakyShop", time, score, band(score), checks.size, sessions.map { it.plan.label },
            timingProfile = "normal",
            targetVersionName = version?.name?.takeIf { metaVersion }, targetVersionCode = version?.code?.takeIf { metaVersion }),
        sessions.map { s -> s.copy(manifestFacts = version?.let { AppManifestFacts(versionName = it.name, versionCode = it.code) }) },
        AuditResult(result.findings.filter { it.checkId in checks }.ifEmpty { emptyList() }, score, band(score)),
        File("unused"),
    )

    private fun band(score: Int) = when { score >= 85 -> ScoreBand.READY; score >= 60 -> ScoreBand.NEEDS_WORK; else -> ScoreBand.AT_RISK }

    private val v10 = AppVersion("1.0", 1)
    private val v11 = AppVersion("1.1", 2)
    private val before = setOf(CheckId.C1, CheckId.C2, CheckId.C3, CheckId.C4, CheckId.C10)
    private val after = setOf(CheckId.C1, CheckId.C3, CheckId.C10, CheckId.C5)

    @Test
    fun `a comparison across an update carries both versions, both scores and the three lists`() {
        val old = bundle("old", oct10, v10, before, 8)
        val now = bundle("now", oct10 + 86_400_000, v11, after, 51)
        val c = AuditComparison.compare(now, old) as AuditComparisonResult.Available
        assertEquals(43, c.scoreDelta)
        assertEquals(8, c.previousScore)
        assertEquals(51, c.currentScore)
        assertEquals(setOf(CheckId.C2, CheckId.C4), c.noLongerRecorded)
        assertEquals(setOf(CheckId.C5), c.newlyRecorded)
        assertEquals(setOf(CheckId.C1, CheckId.C3, CheckId.C10), c.stillRecorded)
        assertEquals(VersionUpdate(v10, v11), AuditComparison.update(now, old))
    }

    @Test
    fun `the update wording says what changed between the two checks and that both are kept`() {
        val old = bundle("old", oct10, v10, before, 8)
        val now = bundle("now", oct10 + 86_400_000, v11, after, 51)
        val c = AuditComparison.compare(now, old) as AuditComparisonResult.Available
        val u = AuditComparison.update(now, old)!!
        assertEquals("Update 1.0 → 1.1", UpdateDiffWording.title(u))
        assertEquals("8 → 51 (+43)", UpdateDiffWording.score(c))
        assertEquals("Update 1.0 → 1.1 · 8 → 51 (+43)", UpdateDiffWording.summary(u, c))
        assertEquals("Fixed in this update", UpdateDiffWording.FIXED)
        assertEquals("New in this update", UpdateDiffWording.NEW)
        assertEquals("Still there", UpdateDiffWording.STILL)
        assertEquals(listOf("C2 · Tracker hosts looked up after the user refused", "C4 · No clear way to refuse"), UpdateDiffWording.lines(c.noLongerRecorded))
        assertEquals(listOf("C1 · Tracker hosts looked up before consent", "C3 · Pre-ticked consent option", "C10 · Device identifiers likely shared before consent"), UpdateDiffWording.lines(c.stillRecorded))
        assertEquals(
            "Compared with your check of version 1.0 on 10 Oct 2026. Both checks are saved; nothing was overwritten.",
            UpdateDiffWording.honest(u, c.previousTimeMs, ZoneOffset.UTC),
        )
    }

    @Test
    fun `a falling score and an empty list read plainly`() {
        val old = bundle("old", oct10, v10, emptySet(), 100)
        val now = bundle("now", oct10 + 1, v11, setOf(CheckId.C2), 80)
        val c = AuditComparison.compare(now, old) as AuditComparisonResult.Available
        assertEquals("100 → 80 (-20)", UpdateDiffWording.score(c))
        assertEquals(listOf("None"), UpdateDiffWording.lines(c.noLongerRecorded))
        assertEquals("8 → 8 (+0)", UpdateDiffWording.score(c.copy(previousScore = 8, currentScore = 8, scoreDelta = 0)))
    }

    @Test
    fun `the same version is not an update and keeps the existing comparison`() {
        val old = bundle("old", oct10, v10, before, 8)
        val now = bundle("now", oct10 + 1, v10, after, 51)
        assertNull(AuditComparison.update(now, old))
        val c = AuditComparison.compare(now, old) as AuditComparisonResult.Available
        assertEquals(43, c.scoreDelta)
        assertEquals(setOf(CheckId.C2, CheckId.C4), c.noLongerRecorded)
    }

    @Test
    fun `an unrecorded version on either side is not called an update`() {
        assertNull(AuditComparison.update(bundle("now", 2, v11, after, 51), bundle("old", 1, null, before, 8)))
        assertNull(AuditComparison.update(bundle("now", 2, null, after, 51), bundle("old", 1, v10, before, 8)))
        assertNull(AuditComparison.update(bundle("now", 2, v11, after, 51), null))
    }

    @Test
    fun `older audits without meta versions use the versions their sessions recorded`() {
        val old = bundle("old", 1, v10, before, 8, metaVersion = false)
        val now = bundle("now", 2, v11, after, 51, metaVersion = false)
        assertEquals(VersionUpdate(v10, v11), AuditComparison.update(now, old))
    }

    @Test
    fun `a downgrade is still an update between two versions`() {
        assertEquals(VersionUpdate(v11, v10), AuditComparison.update(bundle("now", 2, v10, before, 8), bundle("old", 1, v11, after, 51)))
        assertEquals("Update 1.1 → 1.0", UpdateDiffWording.title(VersionUpdate(v11, v10)))
    }

    @Test
    fun `the result page points to the update comparison near the top`() {
        val old = bundle("old", oct10, v10, before, 8)
        val now = bundle("now", oct10 + 86_400_000, v11, after, 51)
        val u = AuditComparison.update(now, old)!!
        val c = AuditComparison.compare(now, old)
        assertEquals("This check is of version 1.1. Your previous check was of version 1.0: 8 → 51 (+43). See Before / after below.", UpdateDiffWording.banner(u, c))
        assertEquals(
            "This check is of version 1.1. Your previous check was of version 1.0. See Before / after below.",
            UpdateDiffWording.banner(u, AuditComparisonResult.Unavailable("The audit plans changed.")),
        )
    }

    @Test
    fun `the shared update report is Markdown with the same facts and the caveat`() {
        val old = bundle("old", oct10, v10, before, 8)
        val now = bundle("now", oct10 + 86_400_000, v11, after, 51)
        val c = AuditComparison.compare(now, old) as AuditComparisonResult.Available
        val md = UpdateDiffWording.markdown("LeakyShop", AuditComparison.update(now, old)!!, c, ZoneOffset.UTC)
        assertTrue(md.startsWith("# LeakyShop: Update 1.0 → 1.1"))
        assertTrue(md.contains("Score: 8 → 51 (+43)"))
        assertTrue(md.contains("## Fixed in this update\n- C2 · Tracker hosts looked up after the user refused\n- C4 · No clear way to refuse"))
        assertTrue(md.contains("## New in this update\n- C5 · Bundled consent for several purposes"))
        assertTrue(md.contains("## Still there\n- C1 · Tracker hosts looked up before consent"))
        assertTrue(md.contains("Both checks are saved; nothing was overwritten."))
        assertTrue(md.contains(UpdateDiffWording.CAVEAT))
        assertFalse(md.contains("compliant"))
    }
}
