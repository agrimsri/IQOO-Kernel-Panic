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

    @Test fun `a vosk result with confident words is a detection`() {
        val json = """{"result":[{"conf":0.98,"end":1.2,"start":0.9,"word":"hey"},{"conf":0.91,"end":1.5,"start":1.2,"word":"trace"},{"conf":0.88,"end":1.9,"start":1.5,"word":"veil"}],"text":"hey trace veil"}"""
        val heard = WakePhrase.detect(json)
        assertTrue(heard.accepted)
        assertEquals(0.88, heard.confidence!!, 1e-9)
    }

    @Test fun `a low-confidence word inside the phrase is not a detection`() {
        val json = """{"result":[{"conf":0.95,"word":"hey"},{"conf":0.40,"word":"trace"},{"conf":0.90,"word":"veil"}],"text":"hey trace veil"}"""
        val heard = WakePhrase.detect(json)
        assertFalse(heard.accepted)
        assertEquals(WakeHeard.Reason.LOW_CONFIDENCE, heard.reason)
    }

    @Test fun `low confidence outside the phrase does not matter`() {
        val json = """{"result":[{"conf":0.20,"word":"[unk]"},{"conf":0.95,"word":"hey"},{"conf":0.90,"word":"trace"},{"conf":0.90,"word":"veil"}],"text":"[unk] hey trace veil"}"""
        assertTrue(WakePhrase.detect(json).accepted)
    }

    @Test fun `text without word timings is accepted on the phrase alone`() {
        val heard = WakePhrase.detect("""{"text":"hey trace veil"}""")
        assertTrue(heard.accepted)
        assertNull(heard.confidence)
    }

    @Test fun `empty, unknown-only, partial and malformed results are never detections`() {
        listOf(
            """{"text":""}""",
            """{"text":"[unk]"}""",
            """{"partial":"hey trace veil"}""",
            "not json",
            "",
            """{"text":"hey"}""",
        ).forEach { assertFalse("should reject $it", WakePhrase.detect(it).accepted) }
    }
}
