package com.dpdpxray.app.voice

import com.dpdpxray.core.copilot.CopilotLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MicAvailabilityTest {
    private fun state(
        sdk: Int = 36, declared: Boolean = true, granted: Boolean = true, onDevice: Boolean = true,
        installed: List<String>? = listOf("en-IN", "hi-IN"), lang: CopilotLanguage = CopilotLanguage.EN,
    ) = MicAvailability.evaluate(sdk, declared, granted, onDevice, installed, lang)

    private fun reason(s: MicState) = (s as MicState.Unavailable).reason

    @Test
    fun `ready when everything is on device`() = assertEquals(MicState.Ready, state())

    @Test
    fun `old Android disables the mic with a reason`() = assertTrue(reason(state(sdk = 30)).contains("Android 12"))

    @Test
    fun `missing manifest permission is explained`() = assertTrue(reason(state(declared = false)).contains("not part of this build"))

    @Test
    fun `undecided permission asks rather than disabling`() = assertEquals(MicState.NeedsPermission, state(granted = false))

    @Test
    fun `permission prompt remains available before a language pack is installed`() =
        assertEquals(MicState.NeedsPermission, state(granted = false, installed = emptyList()))

    @Test
    fun `no on-device recognizer disables the mic`() = assertTrue(reason(state(onDevice = false)).contains("no on-device"))

    @Test
    fun `missing Hindi pack disables only Hindi`() {
        assertEquals(MicState.Ready, state(installed = listOf("en-US"), lang = CopilotLanguage.EN))
        assertTrue(reason(state(installed = listOf("en-US"), lang = CopilotLanguage.HI)).contains("Hindi"))
    }

    @Test
    fun `unknown pack list still allows an attempt`() = assertEquals(MicState.Ready, state(installed = null))

    @Test
    fun `every disabled state tells the user the chips still work`() {
        listOf(state(sdk = 30), state(declared = false), state(onDevice = false), state(installed = emptyList())).forEach {
            assertTrue(reason(it).contains("Tap a question"))
        }
    }

    @Test
    fun `recognizer errors map to readable reasons`() {
        assertTrue(MicAvailability.errorReason(13).contains("pack"))  // ERROR_LANGUAGE_UNAVAILABLE
        assertTrue(MicAvailability.errorReason(7).contains("did not catch"))  // ERROR_NO_MATCH
        assertTrue(MicAvailability.errorReason(9).contains("permission"))  // ERROR_INSUFFICIENT_PERMISSIONS
    }
}
