package com.dpdpxray.core.datasafety

import com.dpdpxray.core.copilot.VerdictWords
import com.dpdpxray.core.export.AuditBundles
import com.dpdpxray.core.export.BundleApp
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayLabelReportTest {
    private val catalog = TrackerCatalog.loadDefault()
    private val record = PlayLabelRecord(DemoLabels.LEAKYSHOP, PlayLabelRecord.DEMO, 1_791_590_400_000)
    private val check = PlayLabelComparator(catalog).compare(DataSafetyParser.parse(record.text), DemoSessions.leaky)

    @Test
    fun `markdown section shows the label, numbered points, caveats and sources`() {
        val md = PlayLabelReport.markdown(record, check)
        assertTrue(md.startsWith("## ${PlayLabelReport.TITLE}\n"))
        assertTrue(md, md.contains("Separate from the score"))
        assertTrue(md, md.contains("demo label written by the TraceVeil team"))
        assertTrue(md, md.contains("- Sharing: No data shared with third parties"))
        assertTrue(md, md.contains("- Collection: App activity"))
        assertTrue(md, md.contains("**Points to review (4)**"))
        assertTrue(md, md.contains("1. Label does not list Device or other IDs"))
        assertTrue(md, md.contains("4. Label says no data shared with third parties"))
        assertTrue(md, md.contains("service provider"))
        assertTrue(md, md.contains("https://support.google.com/analytics/answer/11582702"))
        assertTrue(md, md.contains("CleverTap: ${SdkExpectations.UNVERIFIED}"))
        assertTrue(md, md.contains("https://support.google.com/googleplay/android-developer/answer/10787469"))
        assertNull(VerdictWords.find(md))
    }

    @Test
    fun `the section goes before Method and limitations, or at the end`() {
        val report = "# Report\n\n## Findings\n\nx\n\n## Method and limitations\n- y\n"
        val placed = PlayLabelReport.insertInto(report, "## Section\n\nz\n")
        assertTrue(placed, placed.indexOf("## Section") in placed.indexOf("## Findings")..placed.indexOf("## Method and limitations"))
        assertTrue(PlayLabelReport.insertInto("# Short\n", "## Section\n").endsWith("## Section\n"))
    }

    @Test
    fun `plain text for the command line`() {
        val text = PlayLabelReport.text(check, "label.txt")
        assertTrue(text, text.startsWith("${PlayLabelReport.TITLE} (label.txt)\n"))
        assertTrue(text, text.contains("Points to review (4):"))
        assertTrue(text, text.contains("Separate from the score and the policy result"))
        val unreadable = PlayLabelReport.text(PlayLabelComparator(catalog).compare(DataSafetyParser.parse("hello"), DemoSessions.leaky), "x.txt")
        assertTrue(unreadable, unreadable.contains(PlayLabelComparator.UNREADABLE))
    }

    @Test
    fun `html section for the offline report escapes text and links sources only as plain anchors`() {
        val evil = record.copy(text = "No data shared with third parties\n<script>alert(1)</script>\nThis app may collect these data types\nApp activity")
        val evilCheck = PlayLabelComparator(catalog).compare(evil.parsed, DemoSessions.leaky)
        val html = PlayLabelReport.html(evil, evilCheck)
        assertTrue(html, html.startsWith("<section id=\"play-label\""))
        assertFalse(html, html.contains("<script"))
        assertTrue(html, html.contains("Points to review (4)"))
        assertTrue(html, html.contains("&quot;collects and shares"))
        val stripped = html.replace(Regex("<a\\s[^>]*>.*?</a>", RegexOption.DOT_MATCHES_ALL), "")
        assertFalse(stripped.contains("https://"))
        assertTrue(html.contains("<a href=\"https://support.google.com/analytics/answer/11582702\" target=\"_blank\" rel=\"noopener noreferrer\">"))

        val page = "<html><body><main><p>x</p><footer class=\"foot\"><p>d</p></footer></main></body></html>"
        val placed = PlayLabelReport.insertHtml(page, html)
        assertTrue(placed.indexOf("<section id=\"play-label\"") < placed.indexOf("<footer class=\"foot\">"))
        assertEquals(page, PlayLabelReport.insertHtml(page, ""))
    }

    @Test
    fun `label lines say what the text states and nothing more`() {
        assertEquals(
            listOf("Sharing: not in the pasted text", "Collection: Location and Personal info (and 5 others not named)", "Security: Data isn't encrypted; Data can't be deleted"),
            PlayLabelReport.labelLines(DataSafetyParser.parse(LabelFixtures.SUMMARY_TRUNCATED.replace("This app may share these data types with third parties\nLocation, App activity and 2 others\n", ""))),
        )
        assertEquals(listOf("Sharing: No data shared with third parties", "Collection: No data collected", "Security: Data is encrypted in transit"),
            PlayLabelReport.labelLines(DataSafetyParser.parse(LabelFixtures.NOTHING)))
    }

    @Test
    fun `audit bundle carries the label as an optional part`() {
        val sessions = DemoSessions.leaky
        val result = RulesEngine(catalog).evaluate(sessions)
        fun bundle(label: PlayLabelExport?) = AuditBundles.audit(
            app = BundleApp("com.leakyshop.demo", "LeakyShop"), meta = JsonObject(emptyMap()), sessions = sessions, result = result,
            networkReadings = null, toolVersion = "test", exportedAt = "2026-10-11T00:00:00Z", playLabel = label,
        )
        val without = AuditBundles.encode(bundle(null))
        assertFalse(without.contains("\"playLabel\""))
        val with = AuditBundles.encode(bundle(PlayLabelExport.of(record, check)))
        val back = AuditBundles.decode(with).playLabel
        assertNotNull(back)
        assertEquals(PlayLabelRecord.DEMO, back!!.source)
        assertEquals(record.text, back.text)
        assertEquals(4, back.check!!.toReview.size)
        assertTrue(back.label.noDataShared)
        // A label stored without its comparison (backups) still reads, and the score in the bundle is untouched.
        val labelOnly = AuditBundles.decode(AuditBundles.encode(bundle(PlayLabelExport.of(record, null)))).playLabel!!
        assertNull(labelOnly.check)
        assertEquals(21, AuditBundles.decode(with).result.score)
    }
}
