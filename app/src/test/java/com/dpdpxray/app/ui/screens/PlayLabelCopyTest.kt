package com.dpdpxray.app.ui.screens

import com.dpdpxray.app.data.SampleAudit
import com.dpdpxray.core.copilot.VerdictWords
import com.dpdpxray.core.datasafety.DataSafetyParser
import com.dpdpxray.core.datasafety.DemoLabels
import com.dpdpxray.core.datasafety.PlayLabelComparator
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wording of the "Play Data safety label vs what we saw" card. Sample data, not device runs. */
class PlayLabelCopyTest {
    private val sessions = SampleAudit.sessions(1_791_590_915_883L)
    private val check = PlayLabelComparator(TrackerCatalog.loadDefault()).compare(DataSafetyParser.parse(DemoLabels.LEAKYSHOP), sessions)

    @Test
    fun `card and button wording`() {
        assertEquals("Play Data safety label vs what we saw", PlayLabelCopy.TITLE)
        assertEquals("Paste label text", PlayLabelCopy.PASTE)
        assertEquals("Use demo label (LeakyShop)", PlayLabelCopy.DEMO)
    }

    @Test
    fun `the demo label is offered only for LeakyShop and the sample audit`() {
        assertTrue(PlayLabelCopy.demoOffered("com.leakyshop.demo", sample = false))
        assertTrue(PlayLabelCopy.demoOffered("com.leakyshop.demo.fixed", sample = false))
        assertTrue(PlayLabelCopy.demoOffered("com.example.any", sample = true))
        assertFalse(PlayLabelCopy.demoOffered("com.instagram.android", sample = false))
    }

    @Test
    fun `everyday mode gets one calm line, and nothing without a label`() {
        assertNull(PlayLabelCopy.everydayLine(null))
        val line = PlayLabelCopy.everydayLine(check)!!
        assertTrue(line, line.startsWith("Compared with this app's Play Data safety label:"))
        assertTrue(line, line.contains("points to review"))
    }

    @Test
    fun `no verdict words in any card text`() {
        val texts = listOf(PlayLabelCopy.TITLE, PlayLabelCopy.CAVEAT, PlayLabelCopy.EMPTY_HINT, PlayLabelCopy.PASTE_HINT, PlayLabelCopy.everydayLine(check)!!) +
            check.points.flatMap { listOfNotNull(it.text, it.caveat) } + check.notCompared
        val banned = Regex("\\b(false|lying|lie|violat\\w*|illegal\\w*|breach\\w*)\\b", RegexOption.IGNORE_CASE)
        texts.forEach { t ->
            assertNull(t, VerdictWords.find(t))
            assertNull(t, banned.find(t)?.value)
        }
    }
}
