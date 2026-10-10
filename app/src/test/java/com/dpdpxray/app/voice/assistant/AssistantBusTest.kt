package com.dpdpxray.app.voice.assistant

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantBusTest {
    private val open = AssistantAction.Navigate(AssistantPlanner.ROUTE_REPORTS)

    @After fun clear() {
        AssistantBus.pending.value?.let { AssistantBus.take(it, nowMs = 0) }
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
