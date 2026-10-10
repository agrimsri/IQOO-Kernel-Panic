package com.dpdpxray.app.voice.assistant

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantBusTest {
    private val open = AssistantAction.Navigate(AssistantPlanner.ROUTE_REPORTS)

    @After fun clear() {
        AssistantBus.pending.value?.let { AssistantBus.take(it, nowMs = 0) }
    }

    @Test fun `a cancelled request is withdrawn and can never run later`() = runTest {
        val job = launch { AssistantBus.request(open, hindi = false, speak = true, waitMs = 10_000, nowMs = 0) }
        runCurrent()
        val req = AssistantBus.pending.value
        assertNotNull(req)
        job.cancel()
        runCurrent()
        assertNull("a cancelled \"check Instagram\" must not start when TraceVeil opens later", AssistantBus.pending.value)
        assertTrue(req!!.result.isCompleted)
        assertEquals(UiOutcome.NotReached(), req.result.await())
        assertNull(AssistantBus.take(req, nowMs = 0))
    }

    @Test fun `a request that timed out stays queued for the notification tap`() = runTest {
        val outcome = AssistantBus.request(open, hindi = false, speak = true, waitMs = 1_000, nowMs = 0)
        assertEquals(UiOutcome.NotReached(), outcome)
        val req = AssistantBus.pending.value
        assertNotNull(req)
        assertSame(req, AssistantBus.take(req!!, nowMs = 0))
    }

    @Test fun `a request the host performed returns its outcome`() = runTest {
        val outcome = async { AssistantBus.request(open, hindi = true, speak = false, waitMs = 10_000, nowMs = 0) }
        runCurrent()
        val req = AssistantBus.take(AssistantBus.pending.value!!, nowMs = 0)!!
        assertTrue(req.hindi)
        assertFalse("the speak flag reaches the host, which passes it to the end-of-check follow-up", req.speak)
        AssistantBus.complete(req, UiOutcome.Done)
        assertEquals(UiOutcome.Done, outcome.await())
        assertNull(AssistantBus.pending.value)
    }
}
