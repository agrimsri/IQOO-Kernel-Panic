package com.dpdpxray.core.capture

import com.dpdpxray.core.model.AnswerStatus
import com.dpdpxray.core.model.CaptureHealth
import com.dpdpxray.core.report.AuditJson
import com.dpdpxray.core.score.ConfidenceGrader
import com.dpdpxray.core.score.ConfidenceLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keeps the shared samples internally consistent and graded as their docs say. */
class CaptureSamplesTest {
    private val measured = listOf(
        CaptureSamples.healthy, CaptureSamples.overflow, CaptureSamples.resolverTrouble, CaptureSamples.interrupted,
        CaptureSamples.privateDnsAutomatic,
    )

    @Test
    fun `measured samples obey the counter identities`() {
        measured.forEach { h ->
            assertEquals(h.dnsQueriesParsed, h.eventsRecorded!! + h.eventsDropped!!)
            assertEquals(h.dnsQueriesParsed, h.dnsResponsesRelayed!! + h.dnsRelayFailures!!)
            assertTrue(h.malformedResponses!! <= h.dnsResponsesRelayed!!)
            assertTrue(h.dnsUpstreamDatagramsSent!! >= h.dnsQueriesParsed!!)
            assertTrue(h.dnsUpstreamDatagramsReceived!! <= h.dnsUpstreamDatagramsSent!!)
            assertTrue(h.eventsRecorded!! <= h.eventBufferCapacity!!)
            assertTrue((h.hiddenDnsPackets ?: 0) + (h.nonDnsPackets ?: 0) <= h.malformedPackets!!)
        }
    }

    @Test
    fun `samples grade as documented`() {
        fun level(h: CaptureHealth?) = ConfidenceGrader.grade(h, List(h?.eventsRecorded?.toInt() ?: 41) { CaptureSamples.answerOutcomes[0] }).level
        assertEquals(ConfidenceLevel.HIGH, level(CaptureSamples.healthy))
        assertEquals(ConfidenceLevel.MEDIUM, level(CaptureSamples.notRecorded))
        assertEquals(ConfidenceLevel.MEDIUM, level(CaptureSamples.notMeasured))
        assertEquals(ConfidenceLevel.LOW, level(CaptureSamples.overflow))
        assertEquals(ConfidenceLevel.MEDIUM, level(CaptureSamples.resolverTrouble))
        assertEquals(ConfidenceLevel.MEDIUM, level(CaptureSamples.interrupted))
        assertEquals(ConfidenceLevel.MEDIUM, level(CaptureSamples.privateDnsAutomatic))
    }

    @Test
    fun `answer samples cover every outcome and only COMPLETE or PARTIAL carry records`() {
        val outcomes = CaptureSamples.answerOutcomes
        assertEquals(AnswerStatus.entries.toSet(), outcomes.mapNotNull { it.answerStatus }.toSet())
        assertTrue(outcomes.any { it.answerStatus == null })
        outcomes.filter { it.answerStatus !in setOf(AnswerStatus.COMPLETE, AnswerStatus.PARTIAL) }.forEach {
            assertTrue(it.answerIps.isEmpty() && it.cnameChain.isEmpty() && it.rcode == null)
        }
    }

    @Test
    fun `samples survive the saved-audit encoder`() {
        measured.forEach { h ->
            assertEquals(h, AuditJson.json.decodeFromString(CaptureHealth.serializer(), AuditJson.json.encodeToString(CaptureHealth.serializer(), h)))
        }
    }
}
