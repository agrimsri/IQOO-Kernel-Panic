package com.dpdpxray.app.report

import com.dpdpxray.app.audit.AuditTimingPolicy
import com.dpdpxray.app.data.SampleAudit
import com.dpdpxray.core.dpdp.CitationService
import com.dpdpxray.core.dpdp.DpdpCorpus
import com.dpdpxray.core.fixes.FixCatalog
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.report.HtmlReport
import com.dpdpxray.core.report.MarkdownReport
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HtmlExportTest {
    private val catalog = TrackerCatalog.loadDefault()
    private val citations = CitationService(DpdpCorpus.loadDefault())
    private val fixCatalog = FixCatalog.loadDefault()

    /** 10 Oct 2026, so the written sample files are reproducible. */
    private val auditedAt = 1_791_590_915_883L

    /** Report text as it appears in the HTML source. */
    private fun escaped(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    private fun html(sessions: List<AuditSession>, timing: String?, sample: Boolean): String {
        val result = RulesEngine(catalog).evaluate(sessions)
        return HtmlExporter.build(result, sessions, timing, sample, fixCatalog.fixesFor(result, catalog), citations, catalog)
    }

    @Test
    fun `sample HTML is labelled as a sample before the score and has a card per finding`() {
        val sessions = SampleAudit.sessions(auditedAt)
        val result = RulesEngine(catalog).evaluate(sessions)
        val out = html(sessions, null, sample = true)
        val hero = out.indexOf("class=\"hero\"")
        assertTrue(out.indexOf(HtmlReport.SAMPLE_BANNER) in 1 until hero)
        assertTrue(out.indexOf("Sample audit generated in-app; run a live audit for real evidence.") in 1 until hero)
        assertTrue(out.indexOf(escaped(EvidenceReport.LOCAL_SCOPE)) in 1 until hero)
        result.findings.forEach { assertTrue(out.contains("id=\"finding-${it.checkId.code}\"")) }
        assertFalse("samples carry no timing provenance", out.contains(AuditTimingPolicy.coverageHint(null)))
    }

    @Test
    fun `live HTML opens with the same coverage statements as the Markdown and PDF export`() {
        val incomplete = SampleAudit.sessions(auditedAt).map { s ->
            if (s.plan.name == "REJECT") s.copy(consentEvents = s.consentEvents.filterNot { it.kind == ConsentKind.REJECT }, notes = emptyList())
            else s.copy(notes = emptyList())
        }
        val out = html(incomplete, AuditTimingPolicy.NORMAL, sample = false)
        val markdown = EvidenceReport.annotate(
            MarkdownReport.build(RulesEngine(catalog).evaluate(incomplete), incomplete, emptyList(), citations), incomplete, AuditTimingPolicy.NORMAL,
        )
        val notes = EvidenceReport.coverageNotes(incomplete, AuditTimingPolicy.NORMAL, sample = false)
        assertTrue("a missing refusal and the timing profile are reported", notes.size >= 2)
        val hero = out.indexOf("class=\"hero\"")
        for (note in notes) {
            assertTrue(markdown.contains(note))
            assertTrue("'$note' precedes the score", out.indexOf(escaped(note)) in 1 until hero)
        }
        assertFalse(out.contains(HtmlReport.SAMPLE_BANNER))
        assertTrue(out.contains("Evidence confidence:"))
    }

    @Test
    fun `writes the built-in sample audits as HTML for the laptop`() {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").exists() } ?: return
        val outDir = File(root, "build").apply { mkdirs() }
        for ((variant, name) in listOf(SampleAudit.Variant.BASELINE to "sample-report.html", SampleAudit.Variant.ENDPOINTS to "sample-report-alias.html")) {
            val file = HtmlExporter.write(html(SampleAudit.sessions(auditedAt, variant), null, sample = true), File(outDir, name))
            assertTrue(file.length() in 1 until HtmlReport.SIZE_BUDGET_BYTES.toLong())
        }
    }
}
