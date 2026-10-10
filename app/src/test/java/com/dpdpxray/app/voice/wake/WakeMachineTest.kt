package com.dpdpxray.app.voice.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeMachineTest {
    private val healthy = BatteryReading(80, charging = false, systemLow = false)
    private fun next(s: WakeState, e: WakeEvent) = WakeMachine.next(s, e)

    @Test fun `starts off and only an explicit arm starts the engine`() {
        assertEquals(WakeState.Off, WakeState.initial)
        assertEquals(WakeState.Starting, next(WakeState.Off, WakeEvent.Arm))
        assertEquals(WakeState.Off, next(WakeState.Off, WakeEvent.EngineReady(10)))
        assertEquals(WakeState.Off, next(WakeState.Off, WakeEvent.Heard(10)))
        assertEquals(WakeState.Off, next(WakeState.Off, WakeEvent.Tick(10, healthy)))
    }

    @Test fun `a stopped session can be armed again`() {
        assertEquals(WakeState.Starting, next(WakeState.Stopped(WakeStopReason.TIMEOUT), WakeEvent.Arm))
    }

    @Test fun `arming twice does not start a second engine`() {
        val listening = WakeState.Listening(startedAtMs = 5)
        assertSame(listening, next(listening, WakeEvent.Arm))
        assertEquals(WakeState.Starting, next(WakeState.Starting, WakeEvent.Arm))
    }

    @Test fun `engine ready starts the fifteen-minute clock`() {
        assertEquals(WakeState.Listening(1_000), next(WakeState.Starting, WakeEvent.EngineReady(1_000)))
    }

    @Test fun `an engine failure stops with its reason`() {
        assertEquals(WakeState.Stopped(WakeStopReason.ENGINE_ERROR, "no mic"), next(WakeState.Starting, WakeEvent.EngineFailed("no mic")))
        assertEquals(WakeState.Stopped(WakeStopReason.ENGINE_ERROR, "x"), next(WakeState.Listening(0), WakeEvent.EngineFailed("x")))
    }

    @Test fun `hearing the phrase pauses for a reply without resetting the session clock`() {
        val responding = next(WakeState.Listening(1_000), WakeEvent.Heard(60_000))
        assertEquals(WakeState.Responding(startedAtMs = 1_000, resumeAtMs = 60_000 + WakeMachine.REPLY_PAUSE_MS), responding)
        // A second detection during the reply is ignored.
        assertSame(responding, next(responding, WakeEvent.Heard(61_000)))
        // Before the pause ends it keeps responding; afterwards it listens again with the ORIGINAL start time.
        assertSame(responding, next(responding, WakeEvent.Tick(60_000 + WakeMachine.REPLY_PAUSE_MS - 1, healthy)))
        assertEquals(WakeState.Listening(1_000), next(responding, WakeEvent.Tick(60_000 + WakeMachine.REPLY_PAUSE_MS, healthy)))
    }

    @Test fun `the time limit and low battery stop listening and responding`() {
        val limit = WakeSessionPolicy.MAX_SESSION_MS
        assertEquals(WakeState.Stopped(WakeStopReason.TIMEOUT), next(WakeState.Listening(0), WakeEvent.Tick(limit, healthy)))
        assertEquals(WakeState.Stopped(WakeStopReason.TIMEOUT), next(WakeState.Responding(0, limit + 5_000), WakeEvent.Tick(limit, healthy)))
        val low = BatteryReading(10, charging = false, systemLow = false)
        assertEquals(WakeState.Stopped(WakeStopReason.LOW_BATTERY), next(WakeState.Listening(0), WakeEvent.Tick(1, low)))
        assertEquals(WakeState.Stopped(WakeStopReason.LOW_BATTERY), next(WakeState.Starting, WakeEvent.Tick(1, low)))
    }

    @Test fun `explicit stops win from every active state and keep the first reason`() {
        listOf(WakeState.Starting, WakeState.Listening(0), WakeState.Responding(0, 10)).forEach {
            assertEquals(WakeState.Stopped(WakeStopReason.COPILOT_MIC), next(it, WakeEvent.Stop(WakeStopReason.COPILOT_MIC)))
        }
        val stopped = WakeState.Stopped(WakeStopReason.TIMEOUT)
        assertSame(stopped, next(stopped, WakeEvent.Stop(WakeStopReason.USER)))
        assertEquals(WakeState.Off, next(WakeState.Off, WakeEvent.Stop(WakeStopReason.USER)))
    }

    @Test fun `a late engine-ready after a stop does not resume listening`() {
        val stopped = WakeState.Stopped(WakeStopReason.USER)
        assertSame(stopped, next(stopped, WakeEvent.EngineReady(10)))
    }

    @Test fun `only listening feeds audio to the recogniser`() {
        assertTrue(WakeState.Listening(0).processesAudio)
        assertFalse(WakeState.Responding(0, 1).processesAudio)
        assertFalse(WakeState.Starting.processesAudio)
        assertTrue(WakeState.Responding(0, 1).active)
        assertTrue(WakeState.Starting.active)
        assertFalse(WakeState.Off.active)
        assertFalse(WakeState.Stopped(WakeStopReason.USER).active)
    }
}
