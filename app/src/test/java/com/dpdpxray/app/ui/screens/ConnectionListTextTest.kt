package com.dpdpxray.app.ui.screens

import com.dpdpxray.core.model.Phase
import com.dpdpxray.core.netcapture.ConnectionSummary
import com.dpdpxray.core.netcapture.FlowProtocol
import com.dpdpxray.core.netcapture.FlowRecord
import com.dpdpxray.core.netcapture.HttpExchange
import com.dpdpxray.core.netcapture.Redaction
import com.dpdpxray.core.netcapture.TlsStatus
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionListTextTest {
    private val s = 1_000_000_000L
    private val catalog = TrackerCatalog.loadDefault()

    @Test
    fun `rows group connections by destination and say what each first message showed, never a value`() {
        val r = Redaction.target("/c?email=asha@example.in")
        val flows = listOf(
            FlowRecord(1, FlowProtocol.TCP, "157.240.1.1", 443, 10 * s, sni = "graph.facebook.com", tls = TlsStatus.SNI_ONLY, phase = Phase.AFTER_REJECT),
            FlowRecord(2, FlowProtocol.TCP, "157.240.1.1", 443, 11 * s, sni = "graph.facebook.com", tls = TlsStatus.SNI_ONLY, phase = Phase.AFTER_REJECT),
            FlowRecord(3, FlowProtocol.TCP, "203.0.113.30", 80, 2 * s, sni = "c.example.in", tls = TlsStatus.CLEARTEXT, noLookup = true,
                http = listOf(HttpExchange(2 * s, "GET", "c.example.in", r.text, sensitive = r.found)), phase = Phase.BEFORE_CONSENT),
            FlowRecord(4, FlowProtocol.QUIC, "198.51.100.4", 443, 3 * s, tls = TlsStatus.OPAQUE, phase = Phase.BEFORE_CONSENT),
        )
        val rows = ConnectionListText.rows(flows, catalog, { ConnectionListText.phase(it.phase) })
        assertEquals(listOf("c.example.in", "198.51.100.4", "graph.facebook.com"), rows.map { it.title })
        assertEquals(
            "port 80 · plain HTTP · before the choice · no lookup seen · GET /c?email=[email] · personal-data fields: email (email address)",
            rows[0].subtitle,
        )
        assertTrue(rows[0].cleartext)
        assertEquals("port 443 · QUIC · before the choice · name not read", rows[1].subtitle)
        assertTrue(rows[2].subtitle, rows[2].subtitle.startsWith("port 443 · TLS · after Reject · 2 connections · catalogued as "))
        assertFalse(rows.joinToString { it.subtitle }.contains("asha"))
    }

    @Test
    fun `summaries and the quarantine time label`() {
        val n = ConnectionSummary("full-relay", 9, 6, 3, 3, listOf("dns.google"), 0, 0)
        assertEquals("9 connections, 6 named by the connection itself · 3 plain-HTTP requests · 1 names without a lookup", ConnectionListText.auditSummary(n))
        val f = FlowRecord(1, FlowProtocol.TCP, "203.0.113.9", 443, 3_500_000_000)
        assertEquals("+2.5 s into the run", ConnectionListText.secondsInto(f, 1_000_000_000))
        assertTrue(ConnectionListText.QUARANTINE_NOTE.contains("nothing was sent"))
        assertTrue(ConnectionListText.AUDIT_NOTE.contains("not readable"))
    }
}
