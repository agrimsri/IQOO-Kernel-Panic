package com.dpdpxray.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which voice speaks: the online Sarvam voice only with an explicit flag AND a key; otherwise the phone's offline voice. */
class SpeechOutputChoiceTest {
    private fun choose(env: String?) = SpeechOutputChoice.choose(ApiEnv.parse(env))

    @Test
    fun `no key means the offline voice even when the flag is on`() {
        assertEquals(SpeechOutputChoice.OFFLINE, choose("USE_API=true"))
        assertEquals(SpeechOutputChoice.OFFLINE, choose("USE_API=true\nSARVAM_API_KEY="))
        assertEquals(SpeechOutputChoice.OFFLINE, choose("USE_API=true\nSARVAM_API_KEY=   "))
    }

    @Test
    fun `a key without the flag also stays offline`() = assertEquals(SpeechOutputChoice.OFFLINE, choose("SARVAM_API_KEY=abc123"))

    @Test
    fun `no file at all is offline`() = assertEquals(SpeechOutputChoice.OFFLINE, choose(null))

    @Test
    fun `an explicitly false flag stays offline`() = assertEquals(SpeechOutputChoice.OFFLINE, choose("USE_API=false\nSARVAM_API_KEY=abc123"))

    @Test
    fun `flag and key together choose the online voice`() = assertEquals(SpeechOutputChoice.ONLINE, choose("USE_API=true\nSARVAM_API_KEY=abc123"))

    @Test
    fun `the flag is not case sensitive, so True works as written in the shared api env`() {
        assertEquals(SpeechOutputChoice.ONLINE, choose("USE_API=True\nSARVAM_API_KEY=abc123"))
        assertEquals(SpeechOutputChoice.ONLINE, choose("USE_API=TRUE\nSARVAM_API_KEY=abc123"))
        assertEquals(SpeechOutputChoice.ONLINE, choose("export USE_API=\"True\"\nexport SARVAM_API_KEY='abc123'"))
    }

    @Test
    fun `anything other than true is not true`() {
        listOf("yes", "1", "on", "truee", "t", "").forEach {
            assertEquals(it, SpeechOutputChoice.OFFLINE, choose("USE_API=$it\nSARVAM_API_KEY=abc123"))
        }
    }

    @Test
    fun `the choice and the config never expose the key`() {
        val c = ApiEnv.parse("USE_API=true\nSARVAM_API_KEY=super-secret-value")
        assertFalse(c.toString().contains("super-secret-value"))
        assertFalse(SpeechOutputChoice.choose(c).toString().contains("super-secret-value"))
        assertTrue(c.useApi)
    }
}
