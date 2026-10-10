package com.dpdpxray.app.voice.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeMachineTest {
    private val healthy = BatteryReading(80, charging = false, systemLow = false)
    private val hour = WakeDuration.HOUR_1.limitMs
    private fun next(s: WakeState, e: WakeEvent) = WakeMachine.next(s, e)
    private fun tick(now: Long, limit: Long? = hour, battery: BatteryReading = healthy) = WakeEvent.Tick(now, battery, limit)

    @Test fun `starts off and only an explicit arm starts the engine`() {
        assertEquals(WakeState.Off, WakeState.initial)
        assertEquals(WakeState.Starting, next(WakeState.Off, WakeEvent.Arm))
        assertEquals(WakeState.Off, next(WakeState.Off, WakeEvent.EngineReady(10)))
        assertEquals(WakeState.Off, next(WakeState.Off, WakeEvent.Heard(10)))
        assertEquals(WakeState.Off, next(WakeState.Off, tick(10)))
        assertEquals(WakeState.Off, next(WakeState.Off, WakeEvent.Holds(setOf(WakeHold.COPILOT), 10)))
    }

    @Test fun `a stopped session can be armed again`() {
        assertEquals(WakeState.Starting, next(WakeState.Stopped(WakeStopReason.TIMEOUT), WakeEvent.Arm))
    }

    @Test fun `arming twice does not start a second engine`() {
        val listening = WakeState.Listening(startedAtMs = 5)
        assertSame(listening, next(listening, WakeEvent.Arm))
        assertEquals(WakeState.Starting, next(WakeState.Starting, WakeEvent.Arm))
    }

    @Test fun `engine ready starts the session clock`() {
        assertEquals(WakeState.Listening(1_000), next(WakeState.Starting, WakeEvent.EngineReady(1_000)))
    }

    @Test fun `an engine failure stops with its reason`() {
        assertEquals(WakeState.Stopped(WakeStopReason.ENGINE_ERROR, "no mic"), next(WakeState.Starting, WakeEvent.EngineFailed("no mic")))
        assertEquals(WakeState.Stopped(WakeStopReason.ENGINE_ERROR, "x"), next(WakeState.Listening(0), WakeEvent.EngineFailed("x")))
        assertEquals(
            WakeState.Stopped(WakeStopReason.ENGINE_ERROR, "y"),
            next(WakeState.Paused(0, setOf(WakeHold.COPILOT)), WakeEvent.EngineFailed("y")),
        )
    }

    @Test fun `hearing the phrase hands the microphone to one command without resetting the clock`() {
        val busy = next(WakeState.Listening(1_000), WakeEvent.Heard(60_000))
        assertEquals(WakeState.Paused(1_000, setOf(WakeHold.COMMAND), commandUntilMs = 60_000 + WakeMachine.COMMAND_MAX_MS), busy)
        assertFalse(busy.processesAudio)
        assertFalse("the recogniser releases the microphone for the command", busy.micOpen)
        // A second detection while busy is ignored.
        assertSame(busy, next(busy, WakeEvent.Heard(61_000)))
    }

    @Test fun `when the command is done it resumes after a short pause with the original start time`() {
        val busy = next(WakeState.Listening(1_000), WakeEvent.Heard(60_000))
        val resuming = next(busy, WakeEvent.CommandDone(70_000))
        assertEquals(WakeState.Responding(1_000, 70_000 + WakeMachine.RESUME_PAUSE_MS), resuming)
        assertTrue("the microphone is reopened but its audio is discarded", resuming.micOpen)
        assertFalse(resuming.processesAudio)
        assertSame(resuming, next(resuming, tick(70_000 + WakeMachine.RESUME_PAUSE_MS - 1)))
        assertEquals(WakeState.Listening(1_000), next(resuming, tick(70_000 + WakeMachine.RESUME_PAUSE_MS)))
    }

    @Test fun `a stuck command is released by the watchdog`() {
        val busy = next(WakeState.Listening(0), WakeEvent.Heard(10_000))
        assertSame(busy, next(busy, tick(10_000 + WakeMachine.COMMAND_MAX_MS - 1)))
        assertEquals(
            WakeState.Responding(0, 10_000 + WakeMachine.COMMAND_MAX_MS + WakeMachine.RESUME_PAUSE_MS),
            next(busy, tick(10_000 + WakeMachine.COMMAND_MAX_MS)),
        )
    }

    @Test fun `the copilot holds the microphone and listening resumes when it closes`() {
        val held = next(WakeState.Listening(500), WakeEvent.Holds(setOf(WakeHold.COPILOT), 2_000))
        assertEquals(WakeState.Paused(500, setOf(WakeHold.COPILOT)), held)
        assertTrue("still an active session", held.active)
        assertFalse(held.micOpen)
        assertEquals(WakeState.Responding(500, 9_000 + WakeMachine.RESUME_PAUSE_MS), next(held, WakeEvent.Holds(emptySet(), 9_000)))
    }

    @Test fun `a running check keeps it paused after the copilot closes, then it resumes`() {
        var s: WakeState = WakeState.Listening(0)
        s = next(s, WakeEvent.Holds(setOf(WakeHold.COPILOT), 1))
        s = next(s, WakeEvent.Holds(setOf(WakeHold.COPILOT, WakeHold.ASSISTANT), 2))
        s = next(s, WakeEvent.Holds(setOf(WakeHold.ASSISTANT), 3))
        assertEquals(WakeState.Paused(0, setOf(WakeHold.ASSISTANT)), s)
        s = next(s, WakeEvent.Holds(emptySet(), 70_000))
        assertEquals(WakeState.Responding(0, 70_000 + WakeMachine.RESUME_PAUSE_MS), s)
    }

    @Test fun `external holds never cancel a command in progress`() {
        val busy = next(WakeState.Listening(0), WakeEvent.Heard(1_000))
        val both = next(busy, WakeEvent.Holds(setOf(WakeHold.ASSISTANT), 2_000))
        assertEquals(setOf(WakeHold.COMMAND, WakeHold.ASSISTANT), (both as WakeState.Paused).holds)
        val stillBusy = next(both, WakeEvent.Holds(emptySet(), 3_000))
        assertEquals(setOf(WakeHold.COMMAND), (stillBusy as WakeState.Paused).holds)
        assertEquals(WakeState.Responding(0, 4_000 + WakeMachine.RESUME_PAUSE_MS), next(stillBusy, WakeEvent.CommandDone(4_000)))
    }

    @Test fun `a hold during the short resume pause pauses again`() {
        val resuming = WakeState.Responding(0, 5_000)
        assertEquals(WakeState.Paused(0, setOf(WakeHold.COPILOT)), next(resuming, WakeEvent.Holds(setOf(WakeHold.COPILOT), 4_000)))
        assertSame(resuming, next(resuming, WakeEvent.Holds(emptySet(), 4_000)))
    }

    @Test fun `the chosen time limit and low battery stop every active state`() {
        val limit = hour!!
        assertEquals(WakeState.Stopped(WakeStopReason.TIMEOUT), next(WakeState.Listening(0), tick(limit)))
        assertEquals(WakeState.Stopped(WakeStopReason.TIMEOUT), next(WakeState.Responding(0, limit + 5_000), tick(limit)))
        assertEquals(WakeState.Stopped(WakeStopReason.TIMEOUT), next(WakeState.Paused(0, setOf(WakeHold.COPILOT)), tick(limit)))
        val low = BatteryReading(10, charging = false, systemLow = false)
        assertEquals(WakeState.Stopped(WakeStopReason.LOW_BATTERY), next(WakeState.Listening(0), tick(1, battery = low)))
        assertEquals(WakeState.Stopped(WakeStopReason.LOW_BATTERY), next(WakeState.Starting, tick(1, battery = low)))
        assertEquals(WakeState.Stopped(WakeStopReason.LOW_BATTERY), next(WakeState.Paused(0, setOf(WakeHold.ASSISTANT)), tick(1, battery = low)))
    }

    @Test fun `until-off keeps listening for days`() {
        val listening = WakeState.Listening(0)
        assertSame(listening, next(listening, tick(5 * 24 * 60 * 60_000L, limit = null)))
    }

    @Test fun `explicit stops win from every active state and keep the first reason`() {
        listOf(WakeState.Starting, WakeState.Listening(0), WakeState.Responding(0, 10), WakeState.Paused(0, setOf(WakeHold.COMMAND))).forEach {
            assertEquals(WakeState.Stopped(WakeStopReason.USER), next(it, WakeEvent.Stop(WakeStopReason.USER)))
        }
        val stopped = WakeState.Stopped(WakeStopReason.TIMEOUT)
        assertSame(stopped, next(stopped, WakeEvent.Stop(WakeStopReason.USER)))
        assertEquals(WakeState.Off, next(WakeState.Off, WakeEvent.Stop(WakeStopReason.USER)))
    }

    @Test fun `a late engine-ready or command-done after a stop does not resume listening`() {
        val stopped = WakeState.Stopped(WakeStopReason.USER)
        assertSame(stopped, next(stopped, WakeEvent.EngineReady(10)))
        assertSame(stopped, next(stopped, WakeEvent.CommandDone(10)))
    }

    @Test fun `only listening feeds audio to the recogniser`() {
        assertTrue(WakeState.Listening(0).processesAudio)
        assertFalse(WakeState.Responding(0, 1).processesAudio)
        assertFalse(WakeState.Starting.processesAudio)
        assertFalse(WakeState.Paused(0, setOf(WakeHold.COPILOT)).processesAudio)
        assertTrue(WakeState.Responding(0, 1).active)
        assertTrue(WakeState.Starting.active)
        assertTrue(WakeState.Paused(0, setOf(WakeHold.COPILOT)).active)
        assertFalse(WakeState.Off.active)
        assertFalse(WakeState.Stopped(WakeStopReason.USER).active)
    }
}
