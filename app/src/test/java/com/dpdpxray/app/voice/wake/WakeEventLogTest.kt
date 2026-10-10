package com.dpdpxray.app.voice.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeEventLogTest {
    private fun heard(reason: WakeHeard.Reason, conf: Double? = null) = WakeHeard(reason == WakeHeard.Reason.MATCH, reason, conf)

    @Test fun `counts accepted and rejected phrase decisions`() {
        var log = WakeEventLog()
        log = log.record(heard(WakeHeard.Reason.MATCH, 0.98), WakeSensitivity.STRICT, 1)
        log = log.record(heard(WakeHeard.Reason.LOW_CONFIDENCE, 0.81), WakeSensitivity.STRICT, 2)
        log = log.record(heard(WakeHeard.Reason.SPEECH_BEFORE, 0.95), WakeSensitivity.STRICT, 3)
        log = log.record(heard(WakeHeard.Reason.LOW_CONFIDENCE, 0.60), WakeSensitivity.NORMAL, 4)
        assertEquals(1, log.accepted)
        assertEquals(3, log.rejected)
        assertEquals(mapOf(WakeHeard.Reason.LOW_CONFIDENCE to 2, WakeHeard.Reason.SPEECH_BEFORE to 1), log.rejectedBy)
        assertEquals("newest first", 4L, log.recent.first().atWallMs)
    }

    @Test fun `ordinary non-phrase speech is not counted`() {
        val log = WakeEventLog()
        assertSame(log, log.record(heard(WakeHeard.Reason.NO_MATCH), WakeSensitivity.STRICT, 1))
        assertSame(log, log.record(heard(WakeHeard.Reason.UNREADABLE), WakeSensitivity.STRICT, 1))
    }

    @Test fun `keeps only the most recent events and no words or audio`() {
        var log = WakeEventLog()
        repeat(WakeEventLog.MAX_RECENT + 5) { i -> log = log.record(heard(WakeHeard.Reason.MATCH, 0.9), WakeSensitivity.STRICT, i.toLong()) }
        assertEquals(WakeEventLog.MAX_RECENT, log.recent.size)
        assertEquals(WakeEventLog.MAX_RECENT + 5, log.accepted)
        // The record type only carries a time, an outcome, a confidence and the mode.
        val fields = WakeEventRecord::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") }.toSet()
        assertEquals(setOf("atWallMs", "reason", "confidence", "sensitivity"), fields)
    }

    @Test fun `every outcome has a short plain description`() {
        WakeHeard.Reason.entries.forEach { assertTrue(it.name, WakeEventLog.describe(it).isNotBlank()) }
    }
}
