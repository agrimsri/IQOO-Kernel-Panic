package com.dpdpxray.app.voice

import com.dpdpxray.core.copilot.CopilotLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalVoicePickerTest {
    private fun v(name: String, lang: String, net: Boolean = false, missing: Boolean = false, q: Int = 300) = VoiceInfo(name, lang, net, missing, q)

    @Test
    fun `picks the best installed offline voice for the language`() {
        val voices = listOf(v("hi-net", "hi-IN", net = true, q = 500), v("hi-low", "hi-IN", q = 200), v("hi-high", "hi-IN", q = 400), v("en", "en-IN"))
        assertEquals("hi-high", LocalVoicePicker.pick(voices, CopilotLanguage.HI)?.name)
    }

    @Test
    fun `never picks a network or not-installed voice`() {
        assertNull(LocalVoicePicker.pick(listOf(v("a", "hi-IN", net = true), v("b", "hi-IN", missing = true)), CopilotLanguage.HI))
    }

    @Test
    fun `prefers the Indian English variant`() {
        assertEquals("in", LocalVoicePicker.pick(listOf(v("us", "en-US", q = 400), v("in", "en-IN", q = 300)), CopilotLanguage.EN)?.name)
    }

    @Test
    fun `falls back to any English region`() {
        assertEquals("us", LocalVoicePicker.pick(listOf(v("us", "en-US")), CopilotLanguage.EN)?.name)
    }
}
