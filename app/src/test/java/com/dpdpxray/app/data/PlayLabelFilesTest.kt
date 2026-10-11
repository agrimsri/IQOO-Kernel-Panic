package com.dpdpxray.app.data

import com.dpdpxray.app.report.AuditJsonExport
import com.dpdpxray.core.datasafety.DataSafetyParser
import com.dpdpxray.core.datasafety.DemoLabels
import com.dpdpxray.core.datasafety.PlayLabelComparator
import com.dpdpxray.core.datasafety.PlayLabelExport
import com.dpdpxray.core.datasafety.PlayLabelRecord
import com.dpdpxray.core.export.AuditBackups
import com.dpdpxray.core.export.AuditBundles
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The Play label a person attaches to a saved check: stored next to it, exported with it, restored with it. Sample data. */
class PlayLabelFilesTest {
    @get:Rule val temp = TemporaryFolder()

    private val auditedAt = 1_791_590_915_883L
    private val sessions = SampleAudit.sessions(auditedAt)
    private val catalog = TrackerCatalog.loadDefault()
    private val result = RulesEngine(catalog).evaluate(sessions)
    private val record = PlayLabelRecord(DemoLabels.LEAKYSHOP, PlayLabelRecord.DEMO, 1_791_600_000_000)

    private fun meta(id: String) = AuditMeta(
        id = id, targetPackage = sessions.first().targetPackage, targetLabel = sessions.first().targetLabel,
        createdAtMs = auditedAt, score = result.score, band = result.band, findingCount = result.findings.size,
        plans = sessions.map { it.plan.label }, timingProfile = "normal",
    )

    @Test
    fun `a label is saved next to the audit and read back`() {
        val dir = temp.newFolder("audit-1")
        assertNull("no label attached reads as none", PlayLabelFiles.load(dir))
        PlayLabelFiles.save(dir, record)
        assertEquals(record, PlayLabelFiles.load(dir))
        assertTrue(PlayLabelFiles.delete(dir))
        assertNull(PlayLabelFiles.load(dir))
    }

    @Test
    fun `an unreadable label file reads as none and never breaks the audit`() {
        val dir = temp.newFolder("audit-2")
        File(dir, PlayLabelFiles.FILE).writeText("{not json")
        assertNull(PlayLabelFiles.load(dir))
    }

    @Test
    fun `the audit export carries the attached label, and nothing when none is attached`() {
        val dir = temp.newFolder("audit-3")
        val without = AuditBundle(meta("audit-3"), sessions, result, dir)
        assertFalse(AuditBundles.encode(AuditJsonExport.build(without, "1.3.0")).contains("\"playLabel\""))

        val with = without.copy(playLabel = record)
        val labelOnly = AuditBundles.decode(AuditBundles.encode(AuditJsonExport.build(with, "1.3.0"))).playLabel!!
        assertEquals(record.text, labelOnly.text)
        assertEquals(PlayLabelRecord.DEMO, labelOnly.source)
        assertNull("the plain export carries the label; the comparison is added by the Report screen", labelOnly.check)

        val check = PlayLabelComparator(catalog).compare(DataSafetyParser.parse(record.text), sessions)
        val full = AuditBundles.decode(AuditBundles.encode(AuditJsonExport.build(with, "1.3.0", playLabel = PlayLabelExport.of(record, check)))).playLabel!!
        assertEquals(check.points, full.check!!.points)
        assertEquals(result, AuditBundles.decode(AuditBundles.encode(AuditJsonExport.build(with, "1.3.0"))).result)
    }

    @Test
    fun `a backup keeps the label so a restore can put it back`() {
        val dir = temp.newFolder("audit-4")
        val saved = AuditBundle(meta("audit-4"), sessions, result, dir, playLabel = record)
        val text = AuditBackups.encode(listOf(AuditJsonExport.build(saved, "1.3.0", exportedAt = "2026-10-11T09:00:00Z")), "2026-10-11T09:00:00Z", "1.3.0")
        val back = AuditBackups.plan(AuditBackups.read(text), existingIds = emptySet()).toRestore.single()
        assertEquals(record, CheckBackups.labelOf(back))
        assertNull(CheckBackups.labelOf(AuditJsonExport.build(saved.copy(playLabel = null), "1.3.0")))
    }
}
