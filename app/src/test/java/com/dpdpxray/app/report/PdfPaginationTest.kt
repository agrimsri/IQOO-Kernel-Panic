package com.dpdpxray.app.report

import com.dpdpxray.app.report.PdfPagination.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPaginationTest {
    private val top = 40f
    private val bottom = 802f

    private fun body(lines: Int, lineHeight: Float = 12f) =
        Block(height = lines * lineHeight, before = 2f, keepWithNext = false, lineBottoms = (1..lines).map { it * lineHeight })

    private fun heading(h: Float = 16f) = Block(height = h, before = 12f, keepWithNext = true)

    @Test
    fun `a heading at the page bottom moves to the next page with its body`() {
        // Fill the page so ~30 px remain: room for the heading alone but not its first two body lines.
        val filler = body(60)                       // 720 px
        val blocks = listOf(filler, heading(), body(5))
        val slices = PdfPagination.plan(blocks, top, bottom)
        val headingSlice = slices.single { it.index == 1 }
        val bodySlice = slices.first { it.index == 2 }
        assertEquals(2, headingSlice.page)
        assertEquals("body starts on the heading's page", headingSlice.page, bodySlice.page)
    }

    @Test
    fun `a heading that fits with the first lines of a long body keeps them on its page`() {
        // The 480 px body does not fit below the heading; it flows across the break instead of jumping whole.
        val blocks = listOf(body(30), heading(), body(40))
        val slices = PdfPagination.plan(blocks, top, bottom)
        assertEquals(1, slices.single { it.index == 1 }.page)
        val parts = slices.filter { it.index == 2 }
        assertEquals(listOf(1, 2), parts.map { it.page })
        assertTrue("at least two lines stay with the heading", parts.first().to >= 24f)
    }

    @Test
    fun `an ordinary block that does not fit still moves to the next page whole`() {
        val slices = PdfPagination.plan(listOf(body(55), body(10)), top, bottom)
        assertEquals(listOf(2), slices.filter { it.index == 1 }.map { it.page })
    }

    @Test
    fun `heading, metadata and body are kept together as a chain`() {
        val meta = Block(height = 11f, before = 2f, keepWithNext = true)
        val blocks = listOf(body(58), heading(), meta, body(6))  // y = 741: heading + meta fit, their first body lines do not
        val slices = PdfPagination.plan(blocks, top, bottom)
        val pages = slices.filter { it.index >= 1 }.map { it.page }.distinct()
        assertEquals(listOf(2), pages)
    }

    @Test
    fun `content is never dropped or duplicated`() {
        val blocks = (0 until 40).map { if (it % 4 == 0) heading() else body(1 + it % 7) }
        val slices = PdfPagination.plan(blocks, top, bottom)
        for ((i, b) in blocks.withIndex()) {
            val mine = slices.filter { it.index == i }
            assertEquals("block $i fully drawn", b.height, mine.sumOf { (it.to - it.from).toDouble() }.toFloat(), 0.01f)
        }
        assertTrue(slices.all { it.y >= top && it.y + (it.to - it.from) <= bottom + 0.01f })
        assertEquals("slices keep document order", slices.map { it.index }.sorted(), slices.map { it.index })
    }

    @Test
    fun `a block taller than a page is split at line boundaries`() {
        val tall = body(100)                          // 1200 px > 762 px page body
        val slices = PdfPagination.plan(listOf(body(10), tall), top, bottom)
        val parts = slices.filter { it.index == 1 }
        assertTrue(parts.size >= 2)
        assertTrue(parts.all { it.from % 12f == 0f && it.to % 12f == 0f })
        assertEquals(1200f, parts.last().to, 0.01f)
        assertEquals(parts.map { it.page }, parts.map { it.page }.sorted())
    }

    @Test
    fun `a heading already at the top of a page never forces an empty page`() {
        val huge = heading(h = 20f)
        val slices = PdfPagination.plan(listOf(huge, body(100)), top, bottom)
        assertEquals(1, slices.first().page)
        assertFalse(slices.any { it.page > 3 })
    }

    @Test
    fun `markdown headings, labels, metadata and table headers keep with the next line`() {
        val md = """
            ## Findings

            ### C1 · Trackers contacted before consent
            *High · observed · confidence 100% · Plan A · Accept*

            Plain explanation.

            **Evidence**
            - one

            AndroidManifest.xml:
            | t (s) | Host |
            |---|---|
            | 0.4 | a.example |
            | 0.6 | b.example |
        """.trimIndent()
        val lines = PdfExporter.classify(md)
        fun keep(text: String) = lines.first { it.text.contains(text) }.keepWithNext
        assertTrue(keep("Findings"))
        assertTrue(keep("C1 · Trackers"))
        assertTrue(keep("High · observed"))
        assertFalse(keep("Plain explanation"))
        assertTrue(keep("Evidence"))
        assertFalse(keep("• one"))
        assertTrue(keep("AndroidManifest.xml:"))
        assertTrue("table header row", keep("t (s)"))
        assertFalse(keep("a.example"))
        assertEquals("text is unchanged by classification", "Plain explanation.", lines.first { it.text.contains("Plain") }.text)
    }
}
