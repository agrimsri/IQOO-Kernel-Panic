package com.dpdpxray.app.data

import com.dpdpxray.app.report.AuditJsonExport
import com.dpdpxray.core.export.AuditBackups
import com.dpdpxray.core.export.AuditBundles
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

/** Back up / restore on the phone: saved audits to traceveil.backup.v1 and back. Sample data, not device runs. */
class CheckBackupsTest {
    @get:Rule val temp = TemporaryFolder()

    private val auditedAt = 1_791_590_915_883L
    private val sessions = SampleAudit.sessions(auditedAt)
    private val result = RulesEngine(TrackerCatalog.loadDefault()).evaluate(sessions)

    private fun meta(id: String) = AuditMeta(
        id = id, targetPackage = sessions.first().targetPackage, targetLabel = sessions.first().targetLabel,
        createdAtMs = auditedAt, score = result.score, band = result.band, findingCount = result.findings.size,
        plans = sessions.map { it.plan.label }, timingProfile = "normal", inconclusiveReason = null,
    )

    private val readings = sessions.map { SessionNetworkReadings(it.id, 1, 2, 100, 300, 3, 5, 0, 1, 3) }

    private fun saved(id: String, withReadings: Boolean = true) =
        AuditBundle(meta(id), sessions, result, temp.root, if (withReadings) readings else emptyList())

    @Test
    fun `a saved audit survives backup and restore with its meta, evidence and readings`() {
        val originals = listOf(saved("audit-1"), saved("audit-2", withReadings = false))
        val text = AuditBackups.encode(originals.map { AuditJsonExport.build(it, "1.2.0", exportedAt = "2026-10-10T09:00:00Z") }, "2026-10-10T09:00:00Z", "1.2.0")
        val plan = AuditBackups.plan(AuditBackups.read(text), existingIds = setOf("audit-2"))
        val back = plan.toRestore.single()
        assertEquals(meta("audit-1"), CheckBackups.metaOf(back))
        assertEquals(sessions, back.sessions)
        assertEquals(result, back.result)
        assertEquals(readings, CheckBackups.readingsOf(back))
        assertEquals("Restored 1 check, skipped 1 already here.", AuditBackups.summary(plan))
    }

    @Test
    fun `an audit with no readings restores none, never zeros`() {
        val b = AuditJsonExport.build(saved("audit-3", withReadings = false), "1.2.0")
        assertEquals(emptyList<SessionNetworkReadings>(), CheckBackups.readingsOf(b))
        val broken = b.copy(networkReadings = JsonArray(listOf(JsonPrimitive("not a reading"))))
        assertEquals(emptyList<SessionNetworkReadings>(), CheckBackups.readingsOf(broken))
    }

    @Test
    fun `meta written by another tool is rebuilt from the bundle itself`() {
        val b = AuditJsonExport.build(saved("audit-4"), "1.2.0").copy(meta = buildJsonObject { put("id", "audit-4") })
        val m = CheckBackups.metaOf(b)
        assertEquals("audit-4", m.id)
        assertEquals(b.app.packageName, m.targetPackage)
        assertEquals(b.app.label, m.targetLabel)
        assertEquals(result.score, m.score)
        assertEquals(result.band, m.band)
        assertEquals(result.findings.size, m.findingCount)
        assertEquals(sessions.map { it.plan.label }, m.plans)
        assertEquals(sessions.minOf { it.startedAtEpochMs }, m.createdAtMs)
        assertNull(m.timingProfile)
        assertEquals("audit-4", AuditBundles.metaOf(b).id)
    }

    @Test
    fun `reading a picked file stops at the size limit`() {
        val small = ByteArray(100) { it.toByte() }
        assertArrayEquals(small, CheckBackups.readBounded(ByteArrayInputStream(small), 100))
        assertNull(CheckBackups.readBounded(ByteArrayInputStream(ByteArray(101)), 100))
        assertArrayEquals(ByteArray(0), CheckBackups.readBounded(ByteArrayInputStream(ByteArray(0)), 100))
    }
}
