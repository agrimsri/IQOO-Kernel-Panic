package com.dpdpxray.app.report

import com.dpdpxray.app.audit.AuditTimingPolicy
import com.dpdpxray.app.data.AuditBundle
import com.dpdpxray.app.data.AuditMeta
import com.dpdpxray.core.dpdp.CitationService
import com.dpdpxray.core.dpdp.DpdpCorpus
import com.dpdpxray.core.export.AuditBundles
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.report.MarkdownReport
import com.dpdpxray.core.rules.QuietBackgroundAppReplay
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A background watch in the JSON export and the report's coverage section. Test data, not a device run. */
class WatchExportTest {
    @get:Rule val temp = TemporaryFolder()

    private val engine = RulesEngine(TrackerCatalog.loadDefault())

    private fun bundle(s: AuditSession, timing: String): AuditBundle {
        val r = engine.evaluate(listOf(s))
        val meta = AuditMeta("audit-1", s.targetPackage, s.targetLabel, 1, r.score, r.band, r.findings.size, listOf(s.plan.label), timingProfile = timing)
        return AuditBundle(meta, listOf(s), r, temp.root)
    }

    @Test fun `a watch exports as traceveil audit v1 with its length on the session and no new top-level part`() {
        val watch = bundle(QuietBackgroundAppReplay.backgroundWatch(), AuditTimingPolicy.BACKGROUND_WATCH)
        val text = AuditBundles.encode(AuditJsonExport.build(watch, "1.1.0", exportedAt = "2026-10-11T08:00:00Z"))
        val decoded = AuditBundles.decode(text)
        assertEquals(watch.sessions, decoded.sessions)
        assertEquals(600_000L, decoded.sessions.single().watch?.plannedMs)
        assertEquals(AuditTimingPolicy.BACKGROUND_WATCH, AuditBundles.metaOf(decoded).timingProfile)
        val plain = bundle(QuietBackgroundAppReplay.quickCheck(), AuditTimingPolicy.NORMAL)
        val plainText = AuditBundles.encode(AuditJsonExport.build(plain, "1.1.0", exportedAt = "2026-10-11T08:00:00Z"))
        assertEquals(Json.parseToJsonElement(plainText).jsonObject.keys, Json.parseToJsonElement(text).jsonObject.keys)
        assertFalse("other checks export no watch", plainText.contains("\"watch\""))
    }

    @Test fun `the report's coverage says it was a watch, never a background step after an observation window`() {
        val sessions = listOf(QuietBackgroundAppReplay.backgroundWatch())
        val notes = EvidenceReport.coverageNotes(sessions, AuditTimingPolicy.BACKGROUND_WATCH, sample = false)
        assertTrue(notes.toString(), AuditTimingPolicy.coverageHint(AuditTimingPolicy.BACKGROUND_WATCH) in notes)
        assertFalse(notes.toString(), notes.any { it.startsWith("Background check: after the observation window") })
        val md = EvidenceReport.annotate(
            MarkdownReport.build(engine.evaluate(sessions), sessions, emptyList(), CitationService(DpdpCorpus.loadDefault())),
            sessions, AuditTimingPolicy.BACKGROUND_WATCH,
        )
        assertTrue(md, md.startsWith("## Audit coverage"))
        assertTrue(md, md.contains("What was checked: what RollCall sends while it runs in the background, watched for 10 minutes; its consent screen was not reached."))
    }
}
