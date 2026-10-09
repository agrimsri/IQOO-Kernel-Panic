package com.dpdpxray.core.model

import com.dpdpxray.core.report.AuditJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Additive NetEvent.answerStatus / rcode: null stays "not recorded", distinct from every measured outcome. */
class AnswerStatusContractTest {
    private val legacyJson = javaClass.getResource("/compat/sessions-v0-baseline.json")!!.readText()

    @Test
    fun `audits saved before the field existed decode as not recorded`() {
        AuditJson.decodeSessions(legacyJson).single().netEvents.forEach {
            assertNull(it.answerStatus)
            assertNull(it.rcode)
        }
    }

    @Test
    fun `every status and rcode round-trips, and null stays null`() {
        val events = AnswerStatus.entries.mapIndexed { i, s -> NetEvent(i.toLong(), "h$i.example", answerStatus = s, rcode = if (s == AnswerStatus.COMPLETE) 3 else null) } +
            NetEvent(99, "pending.example")
        val session = AuditSession(id = "x", targetPackage = "p", targetLabel = "P", plan = AuditPlan.SILENT, netEvents = events)
        val decoded = AuditJson.decodeSessions(AuditJson.encodeSessions(listOf(session))).single()
        assertEquals(events, decoded.netEvents)
        assertNull(decoded.netEvents.last().answerStatus)
        assertEquals(3, decoded.netEvents.first { it.answerStatus == AnswerStatus.COMPLETE }.rcode)
    }

    @Test
    fun `the status set is exactly the agreed contract`() {
        assertEquals(
            listOf("NO_REPLY", "COMPLETE", "PARTIAL", "MISMATCHED", "MALFORMED"),
            AnswerStatus.entries.map { it.name },
        )
    }

    @Test
    fun `older positional construction still compiles to the same event`() {
        val e = NetEvent(1, "a.example", 28, "X", Category.ANALYTICS, Phase.AFTER_REJECT, "com.google.android.gms")
        assertTrue(e.answerStatus == null && e.rcode == null)
    }
}
