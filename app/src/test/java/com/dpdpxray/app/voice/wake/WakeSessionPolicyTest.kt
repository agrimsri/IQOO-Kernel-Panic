package com.dpdpxray.app.voice.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeSessionPolicyTest {
    private val healthy = BatteryReading(percent = 80, charging = false, systemLow = false)
    private val minutes15 = WakeDuration.MIN_15.limitMs
    private val hour = WakeDuration.HOUR_1.limitMs

    @Test fun `the three session lengths are fifteen minutes, one hour and until stopped`() {
        assertEquals(15 * 60_000L, WakeDuration.MIN_15.limitMs)
        assertEquals(60 * 60_000L, WakeDuration.HOUR_1.limitMs)
        assertNull(WakeDuration.UNTIL_OFF.limitMs)
        assertEquals(listOf("15 min", "1 hour", "Until I turn it off"), WakeDuration.entries.map { it.label })
    }

    @Test fun `without a saved choice it listens until stopped on the charger and for an hour on battery`() {
        assertEquals(WakeDuration.UNTIL_OFF, WakeDuration.default(charging = true))
        assertEquals(WakeDuration.HOUR_1, WakeDuration.default(charging = false))
        assertEquals(WakeDuration.MIN_15, WakeDuration.effective(saved = WakeDuration.MIN_15, charging = true))
        assertEquals(WakeDuration.UNTIL_OFF, WakeDuration.effective(saved = null, charging = true))
        assertEquals(WakeDuration.HOUR_1, WakeDuration.fromName("bogus") ?: WakeDuration.default(false))
        assertEquals(WakeDuration.UNTIL_OFF, WakeDuration.fromName("UNTIL_OFF"))
    }

    @Test fun `stored choices start empty with strict sensitivity`() {
        assertNull(WakePrefs().duration)
        assertEquals(WakeSensitivity.STRICT, WakePrefs().sensitivity)
    }

    @Test fun `a fifteen-minute session stops at fifteen minutes`() {
        assertNull(WakeSessionPolicy.stopReason(startedAtMs = 1_000, nowMs = 1_000 + 15 * 60_000L - 1, battery = healthy, limitMs = minutes15))
        assertEquals(WakeStopReason.TIMEOUT, WakeSessionPolicy.stopReason(1_000, 1_000 + 15 * 60_000L, healthy, minutes15))
    }

    @Test fun `a one-hour session runs past fifteen minutes and stops at one hour`() {
        assertNull(WakeSessionPolicy.stopReason(0, 16 * 60_000L, healthy, hour))
        assertNull(WakeSessionPolicy.stopReason(0, 60 * 60_000L - 1, healthy, hour))
        assertEquals(WakeStopReason.TIMEOUT, WakeSessionPolicy.stopReason(0, 60 * 60_000L, healthy, hour))
    }

    @Test fun `until-off never times out but still stops on low battery`() {
        assertNull(WakeSessionPolicy.stopReason(0, 72 * 60 * 60_000L, healthy, limitMs = null))
        assertNull(WakeSessionPolicy.remainingMs(0, 5_000, limitMs = null))
        assertEquals(WakeStopReason.LOW_BATTERY, WakeSessionPolicy.stopReason(0, 1, BatteryReading(12, charging = false, systemLow = false), null))
    }

    @Test fun `remaining time counts down and never goes negative`() {
        assertEquals(15 * 60_000L, WakeSessionPolicy.remainingMs(5_000, 5_000, minutes15))
        assertEquals(60_000L, WakeSessionPolicy.remainingMs(0, 14 * 60_000L, minutes15))
        assertEquals(0L, WakeSessionPolicy.remainingMs(0, 20 * 60_000L, minutes15))
    }

    @Test fun `a clock that goes backwards does not extend or end the session`() {
        assertNull(WakeSessionPolicy.stopReason(startedAtMs = 10_000, nowMs = 5_000, battery = healthy, limitMs = minutes15))
        assertEquals(15 * 60_000L, WakeSessionPolicy.remainingMs(10_000, 5_000, minutes15))
    }

    @Test fun `low battery stops listening unless the phone is charging`() {
        assertEquals(WakeStopReason.LOW_BATTERY, WakeSessionPolicy.stopReason(0, 1, BatteryReading(15, charging = false, systemLow = false), hour))
        assertNull(WakeSessionPolicy.stopReason(0, 1, BatteryReading(16, charging = false, systemLow = false), hour))
        assertNull(WakeSessionPolicy.stopReason(0, 1, BatteryReading(5, charging = true, systemLow = false), hour))
    }

    @Test fun `low battery wins over the time limit`() {
        assertEquals(WakeStopReason.LOW_BATTERY, WakeSessionPolicy.stopReason(0, 2 * 60 * 60_000L, BatteryReading(5, charging = false, systemLow = false), hour))
    }

    @Test fun `the system low-battery signal stops listening even without a percentage`() {
        assertEquals(WakeStopReason.LOW_BATTERY, WakeSessionPolicy.stopReason(0, 1, BatteryReading(null, charging = false, systemLow = true), null))
        assertNull(WakeSessionPolicy.stopReason(0, 1, BatteryReading(null, charging = true, systemLow = true), null))
    }

    @Test fun `an unknown battery level is not treated as low`() {
        assertNull(WakeSessionPolicy.stopReason(0, 1, BatteryReading(null, charging = false, systemLow = false), null))
    }

    @Test fun `low battery also refuses a new start`() {
        assertEquals(WakeStopReason.LOW_BATTERY, WakeSessionPolicy.startProblem(BatteryReading(10, charging = false, systemLow = false)))
        assertNull(WakeSessionPolicy.startProblem(healthy))
    }

    @Test fun `the timeout explanation names the chosen length`() {
        assertTrue(WakeStopReason.explain(WakeStopReason.TIMEOUT, WakeDuration.MIN_15).contains("15 minutes"))
        assertTrue(WakeStopReason.explain(WakeStopReason.TIMEOUT, WakeDuration.HOUR_1).contains("1 hour"))
        assertTrue(WakeStopReason.explain(WakeStopReason.TIMEOUT).contains("time you chose"))
    }
}
