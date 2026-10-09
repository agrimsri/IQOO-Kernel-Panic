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
}
