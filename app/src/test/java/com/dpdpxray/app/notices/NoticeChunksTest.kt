package com.dpdpxray.app.notices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Long licence texts are shown in chunks so the screen stays smooth; chunking must never change the text. */
class NoticeChunksTest {
    @Test
    fun `chunks rejoin to exactly the original text`() {
        val text = (1..500).joinToString("\n") { if (it % 7 == 0) "" else "line $it" } + "\n"
        val chunks = NoticeChunks.split(text)
        assertTrue(chunks.size > 1)
        assertEquals(text, chunks.joinToString("\n"))
    }

    @Test
    fun `Windows line endings are shown as plain line breaks`() {
        assertEquals(listOf("a\nb\n\nc"), NoticeChunks.split("a\r\nb\r\n\r\nc"))
    }

    @Test
    fun `a chunk prefers to end at a blank line and never grows past the hard limit`() {
        val dense = (1..1000).joinToString("\n") { "x$it" } // no blank lines at all
        NoticeChunks.split(dense).forEach { assertTrue(it.lines().size <= NoticeChunks.HARD_LINES) }
        val paragraphs = (1..300).joinToString("\n") { if (it % 50 == 0) "" else "p$it" }
        val first = NoticeChunks.split(paragraphs).first()
        assertTrue(first, first.lines().size >= NoticeChunks.SOFT_LINES)
        assertTrue(first, first.lines().last().isEmpty() || first.lines().size == NoticeChunks.HARD_LINES)
    }

    @Test
    fun `empty text gives one empty chunk`() {
        assertEquals(listOf(""), NoticeChunks.split(""))
    }

    @Test
    fun `the largest bundled notice splits losslessly`() {
        val f = listOf("src/main/assets", "app/src/main/assets").map(::File).first { it.isDirectory }
            .resolve("licenses/google/litertlm-android-0.17.1-THIRD_PARTY_NOTICE.txt")
        val text = f.readText().replace("\r\n", "\n")
        val chunks = NoticeChunks.split(text)
        assertEquals(text, chunks.joinToString("\n"))
        assertTrue(chunks.size > 100)
    }
}
