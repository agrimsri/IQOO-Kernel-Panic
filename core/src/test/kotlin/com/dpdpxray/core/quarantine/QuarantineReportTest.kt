package com.dpdpxray.core.quarantine

import com.dpdpxray.core.quarantine.QuarantinePackets.ipv4
import com.dpdpxray.core.quarantine.QuarantinePackets.tcp
import com.dpdpxray.core.quarantine.QuarantinePackets.v4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuarantineReportTest {
    private val resolvers = setOf("10.111.0.53", "fd00:111::53")

    private fun sample(): QuarantineReport {
        val r = QuarantineRecorder(resolvers)
        r.recordTargetOnScreen(1_200_000_000)
        r.recordDns(1_500_000_000, "panel.example.com", 1, "10.111.0.53")
        r.recordDns(1_600_000_000, "panel.example.com", 1, "10.111.0.53")
        r.recordPacket(2_000_000_000, IpHeaders.parse(ipv4(v4("203.0.113.9"), 6, tcp(40000, 443, 0x02)))!!)
        r.recordPermissionDialog(3_000_000_000, "com.google.android.permissioncontroller", "Allow Risk Demo to send and view SMS messages?", true)
        r.recordSettingsScreen(4_000_000_000, SensitiveSettings.DISPLAY_OVER_OTHER_APPS, "Display over other apps")
        return QuarantineReports.build(
            id = "quarantine-1", targetPackage = "com.traceveil.riskdemo", targetLabel = "Risk Demo",
            startedAtEpochMs = 1_791_600_000_000, startedAtNanos = 1_000_000_000, endedAtNanos = 61_000_000_000,
            plannedDurationMs = 60_000, stopReason = QuarantineStopReason.TIMER, resolverAddresses = resolvers,
            observations = r.snapshot(), notes = listOf("note"), deviceModel = "vivo I2501", androidVersion = "16", privateDnsActive = false,
        )
    }

    @Test
    fun `report JSON round-trips exactly`() {
        val report = sample()
        val text = QuarantineReports.encode(report)
        assertEquals(report, QuarantineReports.decode(text))
        assertTrue(text.contains("\"schemaVersion\""))
    }

    @Test
    fun `unknown future fields are ignored when reading`() {
        val text = QuarantineReports.encode(sample()).replaceFirst("{", "{\n  \"addedLater\": 42,")
        assertEquals(sample(), QuarantineReports.decode(text))
    }

    @Test
    fun `report carries the computed summary and the honest limits`() {
        val report = sample()
        assertEquals(listOf("panel.example.com"), report.summary.domainsAttempted)
        assertEquals(listOf("203.0.113.9"), report.summary.ipsAttempted)
        assertTrue(report.summary.dnsBypassSignal)
        assertEquals(60_000, report.observedMs)
        assertEquals(QuarantineReports.LIMITS, report.limits)
        assertTrue(report.limits.any { "not a malware verdict" in it.lowercase() })
        assertTrue(report.limits.any { "sms" in it.lowercase() })
        assertTrue(report.limits.any { "after quarantine ends" in it.lowercase() })
        assertTrue(report.limits.any { "encrypted dns" in it.lowercase() })
    }

    @Test
    fun `timeline is in time order with offsets from the start and evidence labels`() {
        val t = sample().timeline
        assertEquals(
            listOf(TimelineKind.APP_ON_SCREEN, TimelineKind.LOOKUP, TimelineKind.BLOCKED, TimelineKind.PERMISSION_DIALOG, TimelineKind.SETTINGS_SCREEN),
            t.map { it.kind },
        )
        assertEquals(listOf(200L, 500L, 1_000L, 2_000L, 3_000L), t.map { it.atMs })
        assertTrue(t[1].text.contains("panel.example.com") && t[1].text.contains("2"))
        assertTrue(t[2].text.contains("203.0.113.9:443"))
        assertTrue(t[3].text.contains("SMS"))
        assertTrue(t.all { it.evidence == QuarantineEvidence.OBSERVED })
    }

    @Test
    fun `text never calls the app malicious or safe`() {
        val text = QuarantineReports.encode(sample()).lowercase()
        for (word in listOf("malicious", "malware detected", "fraudulent", " is safe", "illegal")) {
            assertTrue("found '$word'", word !in text)
        }
    }
}
