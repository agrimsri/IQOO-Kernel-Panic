package com.dpdpxray.app.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LlmOutcomeTest {
    @Test
    fun `text parts are joined and trimmed`() {
        assertEquals(LlmOutcome.Text("Hello world"), LlmOutcome.fromReply(listOf(" Hello", " world "), emptySet()))
    }

    @Test
    fun `blank reply is Empty and reports channel names, not their content`() {
        val o = LlmOutcome.fromReply(listOf("  "), setOf("thought"))
        assertEquals(LlmOutcome.Empty(setOf("thought")), o)
        assertNull(o.textOrNull)
    }

    @Test
    fun `failure keeps class and a bounded message`() {
        val o = LlmOutcome.failed(IllegalStateException("x".repeat(1000)))
        o as LlmOutcome.Failed
        assertEquals("java.lang.IllegalStateException", o.errorClass)
        assertTrue(o.message.length <= LlmOutcome.MAX_MESSAGE)
    }

    @Test
    fun `cancellation is rethrown, not converted to a failure`() = runBlocking {
        try {
            llmCatching { throw CancellationException("left the screen") }
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun `other exceptions become Failed`() = runBlocking {
        val o = llmCatching { throw RuntimeException("native error") }
        assertTrue(o is LlmOutcome.Failed)
    }

    @Test
    fun `describe never includes prompt text`() {
        assertEquals("Failed: java.lang.RuntimeException: boom", LlmOutcome.Failed("java.lang.RuntimeException", "boom").describe())
        assertEquals("Empty reply (channels: thought)", LlmOutcome.Empty(setOf("thought")).describe())
    }

    @Test
    fun `describe reports hidden thinking, channel source and skipped calls by count and name only`() {
        assertEquals("Text (2 chars); thinking text hidden (40 chars)", LlmOutcome.Text("OK", hiddenThinkingChars = 40).describe())
        assertEquals("Text (2 chars, from channel:final)", LlmOutcome.Text("OK", "channel:final").describe())
        assertEquals(
            "Empty reply (channels: thought); only thinking text (12 chars), not used as an answer",
            LlmOutcome.Empty(setOf("thought"), 12).describe(),
        )
        assertEquals("Not sent: text-only model", LlmOutcome.Skipped("text-only model").describe())
        assertNull(LlmOutcome.Skipped("x").textOrNull)
    }
}
