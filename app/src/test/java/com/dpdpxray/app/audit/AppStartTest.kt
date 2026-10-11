package com.dpdpxray.app.audit

import com.dpdpxray.core.model.StartMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every plan starts the app fresh when Shizuku can do it: a restart (force-stop, data kept) for any app, a reset (data
 * cleared) only with approval. Nothing is deleted without approval, and what happened is always in the notes.
 */
class AppStartTest {
    private class Shell(var ready: Boolean = true, var running: Boolean? = true, var stopFails: Boolean = false, var clearFails: Boolean = false) {
        val calls = mutableListOf<String>()
        fun start() = AppStart(
            shizukuReady = { ready },
            processRunning = { calls += "pidof $it"; running },
            forceStop = { calls += "force-stop $it"; if (stopFails) Result.failure(IllegalStateException("exit 1: no such user")) else Result.success("") },
            clearData = { calls += "clear $it"; if (clearFails) Result.failure(IllegalStateException("exit 1: denied")) else Result.success("Success") },
        )
    }

    @Test fun `a personal app is restarted with its data kept, never cleared`() = runBlocking {
        val shell = Shell()
        val o = shell.start().prepare("com.reskill.hacktracker", resetApproved = false)
        assertEquals(StartMode.RESTARTED, o.mode)
        assertFalse(o.freshState)
        assertEquals(true, o.runningBefore)
        assertEquals(listOf("pidof com.reskill.hacktracker", "force-stop com.reskill.hacktracker"), shell.calls)
        assertTrue(o.notes.toString(), AppStart.RESTARTED_NOTE in o.notes)
        assertTrue("kept data stays marked for the consent-history rules", o.notes.any { it.startsWith("App data was not cleared") })
        assertNull(o.problem)
    }

    @Test fun `an approved reset stops, clears and says so`() = runBlocking {
        val shell = Shell(running = false)
        val o = shell.start().prepare("com.leakyshop.demo", resetApproved = true)
        assertEquals(StartMode.RESET, o.mode)
        assertTrue(o.freshState)
        assertEquals(listOf("pidof com.leakyshop.demo", "force-stop com.leakyshop.demo", "clear com.leakyshop.demo"), shell.calls)
        assertEquals(listOf(AppStart.RESET_NOTE), o.notes)
    }

    @Test fun `without Shizuku nothing is stopped or cleared and the note says the app may have been running`() = runBlocking {
        val shell = Shell(ready = false)
        val o = shell.start().prepare("com.reskill.hacktracker", resetApproved = true)
        assertEquals(StartMode.NOT_RESTARTED, o.mode)
        assertTrue(shell.calls.isEmpty())
        assertNull(o.runningBefore)
        assertTrue(o.notes.any { it.startsWith("App data was not cleared") })
        assertTrue(o.notes.any { it.startsWith("Could not restart the app before this plan") })
    }

    @Test fun `Shizuku dying mid-audit leaves a truthful not-restarted plan`() = runBlocking {
        val shell = Shell(stopFails = true)
        val o = shell.start().prepare("com.reskill.hacktracker", resetApproved = false)
        assertEquals(StartMode.NOT_RESTARTED, o.mode)
        assertTrue(o.problem!!.contains("no such user"))
        assertTrue(o.notes.any { it.startsWith("Could not restart the app before this plan") })
        val reset = Shell(stopFails = true).start().prepare("com.leakyshop.demo", resetApproved = true)
        assertTrue("the existing reset-failure marker stays", reset.notes.any { it.startsWith("Could not clear app data because stopping the test app failed") })
        assertFalse(reset.freshState)
    }

    @Test fun `a failed clear after a good stop is a restart with data kept, and says so`() = runBlocking {
        val o = Shell(clearFails = true).start().prepare("com.leakyshop.demo", resetApproved = true)
        assertEquals(StartMode.RESTARTED, o.mode)
        assertFalse(o.freshState)
        assertTrue(o.notes.any { it.startsWith("Could not clear app data:") })
        assertTrue(AppStart.RESTARTED_NOTE in o.notes)
    }
}
