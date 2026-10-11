package com.dpdpxray.app.quarantine

import com.dpdpxray.core.quarantine.AddressScope
import com.dpdpxray.core.quarantine.QuarantineObservations
import com.dpdpxray.core.quarantine.QuarantineRecorder
import com.dpdpxray.core.quarantine.QuarantineReports
import com.dpdpxray.core.quarantine.QuarantineStopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QuarantineRoutesTextTest {

    @Test
    fun `route for a package and rejection of anything that is not a package name`() {
        assertEquals("quarantine/com.traceveil.riskdemo", QuarantineRoutes.forPackage("com.traceveil.riskdemo"))
        assertTrue(QuarantineRoutes.isValidPackage("com.leakyshop.demo.fixed"))
        for (bad in listOf("", "com..x", "com.x/../../home", "com x", ".com.x", "com.x.")) {
            assertFalse(bad, QuarantineRoutes.isValidPackage(bad))
        }
        assertThrows(IllegalArgumentException::class.java) { QuarantineRoutes.forPackage("a/b") }
    }

    private fun report(o: QuarantineObservations) = QuarantineReports.build(
        id = "q", targetPackage = "com.traceveil.riskdemo", targetLabel = "Risk Demo", startedAtEpochMs = 0, startedAtNanos = 0,
        endedAtNanos = 60_000_000_000, plannedDurationMs = 60_000, stopReason = QuarantineStopReason.TIMER,
        resolverAddresses = QuarantineNet.RESOLVERS, observations = o,
    )

    @Test
    fun `an empty run says nothing was seen and that this is not proof`() {
        val text = QuarantineText.headline(report(QuarantineObservations()))
        assertTrue(text.startsWith("Nothing was seen in 60.0 s"))
        assertTrue("not proof" in text)
    }

    @Test
    fun `a busy run counts names, addresses, packets and dialogs`() {
        val r = QuarantineRecorder(QuarantineNet.RESOLVERS)
        r.recordDns(1, "panel.example", 1, QuarantineNet.RESOLVER_V4)
        r.recordPermissionDialog(2, "com.google.android.permissioncontroller", "Allow Risk Demo to send and view SMS messages?", true)
        val text = QuarantineText.headline(report(r.snapshot()))
        assertEquals(
            "In 60.0 s, Risk Demo tried to look up 1 name and showed 1 permission dialog. Nothing it tried could leave through its own connections.",
            text,
        )
    }

    @Test
    fun `answered connections are counted in the headline and described without values`() {
        val r = QuarantineRecorder(QuarantineNet.RESOLVERS)
        r.recordDns(1, "panel.example", 1, QuarantineNet.RESOLVER_V4)
        val target = com.dpdpxray.core.netcapture.Redaction.target("/c?email=asha@example.in")
        val http = com.dpdpxray.core.netcapture.HttpExchange(5, "GET", "c.example.in", target.text, sensitive = target.found)
        val flows = listOf(
            com.dpdpxray.core.netcapture.FlowRecord(1, com.dpdpxray.core.netcapture.FlowProtocol.TCP, "203.0.113.9", 443, 2, sni = "sdk.example.com", tls = com.dpdpxray.core.netcapture.TlsStatus.SNI_ONLY, alpn = listOf("h2")),
            com.dpdpxray.core.netcapture.FlowRecord(2, com.dpdpxray.core.netcapture.FlowProtocol.TCP, "203.0.113.10", 80, 3, sni = "c.example.in", tls = com.dpdpxray.core.netcapture.TlsStatus.CLEARTEXT, http = listOf(http)),
        )
        val text = QuarantineText.headline(report(r.snapshot().copy(connections = flows, connectionsRead = true)))
        assertEquals(
            "In 60.0 s, Risk Demo tried to look up 1 name and opened 2 connections that TraceVeil answered on the phone. " +
                "Nothing it tried could leave through its own connections.",
            text,
        )
        assertEquals("TLS", QuarantineText.connectionLabel(flows[0]))
        assertEquals("203.0.113.9:443 · name read from the TLS hello · h2 · catalogued as Demo (analytics)", QuarantineText.connectionNote(flows[0], "Demo (analytics)"))
        assertEquals("HTTP", QuarantineText.connectionLabel(flows[1]))
        val note = QuarantineText.connectionNote(flows[1], null)
        assertEquals("203.0.113.10:80 · plain HTTP: GET /c?email=[email] · personal-data fields: email (email address)", note)
        assertFalse(note.contains("asha"))
    }

    @Test
    fun `settings screens are stated without claiming who opened them`() {
        val r = QuarantineRecorder(QuarantineNet.RESOLVERS)
        r.recordSettingsScreen(1, com.dpdpxray.core.quarantine.SensitiveSettings.ACCESSIBILITY, "Accessibility")
        assertEquals(
            "In 60.0 s, Risk Demo made no lookups or connection attempts. Accessibility settings appeared during the run. " +
                "Nothing it tried could leave through its own connections.",
            QuarantineText.headline(report(r.snapshot())),
        )
    }

    @Test
    fun `wording never gives a verdict on the app`() {
        val all = (QuarantineText.STEPS + QuarantineText.EXPLAINER + QuarantineText.ONLY_INTERNET + QuarantineText.NOT_A_VERDICT +
            QuarantineText.bypassExplanation(listOf("203.0.113.9")) + QuarantineText.namesWithoutLookupExplanation(listOf("x.example")) +
            QuarantineText.cleartextExplanation(listOf("GET x.example/a")) + QuarantineReports.LIMITS).joinToString(" ").lowercase()
        for (word in listOf("malicious", "is safe", "fraud", "illegal", "malware detected", "virus")) assertFalse(word, word in all)
    }

    @Test
    fun `durations and scope notes`() {
        assertEquals("60 s", QuarantineText.duration(60_000))
        assertEquals("2 min", QuarantineText.duration(120_000))
        assertEquals("5 min", QuarantineText.duration(300_000))
        assertNull(QuarantineText.scopeNote(AddressScope.PUBLIC))
        assertEquals("local discovery (multicast)", QuarantineText.scopeNote(AddressScope.MULTICAST))
    }
}
