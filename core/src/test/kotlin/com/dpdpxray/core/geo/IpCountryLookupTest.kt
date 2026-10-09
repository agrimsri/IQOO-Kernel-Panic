package com.dpdpxray.core.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IpCountryLookupTest {
    private fun v4(a: Int, b: Int, c: Int, d: Int) = byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())

    private val lookup = IpCountryLookup(MmdbReader.open(MmdbBuilder(6).also {
        it.insert(v4(8, 8, 0, 0), 16, it.record(MmdbBuilder.country("US", "US")))
        it.insert(v4(103, 0, 0, 0), 8, it.record(MmdbBuilder.country("IN", "SG")))
        it.insert(v4(45, 0, 0, 0), 8, it.record(MmdbBuilder.country(null, "NL")))   // registered only, no location
        it.insert(v4(46, 0, 0, 0), 8, it.record(MmdbBuilder.country("zz", "1x")))    // not valid ISO codes
    }.build()))

    @Test
    fun `located and registered countries stay separate`() {
        assertEquals(IpCountry("IN", "SG"), lookup.lookup("103.5.5.5"))
        assertEquals(IpCountry("US", "US"), lookup.lookup("8.8.8.8"))
    }

    @Test
    fun `an address with only a registered country has no located country`() {
        assertEquals(IpCountry(null, "NL"), lookup.lookup("45.1.2.3"))
    }

    @Test
    fun `codes that are not ISO 3166 countries are dropped, not shown`() {
        assertNull(lookup.lookup("46.1.2.3"))
    }

    @Test
    fun `addresses not in the table are null`() = assertNull(lookup.lookup("9.9.9.9"))

    @Test
    fun `parses IPv4 and IPv6 literals`() {
        assertEquals(listOf<Byte>(8, 8, 4, 4), IpLiteral.parse("8.8.4.4")?.toList())
        assertEquals(16, IpLiteral.parse("2001:db8::1")?.size)
        assertEquals(16, IpLiteral.parse("::1")?.size)
    }

    @Test
    fun `anything that is not a literal address is rejected and never resolved over DNS`() {
        listOf("", "example.com", "graph.facebook.com", "8.8.8", "256.1.1.1", "1.2.3.4.5", "01.2.3.4", "8.8.8.8 ", "g::1", "1:2:3", "::ffff:1.2.3.4.5").forEach {
            assertNull(it, IpLiteral.parse(it))
            assertNull(it, lookup.lookup(it))
        }
    }

    @Test
    fun `country names and flags come from ISO codes only`() {
        assertEquals("India", CountryNames.name("IN"))
        assertEquals("🇮🇳", CountryNames.flag("IN"))
        assertTrue(CountryNames.isIso("US"))
        assertFalse(CountryNames.isIso("ZZ"))
        assertFalse(CountryNames.isIso("usa"))
        assertEquals("ZZ", CountryNames.name("ZZ")) // never invents a name
    }

    @Test
    fun `build date is exposed for the freshness note`() = assertEquals(1_700_000_000L, lookup.buildEpochSeconds)

    private fun reserved(ip: String) = IpLiteral.isReserved(IpLiteral.parse(ip)!!)

    @Test
    fun `special-purpose IPv4 ranges are reserved`() {
        listOf("0.0.0.0", "0.1.2.3", "10.0.0.1", "10.255.255.255", "100.64.0.1", "100.127.255.255", "127.0.0.1", "169.254.169.254", "172.16.0.1", "172.31.255.255",
            "192.0.0.1", "192.0.2.5", "192.168.1.1", "198.18.0.1", "198.19.255.255", "198.51.100.7", "203.0.113.9", "224.0.0.251", "239.255.255.250", "240.0.0.1", "255.255.255.255")
            .forEach { assertTrue(it, reserved(it)) }
    }

    @Test
    fun `addresses just outside the reserved ranges are public`() {
        listOf("8.8.8.8", "9.255.255.255", "11.0.0.0", "100.63.255.255", "100.128.0.0", "126.255.255.255", "128.0.0.1", "169.253.0.1", "172.15.255.255", "172.32.0.0",
            "192.0.1.1", "192.169.0.1", "198.17.255.255", "198.20.0.0", "223.255.255.255", "1.1.1.1", "103.21.244.1")
            .forEach { assertFalse(it, reserved(it)) }
    }

    @Test
    fun `special-purpose IPv6 ranges are reserved and public ones are not`() {
        listOf("::", "::1", "fe80::1", "febf::1", "fc00::1", "fd12:3456::1", "ff02::1", "2001:db8::1", "2001:db8:ffff::1").forEach { assertTrue(it, reserved(it)) }
        listOf("2001:4860:4860::8888", "2606:4700:4700::1111", "2a03:2880:f12f:83:face:b00c:0:25de", "2001:db9::1").forEach { assertFalse(it, reserved(it)) }
    }

    @Test
    fun `IPv4-mapped and NAT64 IPv6 addresses are judged by the IPv4 address inside`() {
        assertTrue(reserved("::ffff:10.0.0.1"))
        assertTrue(reserved("::ffff:127.0.0.1"))
        assertFalse(reserved("::ffff:8.8.8.8"))
        assertTrue(reserved("64:ff9b::192.168.0.1"))
        assertFalse(reserved("64:ff9b::8.8.8.8"))
    }

    @Test
    fun `reserved addresses are not looked up even if the table would answer`() {
        val l = IpCountryLookup(MmdbReader.open(MmdbBuilder(4).also { it.insert(v4(10, 0, 0, 0), 8, it.record(MmdbBuilder.country("US", "US"))) }.build()))
        assertNull(l.lookup("10.1.2.3"))
    }

    @Test
    fun `IPv6 text forms parse to the same 16 bytes`() {
        val full = IpLiteral.parse("2001:0db8:0000:0000:0000:0000:0000:0001")!!
        assertEquals(full.toList(), IpLiteral.parse("2001:db8::1")!!.toList())
        assertEquals(full.toList(), IpLiteral.parse("2001:DB8:0:0:0:0:0:1")!!.toList())
        assertEquals(List(16) { 0.toByte() }, IpLiteral.parse("::")!!.toList())
        assertEquals((List(12) { 0.toByte() } + listOf<Byte>(1, 2, 3, 4)), IpLiteral.parse("::1.2.3.4")!!.toList())
        assertNull(IpLiteral.parse("1::2::3"))
        assertNull(IpLiteral.parse("12345::1"))
        assertNull(IpLiteral.parse("1:2:3:4:5:6:7:8:9"))
    }
}
