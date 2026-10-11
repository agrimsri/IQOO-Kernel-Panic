package com.dpdpxray.core.rules

import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.PenaltyBand
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.Severity
import com.dpdpxray.core.netcapture.FlowProtocol
import com.dpdpxray.core.netcapture.FlowRecord
import com.dpdpxray.core.netcapture.HttpExchange
import com.dpdpxray.core.netcapture.Redaction
import com.dpdpxray.core.netcapture.TlsStatus
import com.dpdpxray.core.rules.science.LeakyShopShapes
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C16 and the connection lines of V1, from sessions the full-relay capture recorded (hand-built flows). */
class ConnectionChecksTest {
    private val s = 1_000_000_000L
    private val engine = RulesEngine(TrackerCatalog.loadDefault())
    private val accept = listOf(ConsentEvent(2 * s, ConsentKind.SCREEN_APPEARED), ConsentEvent(5 * s, ConsentKind.ACCEPT, "Accept"))

    private fun session(flows: List<FlowRecord>, lookups: List<String> = listOf("api.shop.app", "cdn.shop.app"), depth: String? = "full-relay") = AuditSession(
        id = "a", targetPackage = "com.shop.app", targetLabel = "ShopNow", plan = AuditPlan.ACCEPT, endedAtNanos = 40 * s,
        netEvents = lookups.mapIndexed { i, h -> NetEvent((1 + i) * s, h) }, consentEvents = accept, freshState = true,
        privateDnsActive = false, flows = flows, captureDepth = depth,
    )

    private fun http(host: String, target: String, t: Long = 6 * s, port: Int = 80, id: Long = 1): FlowRecord {
        val r = Redaction.target(target)
        return FlowRecord(id, FlowProtocol.TCP, "203.0.113.$id", port, t, sni = host, tls = TlsStatus.CLEARTEXT, http = listOf(HttpExchange(t, "GET", host, r.text, sensitive = r.found)))
    }

    private fun tls(host: String?, id: Long, noLookup: Boolean = true, status: TlsStatus = TlsStatus.SNI_ONLY, proto: FlowProtocol = FlowProtocol.TCP) =
        FlowRecord(id, proto, "198.51.100.$id", 443, 7 * s, sni = host, tls = status, noLookup = noLookup)

    private fun c16(vararg sessions: AuditSession) = engine.evaluate(sessions.toList()).findings.firstOrNull { it.checkId == CheckId.C16 }

    @Test
    fun `an e-mail address in a plain-HTTP request is a High, observed C16 that keeps no value`() {
        val f = c16(session(listOf(http("api.shop.app", "/v1/u?email=asha@example.in&ev=open"))))!!
        assertEquals(Severity.HIGH, f.severity)
        assertEquals(EvidenceType.OBSERVED, f.evidenceType)
        assertEquals(listOf("s8(5)"), f.dpdpRefs)
        assertEquals(PenaltyBand.NONE, f.penaltyBand)
        assertEquals(
            "GET api.shop.app/v1/u?email=[email]&ev=open — fields: email (email address) — 6.0 s after launch, 1.0 s after the user chose accept [Plan A · Accept]",
            f.evidence.single(),
        )
        val all = f.explanation + f.evidence.joinToString()
        assertFalse(all.contains("asha"))
        assertTrue("s.8(5)" in f.explanation && "not a verdict" in f.explanation && "bodies" in f.explanation)
        // Under another lens the DPDP sentence gives way to a neutral one; the observation and the caveat stay.
        val eu = com.dpdpxray.core.law.LensText.explanation(f, com.dpdpxray.core.law.LegalProfile.EU)
        assertFalse(eu.text, eu.citesIndia)
        assertTrue(eu.text.contains("not a verdict") && eu.text.contains("plain HTTP"))
    }

    @Test
    fun `field names alone make C16 inferred`() {
        val f = c16(session(listOf(http("api.shop.app", "/v1/u?first_name=Asha&dob=1990-01-02"))))!!
        assertEquals(EvidenceType.INFERRED, f.evidenceType)
        assertEquals(0.6, f.confidence, 0.0)
        assertTrue(f.evidence.single().contains("first_name (person's name, field name only)"))
        assertFalse(f.evidence.single().contains("Asha"))
    }

