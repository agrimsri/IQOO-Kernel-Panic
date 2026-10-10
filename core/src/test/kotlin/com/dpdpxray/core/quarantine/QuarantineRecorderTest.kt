package com.dpdpxray.core.quarantine

import com.dpdpxray.core.quarantine.QuarantinePackets.icmp
import com.dpdpxray.core.quarantine.QuarantinePackets.ipv4
import com.dpdpxray.core.quarantine.QuarantinePackets.ipv6
import com.dpdpxray.core.quarantine.QuarantinePackets.tcp
import com.dpdpxray.core.quarantine.QuarantinePackets.udp
import com.dpdpxray.core.quarantine.QuarantinePackets.v4
import com.dpdpxray.core.quarantine.QuarantinePackets.v6
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuarantineRecorderTest {
    private val resolvers = setOf("10.111.0.53", "fd00:111::53")
    private fun recorder(maxDomains: Int = 1_000, maxDestinations: Int = 1_000, maxUiEvents: Int = 100) =
        QuarantineRecorder(resolvers, maxDomains, maxDestinations, maxUiEvents)

    private fun syn(ip: String, port: Int) = IpHeaders.parse(ipv4(v4(ip), 6, tcp(40000, port, 0x02)))!!

    @Test
    fun `repeated lookups of one name are one row with a count, first and last time and query types`() {
        val r = recorder()
        r.recordDns(1_000, "api.example.com", 1, "10.111.0.53")
        r.recordDns(2_000, "api.example.com", 28, "10.111.0.53")
        r.recordDns(3_000, "api.example.com", 1, "10.111.0.53")
        r.recordDns(2_500, "cdn.example.net", 1, "10.111.0.53")
        val o = r.snapshot()
        assertEquals(4, o.dnsQueries)
        assertEquals(listOf("api.example.com", "cdn.example.net"), o.domains.map { it.hostname })
        val api = o.domains.first()
        assertEquals(3, api.count); assertEquals(1_000, api.firstNanos); assertEquals(3_000, api.lastNanos)
        assertEquals(listOf(1, 28), api.queryTypes)
        assertEquals(listOf("10.111.0.53"), api.resolvers)
    }

    @Test
    fun `repeated packets to one destination are deduplicated into a packet count`() {
        val r = recorder()
        repeat(4) { r.recordPacket(1_000L + it, syn("203.0.113.9", 443)) }
        r.recordPacket(2_000, syn("203.0.113.9", 80))
        r.recordPacket(2_100, IpHeaders.parse(ipv4(v4("203.0.113.9"), 17, udp(1, 443)))!!)
        val o = r.snapshot()
        assertEquals(6, o.blockedPackets)
        assertEquals(3, o.blocked.size)
        val first = o.blocked.first()
        assertEquals("203.0.113.9", first.address); assertEquals(443, first.port); assertEquals(AttemptProtocol.TCP_SYN, first.protocol)
        assertEquals(4, first.packets); assertEquals(1_000, first.firstNanos); assertEquals(1_003, first.lastNanos)
        assertEquals(DestinationRole.APP_DESTINATION, first.role)
    }

    @Test
    fun `unreadable and network-control packets are counted, not listed`() {
        val r = recorder()
        r.recordPacket(1, null)
        r.recordPacket(2, IpHeaders.parse(ipv6(v6("ff02::2"), 58, icmp(133)))!!)
        val o = r.snapshot()
        assertEquals(1, o.unreadablePackets)
        assertEquals(1, o.networkControlPackets)
        assertTrue(o.blocked.isEmpty())
        assertEquals(0, o.blockedPackets)
    }

    @Test
    fun `TCP or TLS DNS to the phone's own tunnel resolver is marked as the phone resolver, not an app destination`() {
        val r = recorder()
        r.recordPacket(1, syn("10.111.0.53", 853))
        val row = r.snapshot().blocked.single()
        assertEquals(DestinationRole.PHONE_RESOLVER, row.role)
        val s = QuarantineSummary.of(r.snapshot(), resolvers)
        assertTrue(s.ipsAttempted.isEmpty())
        // Usually Android's own Private DNS check, so it is kept apart from what the app itself tried.
        assertTrue(s.encryptedDnsDestinations.isEmpty())
        assertEquals(listOf("10.111.0.53:853 (TCP SYN)"), s.phoneResolverDnsAttempts)
        assertFalse(s.dnsBypassSignal)
        assertFalse(s.nothingObserved) // blocked DNS is still something observed
    }

    @Test
    fun `bounded lists count overflow instead of growing`() {
        val r = recorder(maxDomains = 2, maxDestinations = 1)
        r.recordDns(1, "a.example", 1, "10.111.0.53")
        r.recordDns(2, "b.example", 1, "10.111.0.53")
        r.recordDns(3, "c.example", 1, "10.111.0.53")
        r.recordDns(4, "a.example", 1, "10.111.0.53") // existing row still counts
        r.recordPacket(5, syn("203.0.113.1", 443))
        r.recordPacket(6, syn("203.0.113.2", 443))
        val o = r.snapshot()
        assertEquals(2, o.domains.size); assertEquals(1, o.domainsOverflow); assertEquals(4, o.dnsQueries)
        assertEquals(2, o.domains.first().count)
        assertEquals(1, o.blocked.size); assertEquals(1, o.destinationsOverflow); assertEquals(2, o.blockedPackets)
    }

    @Test
    fun `summary lists unique domains and IPs with counts`() {
        val r = recorder()
        r.recordDns(1, "api.example.com", 1, "10.111.0.53")
        r.recordDns(2, "api.example.com", 28, "10.111.0.53")
        r.recordDns(3, "t.example.org", 1, "10.111.0.53")
        r.recordPacket(4, syn("203.0.113.9", 443))
        r.recordPacket(5, syn("203.0.113.9", 8443))
        r.recordPacket(6, IpHeaders.parse(ipv6(v6("2001:db8::7"), 17, udp(1, 443)))!!)
        val s = QuarantineSummary.of(r.snapshot(), resolvers)
        assertEquals(listOf("api.example.com", "t.example.org"), s.domainsAttempted)
        assertEquals(listOf("203.0.113.9", "2001:db8::7"), s.ipsAttempted)
        assertEquals(2, s.uniqueDomains); assertEquals(2, s.uniqueIps); assertEquals(3, s.uniqueDestinations)
        assertEquals(3, s.dnsQueries); assertEquals(3, s.blockedPackets)
        assertFalse(s.nothingObserved)
    }

    @Test
    fun `connection to an address no lookup returned raises the DNS-bypass (hard-coded address) signal`() {
        val r = recorder()
        r.recordDns(1, "api.example.com", 1, "10.111.0.53")
        r.recordPacket(10, syn("203.0.113.9", 443))
        val s = QuarantineSummary.of(r.snapshot(), resolvers) // quarantine answers every lookup NXDOMAIN: no answers
        assertTrue(s.dnsBypassSignal)
        assertEquals(listOf("203.0.113.9"), s.addressesWithoutLookup)
    }

    @Test
    fun `an address answered by DNS before the attempt does not raise the signal, one answered only afterwards does`() {
        val r = recorder()
        r.recordPacket(10, syn("203.0.113.9", 443))
        r.recordPacket(20, syn("198.51.100.4", 443))
        val s = QuarantineSummary.of(r.snapshot(), resolvers, dnsAnswers = mapOf("203.0.113.9" to 5L, "198.51.100.4" to 25L))
        assertEquals(listOf("198.51.100.4"), s.addressesWithoutLookup)
        assertTrue(s.dnsBypassSignal)
        val clean = QuarantineSummary.of(r.snapshot(), resolvers, dnsAnswers = mapOf("203.0.113.9" to 5L, "198.51.100.4" to 15L))
        assertFalse(clean.dnsBypassSignal)
        assertTrue(clean.addressesWithoutLookup.isEmpty())
    }

    @Test
    fun `multicast, broadcast and link-local discovery do not count as hard-coded server addresses`() {
        val r = recorder()
        r.recordPacket(1, IpHeaders.parse(ipv4(v4("239.255.255.250"), 17, udp(1, 1900)))!!)
        r.recordPacket(2, IpHeaders.parse(ipv4(v4("255.255.255.255"), 17, udp(1, 67)))!!)
        val s = QuarantineSummary.of(r.snapshot(), resolvers)
        assertEquals(2, s.ipsAttempted.size)
        assertFalse(s.dnsBypassSignal)
    }

    @Test
    fun `DNS sent straight to another resolver and DNS over TLS are reported`() {
        val r = recorder()
        r.recordDns(1, "x.example", 1, "8.8.8.8")
        r.recordPacket(2, syn("1.1.1.1", 853))
        r.recordPacket(3, syn("9.9.9.9", 53))
        val s = QuarantineSummary.of(r.snapshot(), resolvers)
        assertEquals(listOf("8.8.8.8"), s.directResolvers)
        assertEquals(listOf("1.1.1.1:853 (TCP SYN)", "9.9.9.9:53 (TCP SYN)"), s.encryptedDnsDestinations)
        // DNS-transport destinations are reported as DNS attempts (Android's Private DNS may send them for the app),
        // not as hard-coded server addresses.
        assertFalse(s.dnsBypassSignal)
        assertEquals(listOf("1.1.1.1", "9.9.9.9"), s.ipsAttempted)
    }

    @Test
    fun `permission dialogs and settings screens are deduplicated by text and kind with a shown count`() {
        val r = recorder()
        assertTrue(r.recordPermissionDialog(10, "com.google.android.permissioncontroller", "Allow Demo to send and view SMS messages?", true))
        assertFalse(r.recordPermissionDialog(20, "com.google.android.permissioncontroller", "Allow Demo to send and view SMS messages?", true))
        assertTrue(r.recordPermissionDialog(30, "com.google.android.permissioncontroller", "Allow Demo to access your contacts?", true))
        assertTrue(r.recordSettingsScreen(40, SensitiveSettings.ACCESSIBILITY, "Accessibility"))
        assertFalse(r.recordSettingsScreen(50, SensitiveSettings.ACCESSIBILITY, "Accessibility"))
        r.recordTargetOnScreen(5); r.recordTargetOnScreen(7)
        val o = r.snapshot()
        assertEquals(2, o.permissionDialogs.size)
        assertEquals(2, o.permissionDialogs.first().timesShown); assertEquals(10, o.permissionDialogs.first().firstNanos)
        assertEquals(1, o.settingsScreens.size); assertEquals(2, o.settingsScreens.single().timesShown)
        assertEquals(5L, o.targetOnScreenNanos)
        val s = QuarantineSummary.of(o, resolvers)
        assertEquals(2, s.permissionDialogs)
        assertEquals(listOf(SensitiveSettings.ACCESSIBILITY), s.settingsScreens)
        assertTrue(s.appCameToScreen)
    }

    @Test
    fun `an empty run is reported as nothing observed, not as safe`() {
        val s = QuarantineSummary.of(recorder().snapshot(), resolvers)
        assertTrue(s.nothingObserved)
        assertFalse(s.dnsBypassSignal)
        assertFalse(s.appCameToScreen)
        assertNull(recorder().snapshot().targetOnScreenNanos)
    }
}
