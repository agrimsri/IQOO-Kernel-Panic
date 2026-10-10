package com.dpdpxray.app.voice.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhraseTest {
    @Test fun `grammar restricts the recogniser to the wake phrase and unknown speech`() {
        assertEquals("""["hey trace veil","hey traceveil","[unk]"]""", WakePhrase.grammarJson())
    }

    @Test fun `normalisation lowercases and drops punctuation and hyphens`() {
        assertEquals("hey trace veil", WakePhrase.normalise("  Hey,   Trace-Veil! "))
        assertEquals("hey traceveil", WakePhrase.normalise("HEY TRACEVEIL."))
    }

    @Test fun `unknown speech breaks a phrase instead of disappearing`() {
        assertEquals("hey _ trace veil", WakePhrase.normalise("hey [unk] trace veil"))
        assertFalse(WakePhrase.matches("hey [unk] trace veil"))
    }

    @Test fun `accepts both spellings of the wake phrase`() {
        assertTrue(WakePhrase.matches("hey trace veil"))
        assertTrue(WakePhrase.matches("Hey TraceVeil"))
        assertTrue(WakePhrase.matches("hey trace-veil"))
        assertTrue(WakePhrase.matches("[unk] hey trace veil"))
        assertTrue(WakePhrase.matches("hey trace veil [unk]"))
    }

    @Test fun `rejects fragments and look-alike phrases`() {
        listOf("", "hey", "[unk]", "trace veil", "hey trace", "they trace veil", "hey veil trace", "hey [unk]", "hey hey").forEach {
            assertFalse("should reject '$it'", WakePhrase.matches(it))
        }
    }

    @Test fun `strict is stricter than normal and is the matcher's default`() {
        assertTrue(WakeSensitivity.STRICT.minWordConfidence > WakeSensitivity.NORMAL.minWordConfidence)
        assertTrue("normal is no looser than the old 0.70 floor", WakeSensitivity.NORMAL.minWordConfidence >= 0.70)
        assertEquals(WakeSensitivity.NORMAL, WakeSensitivity.fromName("NORMAL"))
        assertNull(WakeSensitivity.fromName("LOOSE"))
        assertEquals(WakeHeard.Reason.SPEECH_BEFORE, WakePhrase.detect("""{"result":[{"conf":0.99,"word":"[unk]"},{"conf":0.99,"word":"hey"},{"conf":0.99,"word":"trace"},{"conf":0.99,"word":"veil"}],"text":"[unk] hey trace veil"}""").reason)
    }

    // --- sample final results from Vosk's grammar recogniser with setWords(true) -----------------------------------

    private fun word(w: String, conf: Double, start: Double? = null, end: Double? = null) =
        """{"conf":$conf""" + (start?.let { ""","start":$it""" } ?: "") + (end?.let { ""","end":$it""" } ?: "") + ""","word":"$w"}"""

    private fun result(vararg words: String, text: String) = """{"result":[${words.joinToString(",")}],"text":"$text"}"""

    private val clean = result(
        word("hey", 1.0, 0.78, 1.11), word("trace", 1.0, 1.11, 1.44), word("veil", 0.97, 1.44, 1.92),
        text = "hey trace veil",
    )

    @Test fun `a clear phrase on its own is accepted in both modes`() {
        WakeSensitivity.entries.forEach { mode ->
            val heard = WakePhrase.detect(clean, mode)
            assertTrue(mode.name, heard.accepted)
            assertEquals(WakeHeard.Reason.MATCH, heard.reason)
            assertEquals(0.97, heard.confidence!!, 1e-9)
        }
    }

    @Test fun `the user may keep talking after the phrase`() {
        val json = result(word("hey", 1.0, 0.5, 0.8), word("trace", 0.99, 0.8, 1.1), word("veil", 0.98, 1.1, 1.5), word("[unk]", 0.6, 1.6, 2.4), text = "hey trace veil [unk]")
        assertTrue(WakePhrase.detect(json, WakeSensitivity.STRICT).accepted)
    }

    @Test fun `a borderline confidence passes normal but not strict`() {
        val json = result(word("hey", 0.95, 0.5, 0.8), word("trace", 0.82, 0.8, 1.1), word("veil", 0.93, 1.1, 1.5), text = "hey trace veil")
        assertTrue(WakePhrase.detect(json, WakeSensitivity.NORMAL).accepted)
        val strict = WakePhrase.detect(json, WakeSensitivity.STRICT)
        assertFalse(strict.accepted)
        assertEquals(WakeHeard.Reason.LOW_CONFIDENCE, strict.reason)
        assertEquals(0.82, strict.confidence!!, 1e-9)
    }

    @Test fun `a low-confidence word inside the phrase is rejected in both modes`() {
        val json = """{"result":[{"conf":0.95,"word":"hey"},{"conf":0.40,"word":"trace"},{"conf":0.90,"word":"veil"}],"text":"hey trace veil"}"""
        WakeSensitivity.entries.forEach { mode ->
            val heard = WakePhrase.detect(json, mode)
            assertFalse(mode.name, heard.accepted)
            assertEquals(WakeHeard.Reason.LOW_CONFIDENCE, heard.reason)
        }
    }

    @Test fun `background chatter mapped onto the phrase mid-sentence is rejected by strict`() {
        val json = result(
            word("[unk]", 0.7, 0.1, 0.9), word("hey", 0.96, 0.9, 1.1), word("trace", 0.95, 1.1, 1.4), word("veil", 0.94, 1.4, 1.8),
            text = "[unk] hey trace veil",
        )
        assertTrue("normal keeps the old behaviour", WakePhrase.detect(json, WakeSensitivity.NORMAL).accepted)
        val strict = WakePhrase.detect(json, WakeSensitivity.STRICT)
        assertFalse(strict.accepted)
        assertEquals(WakeHeard.Reason.SPEECH_BEFORE, strict.reason)
    }

    @Test fun `a phrase squeezed into a blip or stretched over seconds is rejected by strict`() {
        val blip = result(word("hey", 0.99, 1.00, 1.05), word("trace", 0.99, 1.05, 1.12), word("veil", 0.99, 1.12, 1.20), text = "hey trace veil")
        val stretched = result(word("hey", 0.99, 0.2, 0.5), word("trace", 0.99, 2.4, 2.8), word("veil", 0.99, 2.8, 3.2), text = "hey trace veil")
        listOf(blip, stretched).forEach {
            val heard = WakePhrase.detect(it, WakeSensitivity.STRICT)
            assertFalse(heard.accepted)
            assertEquals(WakeHeard.Reason.BAD_TIMING, heard.reason)
            assertTrue(WakePhrase.detect(it, WakeSensitivity.NORMAL).accepted)
        }
    }

    @Test fun `strict needs per-word scores, normal accepts the text alone`() {
        val textOnly = """{"text":"hey trace veil"}"""
        assertTrue(WakePhrase.detect(textOnly, WakeSensitivity.NORMAL).accepted)
        assertNull(WakePhrase.detect(textOnly, WakeSensitivity.NORMAL).confidence)
        val strict = WakePhrase.detect(textOnly, WakeSensitivity.STRICT)
        assertFalse(strict.accepted)
        assertEquals(WakeHeard.Reason.NO_WORD_SCORES, strict.reason)
    }

    @Test fun `missing word timings do not count against the phrase`() {
        val json = result(word("hey", 0.99), word("trace", 0.98), word("veil", 0.97), text = "hey trace veil")
        assertTrue(WakePhrase.detect(json, WakeSensitivity.STRICT).accepted)
    }

    @Test fun `empty, unknown-only, partial and malformed results are never detections`() {
        listOf(
            """{"text":""}""",
            """{"text":"[unk]"}""",
            """{"partial":"hey trace veil"}""",
            "not json",
            "",
            """{"text":"hey"}""",
        ).forEach { json ->
            WakeSensitivity.entries.forEach { mode -> assertFalse("should reject $json in $mode", WakePhrase.detect(json, mode).accepted) }
        }
        assertEquals(WakeHeard.Reason.UNREADABLE, WakePhrase.detect("not json").reason)
        assertEquals(WakeHeard.Reason.NO_MATCH, WakePhrase.detect("""{"text":"[unk]"}""").reason)
    }
}
