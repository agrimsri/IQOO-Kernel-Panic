package com.dpdpxray.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerCheckTest {
    private val ok = LlmOutcome.Text("OK.")
    private val empty = LlmOutcome.Empty(emptySet())

    private fun run(vararg outcomes: Pair<String, LlmOutcome>, images: Boolean = true): List<Pair<AnswerCheckCase, LlmOutcome>> {
        val byId = outcomes.toMap()
        return AnswerCheck.cases(images).map { it to (byId[it.id] ?: ok) }
    }

    @Test
    fun `app defaults never use temperature 0 and always switch thinking off`() {
        val app = AskOptions()
        assertEquals(ThinkingChoice.OFF, app.thinking)
        assertTrue((app.sampler.temperature ?: 1.0) > 0.0)
        assertTrue((AskOptions.EXPLANATION.sampler.temperature ?: 1.0) > 0.0)
        assertEquals(ThinkingChoice.OFF, AskOptions.screenReview(true).thinking)
        assertTrue(AskOptions.screenReview(true).constrained)
        assertEquals(0.0, AskOptions.LEGACY.sampler.temperature!!, 0.0)
        assertEquals(ThinkingChoice.ENGINE_DEFAULT, AskOptions.LEGACY.thinking)
    }

    @Test
    fun `the check covers the new settings, each old setting on its own, thinking, streaming and constrained JSON`() {
        val ids = AnswerCheck.cases(canReadImages = false).map { it.id }
        assertEquals(ids.first(), "app")
        assertTrue(ids.containsAll(listOf("app", "model_sampler", "no_system", "streaming", "json", "thinking_on", "legacy_thinking", "legacy_sampler", "legacy")))
        assertEquals(ids.size, ids.toSet().size)
        // Old settings run last, so a native error they cause cannot spoil the rows for the new settings.
        assertEquals("legacy", ids.last())
    }

    @Test
    fun `every check is bounded so a broken setting cannot run for minutes`() {
        AnswerCheck.cases(canReadImages = true).forEach { assertTrue(it.id, (it.options.maxOutputTokens ?: Int.MAX_VALUE) <= 512) }
    }

    @Test
    fun `the image check exists only for a model that reads images`() {
        assertFalse(AnswerCheck.cases(canReadImages = false).any { it.withTestImage })
        assertTrue(AnswerCheck.cases(canReadImages = true).any { it.withTestImage })
    }

    @Test
    fun `an answer counts only when it says what was asked`() {
        val app = AnswerCheck.cases(false).first { it.id == "app" }
        assertTrue(AnswerCheck.isExpected(app, LlmOutcome.Text("OK.")))
        assertFalse(AnswerCheck.isExpected(app, LlmOutcome.Text("I cannot look at that")))
        assertFalse(AnswerCheck.isExpected(app, empty))
        val json = AnswerCheck.cases(false).first { it.id == "json" }
        assertTrue(AnswerCheck.isExpected(json, LlmOutcome.Text("{\"ok\": true}")))
    }

    @Test
    fun `preview shows the start of a test answer, never thinking or errors`() {
        assertEquals("OK.", AnswerCheck.preview(ok))
        assertEquals("", AnswerCheck.preview(LlmOutcome.Empty(setOf("thought"), 40)))
        assertEquals(60, AnswerCheck.preview(LlmOutcome.Text("x".repeat(200))).length)
        assertEquals("first line", AnswerCheck.preview(LlmOutcome.Text("first line\nsecond")))
    }

    @Test
    fun `fixed on this phone when new settings answer and old ones do not`() {
        val c = AnswerCheck.conclude(run("legacy" to empty, "legacy_sampler" to empty))
        assertTrue(c.toString(), c.first().startsWith("Fixed on this phone"))
        assertTrue(c.toString(), c.any { it.contains("Temperature 0 is the breaking setting") })
    }

    @Test
    fun `unset thinking is named when only it breaks answers`() {
        val c = AnswerCheck.conclude(run("legacy" to empty, "legacy_thinking" to LlmOutcome.Empty(setOf("thought"), 300)))
        assertTrue(c.toString(), c.any { it.contains("Leaving thinking unset is the breaking setting") })
    }

    @Test
    fun `still failing is said plainly`() {
        val c = AnswerCheck.conclude(run("app" to LlmOutcome.Failed("X", "boom")))
        assertTrue(c.toString(), c.first().startsWith("Still failing"))
    }

    @Test
    fun `not reproduced when old settings also answer`() {
        val c = AnswerCheck.conclude(run())
        assertTrue(c.toString(), c.first().contains("does not reproduce"))
    }

    @Test
    fun `thinking-only reply under thinking on is reported`() {
        val c = AnswerCheck.conclude(run("thinking_on" to LlmOutcome.Empty(setOf("thought"), 900)))
        assertTrue(c.toString(), c.any { it.contains("only thinking text") })
    }

    @Test
    fun `constrained JSON and image failures are reported`() {
        val c = AnswerCheck.conclude(run("json" to LlmOutcome.Failed("X", "no grammar"), "image" to empty))
        assertTrue(c.toString(), c.any { it.contains("Constrained JSON") })
        assertTrue(c.toString(), c.any { it.contains("image test") })
    }

    @Test
    fun `model sampler suggestion when only it works`() {
        val c = AnswerCheck.conclude(run("app" to empty, "no_system" to empty, "streaming" to empty, "json" to empty))
        assertTrue(c.toString(), c.any { it.contains("MODEL_DEFAULT") })
    }
}
