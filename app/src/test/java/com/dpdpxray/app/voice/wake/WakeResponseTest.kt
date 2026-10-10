package com.dpdpxray.app.voice.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Arming rules and stop explanations. */
class WakeResponseTest {
    private val ok = BatteryReading(70, charging = false, systemLow = false)

    @Test fun `arming needs the model, the microphone, a visible notification and battery`() {
        assertNull(WakeArming.problem(modelReady = true, micGranted = true, notificationsAllowed = true, battery = ok, copilotOpen = false))
        assertEquals(WakeStopReason.MODEL_MISSING, WakeArming.problem(false, true, true, ok, false))
        assertEquals(WakeStopReason.PERMISSION_MISSING, WakeArming.problem(true, false, true, ok, false))
        assertEquals(WakeStopReason.NOTIFICATIONS_OFF, WakeArming.problem(true, true, false, ok, false))
        assertEquals(WakeStopReason.LOW_BATTERY, WakeArming.problem(true, true, true, BatteryReading(5, false, false), false))
        assertEquals(WakeStopReason.COPILOT_MIC, WakeArming.problem(true, true, true, ok, copilotOpen = true))
    }

    @Test fun `every stop reason has a plain explanation`() {
        WakeStopReason.entries.forEach { assertTrue(it.name, WakeStopReason.explain(it).isNotBlank()) }
        assertTrue(WakeStopReason.explain(WakeStopReason.TIMEOUT, WakeDuration.MIN_15).contains("15 minutes"))
    }
}
