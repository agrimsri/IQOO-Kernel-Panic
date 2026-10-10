package com.dpdpxray.app.voice.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeSessionPolicyTest {
    private val healthy = BatteryReading(percent = 80, charging = false, systemLow = false)

    @Test fun `a session is capped at fifteen minutes`() {
        assertEquals(15 * 60_000L, WakeSessionPolicy.MAX_SESSION_MS)
        assertNull(WakeSessionPolicy.stopReason(startedAtMs = 1_000, nowMs = 1_000 + 15 * 60_000L - 1, battery = healthy))
        assertEquals(WakeStopReason.TIMEOUT, WakeSessionPolicy.stopReason(1_000, 1_000 + 15 * 60_000L, healthy))
    }

    @Test fun `remaining time counts down and never goes negative`() {
        assertEquals(15 * 60_000L, WakeSessionPolicy.remainingMs(5_000, 5_000))
        assertEquals(60_000L, WakeSessionPolicy.remainingMs(0, 14 * 60_000L))
        assertEquals(0L, WakeSessionPolicy.remainingMs(0, 20 * 60_000L))
    }

    @Test fun `a clock that goes backwards does not extend or end the session`() {
        assertNull(WakeSessionPolicy.stopReason(startedAtMs = 10_000, nowMs = 5_000, battery = healthy))
        assertEquals(15 * 60_000L, WakeSessionPolicy.remainingMs(10_000, 5_000))
    }

    @Test fun `low battery stops listening unless the phone is charging`() {
        assertEquals(WakeStopReason.LOW_BATTERY, WakeSessionPolicy.stopReason(0, 1, BatteryReading(15, charging = false, systemLow = false)))
        assertNull(WakeSessionPolicy.stopReason(0, 1, BatteryReading(16, charging = false, systemLow = false)))
        assertNull(WakeSessionPolicy.stopReason(0, 1, BatteryReading(5, charging = true, systemLow = false)))
    }

    @Test fun `the system low-battery signal stops listening even without a percentage`() {
        assertEquals(WakeStopReason.LOW_BATTERY, WakeSessionPolicy.stopReason(0, 1, BatteryReading(null, charging = false, systemLow = true)))
        assertNull(WakeSessionPolicy.stopReason(0, 1, BatteryReading(null, charging = true, systemLow = true)))
    }

    @Test fun `an unknown battery level is not treated as low`() {
        assertNull(WakeSessionPolicy.stopReason(0, 1, BatteryReading(null, charging = false, systemLow = false)))
    }

    @Test fun `low battery also refuses a new start`() {
        assertEquals(WakeStopReason.LOW_BATTERY, WakeSessionPolicy.startProblem(BatteryReading(10, charging = false, systemLow = false)))
        assertNull(WakeSessionPolicy.startProblem(healthy))
    }
}
