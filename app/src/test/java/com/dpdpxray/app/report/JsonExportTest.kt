package com.dpdpxray.app.report

import com.dpdpxray.app.data.AuditBundle
import com.dpdpxray.app.data.AuditMeta
import com.dpdpxray.app.data.SampleAudit
import com.dpdpxray.app.data.SessionNetworkReadings
import com.dpdpxray.core.export.AuditBundles
import com.dpdpxray.core.export.BundleMeta
import com.dpdpxray.core.quarantine.QuarantineRecorder
import com.dpdpxray.core.quarantine.QuarantineReports
import com.dpdpxray.core.quarantine.QuarantineStopReason
import com.dpdpxray.core.report.AuditJson
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The phone's "Export audit data (JSON)" and quarantine "Export report (JSON)" files. Sample data, not device runs. */
class JsonExportTest {
    @get:Rule val temp = TemporaryFolder()

    private val auditedAt = 1_791_590_915_883L
    private val sessions = SampleAudit.sessions(auditedAt)
    private val result = RulesEngine(TrackerCatalog.loadDefault()).evaluate(sessions)

    private fun meta(sample: Boolean) = AuditMeta(
        id = "audit-1791590915883", targetPackage = sessions.first().targetPackage, targetLabel = sessions.first().targetLabel,
        createdAtMs = auditedAt, score = result.score, band = result.band, findingCount = result.findings.size,
        plans = sessions.map { it.plan.label }, sample = sample, timingProfile = "normal",
    )

    private fun readings() = sessions.map { SessionNetworkReadings(it.id, 1, 2, 100, 300, 3, 5, 0, 1, 3) }

    private fun bundle(sample: Boolean = false, readings: List<SessionNetworkReadings> = emptyList()) =
        AuditBundle(meta(sample), sessions, result, temp.root, readings)

    @Test
    fun `audit export carries the saved audit exactly`() {
        val b = bundle(readings = readings())
        val decoded = AuditBundles.decode(AuditBundles.encode(AuditJsonExport.build(b, "1.1.0", exportedAt = "2026-10-10T08:00:00Z")))
        assertEquals("traceveil.audit.v1", decoded.schema)
        assertEquals("2026-10-10T08:00:00Z", decoded.exportedAt)
        assertEquals(b.meta.targetPackage, decoded.app.packageName)
        assertEquals(b.meta.targetLabel, decoded.app.label)
        assertEquals(sessions, decoded.sessions)
        assertEquals(result, decoded.result)
        assertEquals("TraceVeil", decoded.tool.name)
        assertEquals("1.1.0", decoded.tool.version)
        assertEquals(AuditJson.json.encodeToJsonElement(AuditMeta.serializer(), b.meta), decoded.meta)
        assertEquals(AuditJson.json.encodeToJsonElement(ListSerializer(SessionNetworkReadings.serializer()), readings()), decoded.networkReadings)
        assertNull("a real audit carries no sample note", decoded.note)
    }

    @Test
    fun `missing upload readings are exported as null, not as an empty measurement`() {
        val text = AuditBundles.encode(AuditJsonExport.build(bundle(), "1.1.0"))
        assertEquals(JsonNull, kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject["networkReadings"])
    }

    @Test
    fun `core meta view stays in step with the app's AuditMeta`() {
        val appKeys = AuditJson.json.encodeToJsonElement(AuditMeta.serializer(), meta(false)).jsonObject.keys
        val coreKeys = AuditJson.json.encodeToJsonElement(BundleMeta.serializer(), BundleMeta()).jsonObject.keys
        assertEquals(appKeys, coreKeys)
        val view = AuditBundles.metaOf(AuditJsonExport.build(bundle(), "1.1.0"))
        assertEquals(result.score, view.score)
        assertEquals(result.band, view.band)
        assertEquals("normal", view.timingProfile)
    }

    @Test
    fun `sample audits are marked synthetic in the export`() {
        val exported = AuditJsonExport.build(bundle(sample = true), "1.1.0")
        assertTrue(exported.note!!.contains("synthetic", ignoreCase = true))
        assertEquals(true, AuditBundles.metaOf(exported).sample)
    }

    @Test
    fun `audit export file is written next to the audit and decodes`() {
        val file = AuditJsonExport.write(bundle(), "1.1.0")
        assertEquals(temp.root, file.parentFile)
        assertEquals("TraceVeil-audit-audit-1791590915883.json", file.name)
        assertEquals(sessions, AuditBundles.decode(file.readText()).sessions)
    }

    @Test
    fun `quarantine export wraps the saved report and decodes`() {
        val recorder = QuarantineRecorder(setOf("10.111.0.53"))
        recorder.recordDns(1_500_000_000, "panel.example.com", 1, "10.111.0.53")
        val report = QuarantineReports.build(
            id = "quarantine-1791600000000", targetPackage = "com.traceveil.riskdemo", targetLabel = "Risk Demo",
            startedAtEpochMs = 1_791_600_000_000, startedAtNanos = 1_000_000_000, endedAtNanos = 61_000_000_000,
            plannedDurationMs = 60_000, stopReason = QuarantineStopReason.TIMER, resolverAddresses = setOf("10.111.0.53"),
            observations = recorder.snapshot(),
        )
        val file = QuarantineJsonExport.write(temp.root, report)
        assertEquals("TraceVeil-quarantine-quarantine-1791600000000.json", file.name)
        val decoded = AuditBundles.decodeQuarantine(file.readText())
        assertEquals("traceveil.quarantine.v1", decoded.schema)
        assertEquals(report, decoded.report)
        assertNotNull(decoded.exportedAt)
    }
}
