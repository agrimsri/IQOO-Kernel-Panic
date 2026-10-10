package com.dpdpxray.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reply extraction against the structures LiteRT-LM 0.17.1 really returns: `Message.contents` holds the visible
 * `Content.Text` parts and `Message.channels` maps a channel name (Gemma 4 declares "thought") to its text.
 */
class ReplyExtractionTest {
    private fun extract(text: String, channels: Map<String, String> = emptyMap()) = ReplyExtraction.extract(listOf(text), channels)

    @Test
    fun `visible reply is the answer and thought channel text is counted but not shown`() {
        val o = extract("The answer.", mapOf("thought" to "secret plan"))
        assertEquals(LlmOutcome.Text("The answer.", LlmOutcome.FROM_CONTENTS, hiddenThinkingChars = 11), o)
        assertFalse(o.describe().contains("secret"))
    }

    @Test
    fun `reply with only thinking text is Empty, never an answer`() {
        val o = extract("", mapOf("thought" to "I think the answer is 42"))
        assertEquals(LlmOutcome.Empty(setOf("thought"), thinkingChars = 24), o)
        assertNull(o.textOrNull)
        assertFalse(o.describe().contains("42"))
        assertTrue(o.describe().contains("only thinking text"))
    }

    @Test
    fun `inline thought markup left in the visible text is removed`() {
        assertEquals(
            LlmOutcome.Text("Final answer", LlmOutcome.FROM_CONTENTS, hiddenThinkingChars = 10),
            extract("<|channel>thought\nplan steps<channel|>Final answer"),
        )
    }

    @Test
    fun `an empty thought block is harmless`() {
        assertEquals(LlmOutcome.Text("OK"), extract("<|channel>thought\n<channel|>OK"))
    }

    @Test
    fun `thinking that never closed (output budget ran out) is Empty`() {
        val o = extract("<|channel>thought\nstill thinking when tokens ran out")
        assertTrue(o is LlmOutcome.Empty)
        o as LlmOutcome.Empty
        assertEquals(setOf("thought"), o.channels)
        assertEquals("still thinking when tokens ran out".length, o.thinkingChars)
    }

    @Test
    fun `close marker without an open marker means the text before it was thinking`() {
        assertEquals(
            LlmOutcome.Text("Answer", LlmOutcome.FROM_CONTENTS, hiddenThinkingChars = "reasoning here".length),
            extract("reasoning here<channel|>Answer"),
        )
    }

    @Test
    fun `an unnamed inline block is treated as thinking, never shown`() {
        val o = extract("<|channel>I will reason without naming the channel<channel|>OK")
        assertEquals("OK", o.textOrNull)
        assertTrue((o as LlmOutcome.Text).hiddenThinkingChars > 0)
    }

    @Test
    fun `answer channel is used only when the visible reply is empty, and the source is recorded`() {
        val o = extract(" ", mapOf("thought" to "x", "final" to " Use clear consent. "))
        assertEquals(LlmOutcome.Text("Use clear consent.", "channel:final", hiddenThinkingChars = 1), o)
        assertTrue(o.describe().contains("from channel:final"))
    }

    @Test
    fun `unknown side channels are never presented as the answer`() {
        assertEquals(LlmOutcome.Empty(setOf("commentary")), extract("", mapOf("commentary" to "tool chatter")))
    }

    @Test
    fun `parts are joined before markup is split`() {
        assertEquals(
            LlmOutcome.Text("Hello", LlmOutcome.FROM_CONTENTS, hiddenThinkingChars = 2),
            ReplyExtraction.extract(listOf("<|channel>thou", "ght\nab<chan", "nel|>Hel", "lo"), emptyMap()),
        )
    }

    @Test
    fun `streamed chunks are accumulated per channel`() {
        val acc = ReplyAccumulator()
        acc.add(listOf("Hel"), mapOf("thought" to "a"))
        acc.add(listOf("lo"), mapOf("thought" to "b"))
        acc.add(emptyList(), emptyMap())
        assertEquals(LlmOutcome.Text("Hello", LlmOutcome.FROM_CONTENTS, hiddenThinkingChars = 2), acc.outcome())
    }

    @Test
    fun `streamed thinking-only reply stays Empty`() {
        val acc = ReplyAccumulator()
        acc.add(emptyList(), mapOf("thought" to "pondering"))
        assertEquals(LlmOutcome.Empty(setOf("thought"), thinkingChars = 9), acc.outcome())
    }

    // LiteRT-LM 0.17.1 classes are Java 21 bytecode (class file 65) and the JVM tests run on JDK 17, so a real Message
    // cannot be loaded here. These mirror Message.toOutcome(): text parts = Content.Text items, channels = Message.channels.

    @Test
    fun `mapped Message with text parts and a thought channel`() {
        val textParts = listOf("Hi ", "there")
        val channels = mapOf("thought" to "zz")
        assertEquals(LlmOutcome.Text("Hi there", LlmOutcome.FROM_CONTENTS, hiddenThinkingChars = 2), LlmOutcome.fromReply(textParts, channels))
    }

    @Test
    fun `mapped Message with no text parts and only a thought channel is Empty`() {
        assertEquals(LlmOutcome.Empty(setOf("thought"), thinkingChars = 6), LlmOutcome.fromReply(emptyList(), mapOf("thought" to "hidden")))
    }
}
