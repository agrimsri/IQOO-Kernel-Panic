package com.dpdpxray.app.voice

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechErrorsTest {
    private val allCodes = listOf(
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS, SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT, SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS,
    )

    @Test
    fun `code 11 is the server-disconnected error`() = assertEquals(11, SpeechRecognizer.ERROR_SERVER_DISCONNECTED)

    @Test
    fun `every known recognizer error has its own wording, never the generic code fallback`() {
        val texts = allCodes.map { SpeechErrors.reason(it) }
        texts.forEachIndexed { i, t -> assertFalse("code ${allCodes[i]}: $t", t.contains("(code")) }
    }

    @Test
    fun `only codes that mean the same to the user share wording`() {
        fun same(vararg codes: Int) = codes.map { SpeechErrors.reason(it) }.toSet().size == 1
        assertTrue(same(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertTrue(same(SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER))
        // two groups share, so 15 codes give 12 distinct messages: nothing else is collapsed
        assertEquals(12, allCodes.map { SpeechErrors.reason(it) }.toSet().size)
    }

    @Test
    fun `an unknown code still says something honest`() {
        assertTrue(SpeechErrors.reason(99).contains("code 99"))
        assertTrue(SpeechErrors.reason(99).contains("Tap a question"))
    }

    @Test
    fun `a disconnected speech service is explained and tells the user to try again`() {
        val t = SpeechErrors.reason(SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
        assertTrue(t, t.contains("disconnected"))
        assertTrue(t, t.contains("try again", ignoreCase = true))
    }

    @Test
    fun `only a disconnected service is retried, and only once`() {
        assertTrue(SpeechErrors.shouldRetry(SpeechRecognizer.ERROR_SERVER_DISCONNECTED, attempt = 0))
        assertFalse(SpeechErrors.shouldRetry(SpeechRecognizer.ERROR_SERVER_DISCONNECTED, attempt = 1))
        assertFalse(SpeechErrors.shouldRetry(SpeechRecognizer.ERROR_SERVER_DISCONNECTED, attempt = 2))
        allCodes.filter { it != SpeechRecognizer.ERROR_SERVER_DISCONNECTED }.forEach {
            assertFalse("code $it must not be retried", SpeechErrors.shouldRetry(it, attempt = 0))
        }
    }

    @Test
    fun `no wording suggests audio goes to a server`() {
        val banned = Regex("uploaded|sent to|cloud (service|server) (is|was)", RegexOption.IGNORE_CASE)
        allCodes.forEach { code -> assertFalse("code $code", banned.containsMatchIn(SpeechErrors.reason(code))) }
    }

    @Test
    fun `the old entry point keeps working and agrees`() {
        allCodes.forEach { assertEquals(SpeechErrors.reason(it), MicAvailability.errorReason(it)) }
    }
}