    @Test
    fun `a tracker host is named as the entity`() {
        val f = c16(session(listOf(http("app-measurement.com", "/a?gaid=38400000-8cf0-11bd-b23e-10b96e40000d"))))!!
        assertTrue(f.entities.isNotEmpty())
    }

    @Test
    fun `no C16 for encrypted connections, harmless cleartext, IDs without an ID field name, or a DNS-only audit`() {
        assertNull(c16(session(listOf(tls("api.shop.app", 1, noLookup = false)))))
        assertNull(c16(session(listOf(http("api.shop.app", "/ping?v=2&request_id=38400000-8cf0-11bd-b23e-10b96e40000d")))))
        assertNull(c16(session(emptyList(), depth = "dns-only")))
        assertNull(c16(session(emptyList(), depth = null)))
    }

    @Test
    fun `C16 alone costs 15 points and keeps the result out of Ready`() {
        val r = engine.evaluate(listOf(session(listOf(http("api.shop.app", "/v1/u?phone=9876543210")))))
        assertEquals(listOf(CheckId.C16), r.findings.filter { it.severity.weight > 0 }.map { it.checkId })
        assertEquals(85, r.score)
        assertEquals(ScoreBand.NEEDS_WORK, r.band)
    }

    @Test
    fun `V1 names servers the connections stated that no lookup asked for`() {
        val v1 = engine.evaluate(listOf(session(listOf(
            tls("dns.google", 1), tls("api.shop.app", 2), tls("hidden.example-tracker.com", 3),
            tls("looked.example", 4, noLookup = false), tls(null, 5),
        )))).findings.single { it.checkId == CheckId.V1 }
        val line = v1.evidence.single()
        assertTrue(line, line.startsWith("Plan A · Accept: the app connected to dns.google, hidden.example-tracker.com (names read from the connection itself"))
        assertFalse("a looked-up name is not listed", line.contains("api.shop.app"))
        assertFalse("an address a lookup answered is not listed", line.contains("looked.example"))
        assertTrue(v1.explanation.contains("named from the connection itself"))
    }

    @Test
    fun `V1 reports Encrypted Client Hello and unnamed QUIC connections, and stays silent for DNS-only audits`() {
        val v1 = engine.evaluate(listOf(session(listOf(
            tls("public.example-ech.net", 1, noLookup = false, status = TlsStatus.ECH_HIDDEN),
            tls(null, 2, proto = FlowProtocol.QUIC, status = TlsStatus.OPAQUE),
        )))).findings.single { it.checkId == CheckId.V1 }
        assertEquals(2, v1.evidence.size)
        assertTrue(v1.evidence[0].contains("1 connections offered Encrypted Client Hello"))
        assertTrue(v1.evidence[1].contains("1 QUIC (HTTP/3) connections could not be named"))
        val dnsOnly = session(listOf(tls("dns.google", 1)), depth = "dns-only")
        assertNull(engine.evaluate(listOf(dnsOnly)).findings.firstOrNull { it.checkId == CheckId.V1 })
    }

    @Test
    fun `LeakyShop's connections in a full-relay audit leave its score unchanged`() {
        val before = engine.evaluate(LeakyShopShapes.aiAssisted)
        // What the leaky build's network code does: a TLS handshake to graph.facebook.com after Reject (looked up first),
        // DNS over HTTPS to 1.1.1.1 by address (no server name), and a bare TCP connection to the DoH answer.
        val flows = listOf(
            FlowRecord(1, FlowProtocol.TCP, "157.240.1.1", 443, 10 * s, sni = "graph.facebook.com", tls = TlsStatus.SNI_ONLY, bytesUp = 517, dnsName = "graph.facebook.com"),
            FlowRecord(2, FlowProtocol.TCP, "1.1.1.1", 443, 1 * s, tls = TlsStatus.OPAQUE, bytesUp = 600, noLookup = true),
            FlowRecord(3, FlowProtocol.TCP, "18.66.1.1", 443, 2 * s, noLookup = true),
        )
        val after = engine.evaluate(LeakyShopShapes.aiAssisted.map { it.copy(flows = flows, captureDepth = "full-relay") })
        assertEquals(before.score, after.score)
        assertEquals(before.band, after.band)
        assertNull(after.findings.firstOrNull { it.checkId == CheckId.C16 })
    }
}
