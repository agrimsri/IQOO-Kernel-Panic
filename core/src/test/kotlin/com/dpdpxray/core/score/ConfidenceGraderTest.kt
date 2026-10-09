package com.dpdpxray.core.score

import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.CaptureHealth
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.score.ConfidenceGrader.HIGH_MIN_LOOKUPS
import com.dpdpxray.core.score.ConfidenceGrader.MIN_LOOKUPS
import com.dpdpxray.core.score.ConfidenceGrader.MIN_WINDOW_NANOS
import com.dpdpxray.core.score.ConfidenceLevel.HIGH
import com.dpdpxray.core.score.ConfidenceLevel.LOW
import com.dpdpxray.core.score.ConfidenceLevel.MEDIUM
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfidenceGraderTest {
    private val S = 1_000_000_000L

    private fun events(n: Int) = List(n) { NetEvent(it.toLong(), "h$it.example") }

    /** A clean 30 s capture of [parsed] lookups, all relayed. */
    private fun healthy(parsed: Long = 100) = CaptureHealth(
        captureStartedNanos = 0, captureEndedNanos = 30 * S,
        dnsQueriesParsed = parsed, malformedPackets = 0, dnsResponsesRelayed = parsed, dnsRelayFailures = 0,
        malformedResponses = 0, eventsRecorded = parsed, eventsDropped = 0, eventBufferCapacity = 5_000,
        firstLookupNanos = 1 * S, lastLookupNanos = 29 * S,
        dnsUpstreamPayloadBytesSent = parsed * 32, dnsUpstreamDatagramsSent = parsed,
        dnsUpstreamPayloadBytesReceived = parsed * 90, dnsUpstreamDatagramsReceived = parsed,
    )

    private fun level(h: CaptureHealth?, n: Int = 100) = ConfidenceGrader.grade(h, events(n)).level

    @Test
    fun `a clean capture is High and says why`() {
        val g = ConfidenceGrader.grade(healthy(41), events(41))
        assertEquals(HIGH, g.level)
        assertEquals("Evidence confidence: High: 41 lookups, none malformed", g.summary)
    }

    @Test
    fun `zero events is Low with or without health`() {
        assertEquals(LOW, level(null, 0))
        val g = ConfidenceGrader.grade(healthy(0), emptyList())
        assertEquals(LOW, g.level)
        assertEquals("No DNS lookups were captured", g.reasons.first())
    }

    @Test
    fun `zero events grade Low without changing the audit result`() {
        val session = AuditSession(id = "s", targetPackage = "p", targetLabel = "P", plan = AuditPlan.SILENT, captureHealth = healthy(0))
        val engine = RulesEngine(TrackerCatalog.loadDefault())
        val before = engine.evaluate(listOf(session))
        assertEquals(LOW, ConfidenceGrader.gradeAudit(listOf(session)).level)
        assertEquals(before, engine.evaluate(listOf(session)))
        assertTrue("V0 behaviour kept", before.findings.any { it.checkId == CheckId.V0 } && before.score == null)
    }

    @Test
    fun `lookup count thresholds`() {
        assertEquals(LOW, level(healthy(MIN_LOOKUPS - 1L), MIN_LOOKUPS - 1))
        assertEquals(MEDIUM, level(healthy(MIN_LOOKUPS.toLong()), MIN_LOOKUPS))
        assertEquals(MEDIUM, level(healthy(HIGH_MIN_LOOKUPS - 1L), HIGH_MIN_LOOKUPS - 1))
        assertEquals(HIGH, level(healthy(HIGH_MIN_LOOKUPS.toLong()), HIGH_MIN_LOOKUPS))
        assertEquals("1 lookup was captured; at least 2 are needed for any confidence",
            ConfidenceGrader.grade(healthy(1), events(1)).reasons.first())
    }

    @Test
    fun `lookups counted by the capture include dropped ones`() {
        val h = healthy(6_000).copy(eventsRecorded = 5_000, eventsDropped = 1_000)
        assertTrue(ConfidenceGrader.grade(h, events(5_000)).summary.contains("6000 lookups"))
    }

    @Test
    fun `capture window threshold`() {
        assertEquals(MEDIUM, level(healthy().copy(captureEndedNanos = MIN_WINDOW_NANOS - 1)))
        assertEquals(HIGH, level(healthy().copy(captureEndedNanos = MIN_WINDOW_NANOS)))
    }

    @Test
    fun `malformed packet share thresholds`() {
        assertEquals(HIGH, level(healthy(98).copy(malformedPackets = 2)))   // 2.0 %
        assertEquals(MEDIUM, level(healthy(98).copy(malformedPackets = 3))) // 3/101
        assertEquals(MEDIUM, level(healthy(80).copy(malformedPackets = 20))) // 20.0 %
        assertEquals(LOW, level(healthy(80).copy(malformedPackets = 21)))    // 21/101
    }

    @Test
    fun `non-DNS tunnel traffic does not lower confidence`() {
        val g = ConfidenceGrader.grade(healthy(98).copy(malformedPackets = 40, nonDnsPackets = 40, hiddenDnsPackets = 0), events(98))
        assertEquals(HIGH, g.level)
        assertEquals("98 lookups, none malformed", g.detail)
    }

    @Test
    fun `malformed share counts only unreadable DNS once the breakdown is known`() {
        // 3 unreadable DNS of 101 packets (98 + 3) exceeds 2 %; the 40 non-DNS packets are left out.
        assertEquals(MEDIUM, level(healthy(98).copy(malformedPackets = 43, nonDnsPackets = 40, hiddenDnsPackets = 0)))
        assertEquals(HIGH, level(healthy(98).copy(malformedPackets = 42, nonDnsPackets = 40, hiddenDnsPackets = 0)))
    }

    @Test
    fun `encrypted or TCP DNS attempts cap at Medium with a fix`() {
        // The observed phone shape: Private DNS on Automatic, 8 DNS-over-TLS SYNs, 5 IPv6 control packets.
        val h = healthy(100).copy(malformedPackets = 13, hiddenDnsPackets = 8, nonDnsPackets = 5)
        val g = ConfidenceGrader.grade(h, events(100))
        assertEquals(MEDIUM, g.level)
        assertEquals(
            "8 packets used encrypted or TCP DNS, which TraceVeil cannot read; turn Private DNS off and re-run",
            g.reasons.first(),
        )
        assertEquals(HIGH, level(h.copy(malformedPackets = 5, hiddenDnsPackets = 0)))
    }

    @Test
    fun `an inconsistent breakdown is not trusted down to zero`() {
        // Parts larger than the total: judge the whole total as unreadable, as older builds did.
        val g = ConfidenceGrader.grade(healthy(80).copy(malformedPackets = 21, hiddenDnsPackets = 0, nonDnsPackets = 50), events(80))
        assertEquals(LOW, g.level)
    }

    @Test
    fun `relay failure share thresholds`() {
        assertEquals(HIGH, level(healthy().copy(dnsRelayFailures = 5)))
        assertEquals(MEDIUM, level(healthy().copy(dnsRelayFailures = 6)))
        assertEquals(MEDIUM, level(healthy().copy(dnsRelayFailures = 25)))
        assertEquals(LOW, level(healthy().copy(dnsRelayFailures = 26)))
    }

    @Test
    fun `malformed reply share thresholds`() {
        assertEquals(HIGH, level(healthy().copy(malformedResponses = 5)))
        assertEquals(MEDIUM, level(healthy().copy(malformedResponses = 6)))
        assertEquals(MEDIUM, level(healthy().copy(malformedResponses = 25)))
        assertEquals(LOW, level(healthy().copy(malformedResponses = 26)))
        val noReplies = ConfidenceGrader.grade(healthy().copy(dnsResponsesRelayed = 0, malformedResponses = 0, dnsRelayFailures = 100), events(100))
        assertEquals(LOW, noReplies.level)
        assertTrue("no reply ratio without replies", noReplies.reasons.none { it.contains("could not be fully read") })
    }

    @Test
    fun `dropped event thresholds`() {
        assertEquals(HIGH, level(healthy().copy(eventsDropped = 0)))
        assertEquals(MEDIUM, level(healthy().copy(eventsDropped = 1)))
        assertEquals(MEDIUM, level(healthy().copy(eventsDropped = 10)))
        assertEquals(LOW, level(healthy().copy(eventsDropped = 11)))
    }

    @Test
    fun `unknown health is never treated as healthy`() {
        val g = ConfidenceGrader.grade(null, events(100))
        assertEquals(MEDIUM, g.level)
        assertEquals("Capture health was not recorded for this audit", g.reasons.first())
        val missing = ConfidenceGrader.grade(healthy().copy(malformedPackets = null, dnsRelayFailures = null), events(100))
        assertEquals(MEDIUM, missing.level)
        assertEquals("Not measured: malformed packets, relay failures", missing.reasons.first())
        assertEquals(MEDIUM, level(CaptureHealth(eventBufferCapacity = 5_000, notes = listOf("Capture was not running"))))
    }

    @Test
    fun `capture notes cap the grade and are shown`() {
        val g = ConfidenceGrader.grade(healthy().copy(notes = listOf("Capture restarted after a 4.0 s gap; lookups during the gap were not seen.")), events(100))
        assertEquals(MEDIUM, g.level)
        assertTrue(g.reasons.contains("Capture restarted after a 4.0 s gap; lookups during the gap were not seen."))
    }

    @Test
    fun `the most limiting reason comes first`() {
        val g = ConfidenceGrader.grade(healthy().copy(eventsDropped = 50, dnsRelayFailures = 6), events(100))
        assertEquals(LOW, g.level)
        assertTrue(g.reasons.first(), g.reasons.first().startsWith("50 lookups were dropped"))
        assertTrue(g.reasons.any { it.startsWith("6 of 100 lookups got no reply") })
    }

    @Test
    fun `an audit takes its weakest session, labelled by plan`() {
        fun s(plan: AuditPlan, h: CaptureHealth?, n: Int) =
            AuditSession(id = plan.name, targetPackage = "p", targetLabel = "P", plan = plan, netEvents = events(n), captureHealth = h)
        val g = ConfidenceGrader.gradeAudit(listOf(s(AuditPlan.ACCEPT, healthy(), 100), s(AuditPlan.REJECT, healthy(5), 5)))
        assertEquals(MEDIUM, g.level)
        assertTrue(g.reasons.first(), g.reasons.first().startsWith("Plan B · Reject: "))
        assertEquals(LOW, ConfidenceGrader.gradeAudit(emptyList()).level)
    }
}
