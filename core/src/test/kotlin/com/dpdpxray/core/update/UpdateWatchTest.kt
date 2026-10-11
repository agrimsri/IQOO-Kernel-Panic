package com.dpdpxray.core.update

import com.dpdpxray.core.model.AppManifestFacts
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.ScoreBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateWatchTest {
    private val v10 = AppVersion("1.0", 1)
    private val v11 = AppVersion("1.1", 2)

    private fun checked(
        id: String, at: Long, version: AppVersion? = v10, pkg: String = "com.leakyshop.demo", score: Int? = 8,
        band: ScoreBand = ScoreBand.AT_RISK, sample: Boolean = false, label: String = "LeakyShop",
    ) = CheckedApp(pkg, label, id, at, version, score, band, sample)

    private fun installed(version: AppVersion?, updatedAt: Long? = 5_000, pkg: String = "com.leakyshop.demo") =
        mapOf(pkg to InstalledApp(pkg, version, updatedAt))

    @Test
    fun `an update of a checked app is one item with both versions and the last result`() {
        val items = UpdateWatch.updatedSinceLastCheck(listOf(checked("a1", 1_000)), installed(v11))
        assertEquals(1, items.size)
        val item = items.single()
        assertEquals(UpdateKind.UPDATED, item.kind)
        assertEquals("com.leakyshop.demo", item.packageName)
        assertEquals("LeakyShop", item.label)
        assertEquals(v10, item.oldVersion)
        assertEquals(v11, item.newVersion)
        assertEquals(8, item.lastScore)
        assertEquals(ScoreBand.AT_RISK, item.lastBand)
        assertEquals("a1", item.lastAuditId)
        assertEquals(1_000L, item.lastAuditedAtMs)
        assertEquals("1.0 → 1.1", item.versionChange)
    }

    @Test
    fun `the same version installed now gives no item`() {
        assertTrue(UpdateWatch.updatedSinceLastCheck(listOf(checked("a1", 1_000)), installed(v10)).isEmpty())
    }

    @Test
    fun `the same version reinstalled after the check gives no item`() {
        assertTrue(UpdateWatch.updatedSinceLastCheck(listOf(checked("a1", 1_000)), installed(v10, updatedAt = 9_000)).isEmpty())
    }

    @Test
    fun `an uninstalled app gives no item`() {
        assertTrue(UpdateWatch.updatedSinceLastCheck(listOf(checked("a1", 1_000)), emptyMap()).isEmpty())
    }

    @Test
    fun `a downgrade is reported as a downgrade`() {
        val item = UpdateWatch.updatedSinceLastCheck(listOf(checked("a1", 1_000, version = v11)), installed(v10)).single()
        assertEquals(UpdateKind.DOWNGRADED, item.kind)
        assertEquals("1.1 → 1.0", item.versionChange)
    }

    @Test
    fun `many audits of one app use the latest one`() {
        val history = listOf(
            checked("old", 1_000, version = AppVersion("0.9", 0), score = 40, band = ScoreBand.NEEDS_WORK),
            checked("latest", 3_000, version = v10, score = 8),
            checked("middle", 2_000, version = AppVersion("0.9", 0)),
        )
        val item = UpdateWatch.updatedSinceLastCheck(history, installed(v11)).single()
        assertEquals("latest", item.lastAuditId)
        assertEquals(v10, item.oldVersion)
        assertEquals(8, item.lastScore)
        // Already checked at the version installed now: nothing to report, even though older audits saw older versions.
        assertTrue(UpdateWatch.updatedSinceLastCheck(history + checked("newest", 4_000, version = v11), installed(v11)).isEmpty())
    }

    @Test
    fun `audits at the same time pick the larger id so the choice is stable`() {
        val item = UpdateWatch.updatedSinceLastCheck(listOf(checked("a", 1_000), checked("b", 1_000, score = 9)), installed(v11)).single()
        assertEquals("b", item.lastAuditId)
    }

    @Test
    fun `sample audits are ignored`() {
        assertTrue(UpdateWatch.updatedSinceLastCheck(listOf(checked("s", 1_000, sample = true)), installed(v11)).isEmpty())
        // A sample newer than a real audit does not hide the real one.
        val items = UpdateWatch.updatedSinceLastCheck(listOf(checked("real", 1_000), checked("s", 2_000, version = v11, sample = true)), installed(v11))
        assertEquals("real", items.single().lastAuditId)
    }

    @Test
    fun `version not recorded at the check still offers a re-check when Android says the app was updated after it`() {
        val item = UpdateWatch.updatedSinceLastCheck(listOf(checked("a1", 1_000, version = null)), installed(v11, updatedAt = 2_000)).single()
        assertEquals(UpdateKind.VERSION_NOT_RECORDED, item.kind)
        assertNull(item.oldVersion)
        assertEquals("version not recorded → 1.1", item.versionChange)
    }

    @Test
    fun `version not recorded and no update since the check gives no item`() {
        assertTrue(UpdateWatch.updatedSinceLastCheck(listOf(checked("a1", 1_000, version = null)), installed(v11, updatedAt = 900)).isEmpty())
        assertTrue(UpdateWatch.updatedSinceLastCheck(listOf(checked("a1", 1_000, version = null)), installed(v11, updatedAt = null)).isEmpty())
    }

    @Test
    fun `an installed version Android does not report gives no item`() {
        assertTrue(UpdateWatch.updatedSinceLastCheck(listOf(checked("a1", 1_000)), installed(null)).isEmpty())
    }

    @Test
    fun `several apps are listed most recently checked first`() {
        val items = UpdateWatch.updatedSinceLastCheck(
            listOf(checked("a", 1_000, pkg = "a.app", label = "A"), checked("b", 2_000, pkg = "b.app", label = "B"), checked("c", 3_000, pkg = "c.app", label = "C")),
            installed(v11, pkg = "a.app") + installed(v11, pkg = "b.app") + installed(v10, pkg = "c.app"),
        )
        assertEquals(listOf("b.app", "a.app"), items.map { it.packageName })
    }

    @Test
    fun `an inconclusive last check keeps its band and no score`() {
        val item = UpdateWatch.updatedSinceLastCheck(listOf(checked("a1", 1_000, score = null, band = ScoreBand.INCONCLUSIVE)), installed(v11)).single()
        assertNull(item.lastScore)
        assertEquals(ScoreBand.INCONCLUSIVE, item.lastBand)
    }

    @Test
    fun `version labels and change text`() {
        assertEquals("1.1", v11.label())
        assertEquals("code 7", AppVersion(null, 7).label())
        assertEquals("version not recorded", AppVersion.label(null))
        // Same name, different code: the codes tell the builds apart.
        assertEquals("1.0 (code 1) → 1.0 (code 2)", AppVersion.change(AppVersion("1.0", 1), AppVersion("1.0", 2)))
        assertNull(AppVersion.of(" ", null))
        assertEquals(AppVersion(null, 3), AppVersion.of("", 3))
    }

    @Test
    fun `the audited version comes from the first session whose manifest was read`() {
        val base = AuditSession(id = "s", targetPackage = "p", targetLabel = "P", plan = AuditPlan.SILENT)
        val sessions = listOf(
            base,
            base.copy(plan = AuditPlan.REJECT, manifestFacts = AppManifestFacts(versionName = "1.1", versionCode = 2)),
            base.copy(plan = AuditPlan.ACCEPT, manifestFacts = AppManifestFacts(versionName = "9", versionCode = 9)),
        )
        assertEquals(v11, AppVersion.ofSessions(sessions))
        assertNull(AppVersion.ofSessions(listOf(base)))
        // Older audits whose manifest read recorded no version stay "not recorded".
        assertNull(AppVersion.ofSessions(listOf(base.copy(manifestFacts = AppManifestFacts()))))
    }

    @Test
    fun `the dedup key is the package and the version code installed now`() {
        val item = UpdateWatch.updatedSinceLastCheck(listOf(checked("a1", 1_000)), installed(v11)).single()
        assertEquals(2L, item.newVersion?.code)
    }
}
