package com.dpdpxray.app.audit

import com.dpdpxray.app.data.SampleAudit
import com.dpdpxray.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConsentReliabilityTest {
    private val reject = SampleAudit.sessions(1000).first { it.plan == AuditPlan.REJECT }
    private val missed = reject.copy(consentEvents = emptyList(), screenAudit = null)

    @Test fun `late dialog is found by periodic probing without another window event`() = runBlocking {
        var now = 0L
        var probes = 0
        val event = ConsentEvent(1, ConsentKind.SCREEN_APPEARED)
        val waiter = ConsentScreenWaiter({ probes++; if (now >= 1000) event else null }, { now }, { now += it })
        assertEquals(event, waiter.await(1500))
        assertEquals(3, probes)
    }

    @Test fun `missing dialog probes are bounded by the timeout`() = runBlocking {
        var now = 0L
        var probes = 0
        val waiter = ConsentScreenWaiter({ probes++; null }, { now }, { now += it }, 250)
        assertNull(waiter.await(500))
        assertEquals(500L, now)
        assertEquals(3, probes)
    }

    @Test fun `already visible dialog does not wait`() = runBlocking {
        val event = ConsentEvent(1, ConsentKind.SCREEN_APPEARED)
        val waiter = ConsentScreenWaiter({ event }, { 0 }, { fail("Unexpected wait") })
        assertEquals(event, waiter.await(2000))
    }

    @Test fun `controlled missing dialog retries once and retains the failed attempt`() = runBlocking {
        val attempts = mutableListOf<Int>()
        val saved = ConsentPlanRetry.capture(AuditPlan.REJECT, "com.leakyshop.demo", { true }, { it }) { attempt ->
            attempts += attempt
            if (attempt == 0) missed else reject
        }
        assertEquals(listOf(0, 1), attempts)
        assertEquals(listOf(missed, reject), saved)
    }

    @Test fun `even a second failed attempt is never retried again`() = runBlocking {
        var calls = 0
        val saved = ConsentPlanRetry.capture(AuditPlan.REJECT, "com.leakyshop.demo.fixed", { true }, { it }) { calls++; missed }
        assertEquals(2, calls)
        assertEquals(2, saved.size)
    }

    @Test fun `personal app is never reset again by retry policy`() = runBlocking {
        var calls = 0
        ConsentPlanRetry.capture(AuditPlan.REJECT, "com.personal.bank", { true }, { it }) { calls++; missed }
        assertEquals(1, calls)
    }

    @Test fun `no reset permission prevents retry`() = runBlocking {
        var calls = 0
        ConsentPlanRetry.capture(AuditPlan.REJECT, "com.leakyshop.demo", { false }, { it }) { calls++; missed }
        assertEquals(1, calls)
    }

    @Test fun `Silent intentionally requires no consent choice and does not retry`() = runBlocking {
        var calls = 0
        ConsentPlanRetry.capture(AuditPlan.SILENT, "com.leakyshop.demo", { true }, { it }) { calls++; missed.copy(plan = AuditPlan.SILENT) }
        assertEquals(1, calls)
    }

    @Test fun `successful first attempt is not repeated`() = runBlocking {
        var calls = 0
        ConsentPlanRetry.capture(AuditPlan.REJECT, "com.leakyshop.demo", { true }, { it }) { calls++; reject }
        assertEquals(1, calls)
    }

    @Test fun `unsuccessful initial data reset prevents automatic retry`() = runBlocking {
        var calls = 0
        ConsentPlanRetry.capture(AuditPlan.REJECT, "com.leakyshop.demo", { true }, { it }) {
            calls++; missed.copy(notes = listOf("Could not clear app data: refused"))
        }
        assertEquals(1, calls)
    }
}
