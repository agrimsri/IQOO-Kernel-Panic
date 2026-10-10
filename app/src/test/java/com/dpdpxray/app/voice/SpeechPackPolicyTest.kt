package com.dpdpxray.app.voice

import com.dpdpxray.core.copilot.CopilotLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechPackPolicyTest {
    @Test fun `uses installed English pack instead of requesting a missing region`() {
        val choice = SpeechPackPolicy.choose(CopilotLanguage.EN,
            SpeechPacks(installed = listOf("en-US")), "en-IN")
        assertEquals("en-US", choice.tag)
        assertEquals(PackStatus.INSTALLED, choice.status)
    }

    @Test fun `device region wins when both recognition packs are installed`() {
        val choice = SpeechPackPolicy.choose(CopilotLanguage.EN,
            SpeechPacks(installed = listOf("en-IN", "en-GB")), "en-GB")
        assertEquals("en-GB", choice.tag)
    }

    @Test fun `downloadable is not confused with installed or online recognition`() {
        val choice = SpeechPackPolicy.choose(CopilotLanguage.HI,
            SpeechPacks(installed = listOf("en-US"), supported = listOf("hi-IN")), "en-US")
        assertEquals("hi-IN", choice.tag)
        assertEquals(PackStatus.DOWNLOADABLE, choice.status)
    }

    @Test fun `scheduled download remains unavailable until installed`() {
        val choice = SpeechPackPolicy.choose(CopilotLanguage.EN,
            SpeechPacks(pending = listOf("en-US")), "en-US")
        assertEquals(PackStatus.PENDING, choice.status)
    }

    @Test fun `missing Hindi never selects an English pack`() {
        val choice = SpeechPackPolicy.choose(CopilotLanguage.HI,
            SpeechPacks(installed = listOf("en-US")), "en-US")
        assertEquals(PackStatus.UNSUPPORTED, choice.status)
        assertEquals("hi-IN", choice.tag)
    }

    @Test fun `unreported support allows an explicit local attempt using device region`() {
        val choice = SpeechPackPolicy.choose(CopilotLanguage.EN, null, "en-GB")
        assertEquals("en-GB", choice.tag)
        assertEquals(PackStatus.UNKNOWN, choice.status)
    }
}
