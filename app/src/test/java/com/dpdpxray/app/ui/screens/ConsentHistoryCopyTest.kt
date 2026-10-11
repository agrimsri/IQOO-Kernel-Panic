package com.dpdpxray.app.ui.screens

import com.dpdpxray.app.ui.guide.Bi
import com.dpdpxray.core.model.ConsentHistory
import com.dpdpxray.core.model.HistoryBounds
import com.dpdpxray.core.model.ScoreBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The consent-history question and its answers, in both modes and both languages. */
class ConsentHistoryCopyTest {
    private val c = ConsentHistoryCopy
    private val split = HistoryBounds(35, ScoreBand.AT_RISK, 69, ScoreBand.NEEDS_WORK)
    private val settled = HistoryBounds(65, ScoreBand.NEEDS_WORK, 99, ScoreBand.READY)

    @Test fun `the question names the app, in plain words for everyday mode`() {
        assertEquals(
            "Have you ever agreed to tracking or personalised ads in Block Blast! (for example in a popup or its settings)?",
            c.question("Block Blast!", everyday = false).en,
        )
        assertEquals("Did you ever say yes to tracking or personalised ads in Block Blast!? For example in a popup or in its settings.",
            c.question("Block Blast!", everyday = true).en)
        assertTrue(c.question("Block Blast!", everyday = true).hi.contains("Block Blast!"))
        assertNotEquals(c.question("X", everyday = true).hi, c.question("X", everyday = false).hi)
        assertEquals(listOf("No, never", "Yes, I agreed", "Not sure"), listOf(c.never.en, c.agreed.en, c.notSure.en))
    }

    @Test fun `each answer says what it does to the result`() {
        assertEquals("Score 35 · At risk: its tracker lookups count as made without your consent", c.neverDetail(split).en)
        assertEquals("Score 69 · Needs work: checked as after you agreed", c.agreedDetail(split).en)
        assertEquals("Score 99 · Needs work: checked as after you agreed (never Ready on an answer alone)", c.agreedDetail(settled).en)
        assertEquals("Keeps the range 35–69", c.notSureDetail(split).en)
    }

    @Test fun `a settled range explains itself without asking`() {
        assertEquals("Needs work whichever way you answer: 65 if you never agreed to tracking, 99 if you agreed earlier (never Ready on an answer alone).",
            c.settled(settled).en)
        assertTrue(c.settled(settled).hi.contains("65") && c.settled(settled).hi.contains("99"))
    }

    @Test fun `an answer is always labelled as the person's, and Not sure keeps the range`() {
        val never = c.answered(split.copy(answer = ConsentHistory.DECLARED_NEVER), "Block Blast!")
        assertTrue(never.en, never.en.startsWith("Based on your answer: you never agreed to tracking in Block Blast!."))
        assertTrue(never.en, never.en.contains("If you had agreed earlier: 69."))
        val agreed = c.answered(split.copy(answer = ConsentHistory.DECLARED_AGREED), "Block Blast!")
        assertTrue(agreed.en, agreed.en.startsWith("Checked as after you agreed:"))
        assertTrue(agreed.en, agreed.en.contains("If you never agreed: 35."))
        val unsure = c.notSureLine(split, resetOk = false)
        assertEquals("You're not sure, so the result stays a range: 35–69. Only a fresh start shows the app's first-launch consent.", unsure.en)
        assertTrue(c.notSureLine(split, resetOk = true).en.endsWith("Check again with a reset (your own test app only)."))
    }

    @Test fun `the result headline gives the verdict the range allows`() {
        assertEquals("Needs work whichever way you answer: 65 if you never agreed to tracking, 99 if you agreed earlier.", c.headline(settled).en)
        assertEquals("At best Needs work (35–69): one answer about your consent history decides it, just below.", c.headline(split).en)
    }

    @Test fun `no wording is a verdict on the app and every Hindi line is filled`() {
        val all: List<Bi> = c.all(split, settled)
        val forbidden = listOf("is safe", "compliant", "certified", "malicious", "fraud", "illegal", "inconclusive")
        all.forEach { b ->
            assertTrue(b.en, b.hi.isNotBlank())
            forbidden.forEach { w -> assertFalse("${b.en} says $w", b.en.contains(w, ignoreCase = true)) }
        }
    }
}
