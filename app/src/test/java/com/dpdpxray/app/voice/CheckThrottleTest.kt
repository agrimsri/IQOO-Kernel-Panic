package com.dpdpxray.app.voice

import com.dpdpxray.core.copilot.CopilotLanguage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckThrottleTest {
    @Test
    fun `the first check always runs`() = assertTrue(CheckThrottle(2_000).allow(0, CopilotLanguage.EN))

    @Test
    fun `an identical check straight after is skipped`() {
        val t = CheckThrottle(2_000)
        assertTrue(t.allow(1_000, CopilotLanguage.EN))
        assertFalse(t.allow(1_500, CopilotLanguage.EN))
        assertFalse(t.allow(2_999, CopilotLanguage.EN))
    }

    @Test
    fun `it runs again once the gap has passed`() {
        val t = CheckThrottle(2_000)
        t.allow(1_000, CopilotLanguage.EN)
        assertTrue(t.allow(3_000, CopilotLanguage.EN))
    }

    @Test
    fun `a different language is never skipped`() {
        val t = CheckThrottle(2_000)
        t.allow(1_000, CopilotLanguage.EN)
        assertTrue(t.allow(1_100, CopilotLanguage.HI))
    }

    @Test
    fun `an explicit Check again is never skipped`() {
        val t = CheckThrottle(2_000)
        t.allow(1_000, CopilotLanguage.EN)
        assertTrue(t.allow(1_100, CopilotLanguage.EN, force = true))
    }

    @Test
    fun `a skipped check does not push the window forward`() {
        val t = CheckThrottle(2_000)
        t.allow(1_000, CopilotLanguage.EN)
        t.allow(2_900, CopilotLanguage.EN) // skipped
        assertTrue(t.allow(3_000, CopilotLanguage.EN))
    }
}
