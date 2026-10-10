package com.dpdpxray.core.rules

import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.CaptureHealth
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.Severity
import com.dpdpxray.core.report.AuditJson
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V1: capture-health signs that some lookups were invisible. INFO only; unknown stays unknown. */
class HiddenLookupsTest {
    private val s = 1_000_000_000L
    private val engine = RulesEngine(TrackerCatalog.loadDefault())

    /** Counters shaped like a real plan (see the Facebook replay): six IPv6 control packets, nothing hidden. */
    private val healthy = CaptureHealth(
        captureStartedNanos = 0, captureEndedNanos = 30 * s, dnsQueriesParsed = 12, malformedPackets = 6,
        dnsResponsesRelayed = 12, dnsRelayFailures = 0, malformedResponses = 0, eventsRecorded = 12, eventsDropped = 0,
        eventBufferCapacity = 5_000, hiddenDnsPackets = 0, nonDnsPackets = 6,
    )

    private fun session(health: CaptureHealth?, privateDns: Boolean? = false) = AuditSession(
        id = "c", targetPackage = "com.shop.app", targetLabel = "Shop", plan = AuditPlan.SILENT, endedAtNanos = 30 * s,
        netEvents = listOf(NetEvent(1 * s, "api.shop.app"), NetEvent(2 * s, "cdn.shop.app")), captureHealth = health,
        privateDnsActive = privateDns, freshState = true,
    )

    private fun v1(vararg sessions: AuditSession) = engine.evaluate(sessions.toList()).findings.firstOrNull { it.checkId == CheckId.V1 }

    @Test
    fun `IPv6 control packets alone are not hidden lookups (real replay counters)`() {
        val replay = AuditJson.decodeSessions(javaClass.getResource("/replays/facebook-audit-1791603680860.sessions.json")!!.readText())
        assertEquals(6L, replay.single().captureHealth?.nonDnsPackets)
        assertNull(engine.evaluate(replay).findings.firstOrNull { it.checkId == CheckId.V1 })
        assertNull(v1(session(healthy)))
    }

    @Test
    fun `encrypted or TCP DNS packets give V1 info and keep the score`() {
        val r = engine.evaluate(listOf(session(healthy.copy(malformedPackets = 9, hiddenDnsPackets = 3))))
        val f = r.findings.single { it.checkId == CheckId.V1 }
        assertEquals(Severity.INFO, f.severity)
        assertEquals(EvidenceType.OBSERVED, f.evidenceType)
        assertEquals(
            listOf("Plan C · No interaction: 3 packets used DNS over TLS (port 853) or DNS over TCP, which TraceVeil cannot read; the names in them are not in this evidence."),
            f.evidence,
        )
        assertEquals(100, r.score)
        assertEquals(ScoreBand.READY, r.band)
    }

    @Test
    fun `Private DNS on, dropped events and unreadable DNS packets are each listed`() {
        val f = v1(session(healthy.copy(malformedPackets = 8, eventsDropped = 40, eventBufferCapacity = 5_000), privateDns = true))!!
        assertEquals(3, f.evidence.size)
        assertTrue(f.evidence[0].contains("Private DNS was on"))
        assertTrue(f.evidence[1].contains("40 lookups were dropped because the event buffer was full (5000 events)"))
        assertTrue(f.evidence[2].contains("2 DNS packets could not be read as questions"))
    }

    @Test
    fun `without the packet breakdown unreadable DNS is not guessed`() {
        assertNull(v1(session(healthy.copy(malformedPackets = 8, hiddenDnsPackets = null, nonDnsPackets = null))))
    }

    @Test
    fun `no capture health recorded establishes nothing`() {
        assertNull(v1(session(null, privateDns = null)))
    }

    @Test
    fun `V1 never makes the result inconclusive, V0 still does`() {
        val empty = session(healthy.copy(dnsQueriesParsed = 0, hiddenDnsPackets = 4, malformedPackets = 10), privateDns = true).copy(netEvents = emptyList())
        val r = engine.evaluate(listOf(empty))
        assertEquals(setOf(CheckId.V0, CheckId.V1), r.findings.map { it.checkId }.toSet())
        assertEquals(ScoreBand.INCONCLUSIVE, r.band)
    }
}
