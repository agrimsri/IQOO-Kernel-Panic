package com.dpdpxray.app.voice

import com.dpdpxray.core.copilot.CopilotLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Any [SpeechOutput] provider (offline today, others later) drives the same UI state through [applyTo]. */
class SpeechOutputTest {
    private class Fake(var problem: String? = null) : SpeechOutput {
        val spoken = mutableListOf<Pair<String, CopilotLanguage>>()
        var stopped = 0
        var released = 0
        override val online = false
        override fun problemFor(language: CopilotLanguage) = problem
        override suspend fun speak(text: String, language: CopilotLanguage) { spoken += text to language }
        override fun stop() { stopped++ }
        override fun release() { released++ }
    }

    @Test
    fun `offline provider is not marked online`() {
        assertFalse(Fake().online)
    }

    @Test
    fun `speaking sets speaking and keeps the note`() {
        val s = VoiceOutputState.Speaking.applyTo(VoiceUiState(voiceNote = "old"))
        assertTrue(s.speaking)
        assertEquals("old", s.voiceNote)
    }

    @Test
    fun `idle clears speaking but keeps the note`() {
        val s = VoiceOutputState.Idle.applyTo(VoiceUiState(speaking = true, voiceNote = "n"))
        assertFalse(s.speaking)
        assertEquals("n", s.voiceNote)
    }

    @Test
    fun `note stops speaking and shows the reason`() {
        val s = VoiceOutputState.Note("No offline Hindi voice").applyTo(VoiceUiState(speaking = true))
        assertFalse(s.speaking)
        assertEquals("No offline Hindi voice", s.voiceNote)
    }

    @Test
    fun `cloud voice is off by default`() {
        assertFalse(VoiceUiState().cloudVoice)
        assertNull(VoiceUiState().voiceNote)
        assertFalse(VoiceUiState().speaking)
    }
}
