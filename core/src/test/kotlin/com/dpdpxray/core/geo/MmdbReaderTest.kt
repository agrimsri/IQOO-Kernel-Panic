package com.dpdpxray.core.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MmdbReaderTest {
    private fun v4(a: Int, b: Int, c: Int, d: Int) = byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())
    private fun v6(vararg groups: Int) = ByteArray(16).also { out -> groups.forEachIndexed { i, g -> out[2 * i] = (g shr 8).toByte(); out[2 * i + 1] = g.toByte() } }

    private fun iso(m: Map<String, Any?>?, key: String) = ((m?.get(key) as? Map<*, *>)?.get("iso_code")) as String?

    @Test
    fun `reads an IPv4 tree at every record size`() {
        for (size in listOf(24, 28, 32)) {
            val b = MmdbBuilder(4, size)
            b.insert(v4(8, 8, 0, 0), 16, b.record(MmdbBuilder.country("US", "US")))
            b.insert(v4(103, 0, 0, 0), 8, b.record(MmdbBuilder.country("IN", "SG")))
            val r = MmdbReader.open(b.build())
            assertEquals("size $size", "US", iso(r.find(v4(8, 8, 8, 8)), "country"))
            assertEquals("size $size", "SG", iso(r.find(v4(103, 9, 9, 9)), "registered_country"))
            assertNull("size $size outside", r.find(v4(9, 9, 9, 9)))
        }
    }

    @Test
    fun `IPv4 addresses are found inside an IPv6 tree and native IPv6 works`() {
        val b = MmdbBuilder(6)
        b.insert(v4(8, 8, 0, 0), 16, b.record(MmdbBuilder.country("US", "US")))
        b.insert(v6(0x2a00, 0x1450), 32, b.record(MmdbBuilder.country("IE", "US")))
        val r = MmdbReader.open(b.build())
        assertEquals("US", iso(r.find(v4(8, 8, 4, 4)), "country"))
        assertEquals("IE", iso(r.find(v6(0x2a00, 0x1450, 0x4001, 0, 0, 0, 0, 0x200e)), "country"))
        assertNull(r.find(v4(1, 1, 1, 1)))
        assertNull(r.find(v6(0x2001, 0xdb8)))
    }

    @Test
    fun `an IPv6 address cannot be looked up in an IPv4-only database`() {
        val b = MmdbBuilder(4)
        b.insert(v4(8, 8, 0, 0), 16, b.record(MmdbBuilder.country("US", null)))
        assertNull(MmdbReader.open(b.build()).find(v6(0x2001, 0xdb8)))
    }

    @Test
    fun `follows pointers and decodes long strings, arrays and uint types`() {
        val b = MmdbBuilder(4)
        val first = b.record(MmdbBuilder.map("country" to MmdbBuilder.map("iso_code" to MmdbBuilder.str("DE")), "pad" to MmdbBuilder.str("x".repeat(40))))
        b.insert(v4(5, 0, 0, 0), 8, first)
        // second record is a pointer to the first record's offset (0 into the data section)
        b.insert(v4(6, 0, 0, 0), 8, b.record(MmdbBuilder.pointer(0)))
        b.insert(v4(7, 0, 0, 0), 8, b.record(MmdbBuilder.map("list" to MmdbBuilder.array(MmdbBuilder.uint(5, 300), MmdbBuilder.uint(6, 70_000)))))
        val r = MmdbReader.open(b.build())
        assertEquals("DE", iso(r.find(v4(5, 1, 1, 1)), "country"))
        assertEquals("x".repeat(40), r.find(v4(5, 1, 1, 1))?.get("pad"))
        assertEquals("DE", iso(r.find(v4(6, 1, 1, 1)), "country"))
        assertEquals(listOf(300L, 70_000L), r.find(v4(7, 1, 1, 1))?.get("list"))
    }

    @Test
    fun `exposes metadata`() {
        val r = MmdbReader.open(MmdbBuilder(4).also { it.insert(v4(1, 0, 0, 0), 8, it.record(MmdbBuilder.country("AU", "AU"))) }.build(buildEpoch = 1_760_000_000L))
        assertEquals(1_760_000_000L, r.buildEpochSeconds)
        assertEquals("GeoLite2-Country", r.databaseType)
        assertEquals(4, r.ipVersion)
    }

    @Test
    fun `rejects files that are not MaxMind databases without throwing anything else`() {
        listOf(ByteArray(0), ByteArray(100), "not a database".toByteArray(), ByteArray(4096) { 7 }).forEach {
            try { MmdbReader.open(it); fail("accepted garbage") } catch (_: MmdbFormatException) {}
        }
    }

    @Test
    fun `a truncated or corrupted database fails closed`() {
        val good = MmdbBuilder(4).also { it.insert(v4(8, 8, 0, 0), 16, it.record(MmdbBuilder.country("US", "US"))) }.build()
        for (cut in listOf(good.size / 2, good.size - 5, 20)) {
            try {
                val r = MmdbReader.open(good.copyOf(cut))
                r.find(v4(8, 8, 8, 8)) // may legitimately miss, must never crash
            } catch (_: MmdbFormatException) {}
        }
        // a pointer loop in the data section must not hang or overflow the stack
        val b = MmdbBuilder(4)
        b.insert(v4(8, 0, 0, 0), 8, b.record(MmdbBuilder.pointer(0)))
        val r = MmdbReader.open(b.build())
        try { assertNull(r.find(v4(8, 1, 1, 1))) } catch (_: MmdbFormatException) { assertTrue(true) }
    }

    @Test
    fun `address of the wrong length is not found`() {
        val r = MmdbReader.open(MmdbBuilder(4).also { it.insert(v4(8, 0, 0, 0), 8, it.record(MmdbBuilder.country("US", null))) }.build())
        assertNull(r.find(ByteArray(5)))
        assertNull(r.find(ByteArray(0)))
    }

    @Test fun `unsigned 32 bit pointer cannot become a negative tree index`() {
        val bytes = MmdbBuilder(4, 32).also {
            it.insert(v4(8, 8, 0, 0), 16, it.record(MmdbBuilder.country("US", "US")))
        }.build()
        repeat(4) { bytes[it] = 0xFF.toByte() }
        val reader = MmdbReader.open(bytes)
        try {
            reader.find(v4(8, 8, 8, 8))
            fail("impossible pointer must fail closed")
        } catch (_: MmdbFormatException) { }
        assertNull(IpCountryLookup(reader).lookup("8.8.8.8"))
    }

    @Test fun `invalid build dates are rejected before date presentation`() {
        for (epoch in listOf(Long.MAX_VALUE, -1L, 253_402_300_800L)) {
            val bytes = MmdbBuilder(4).also {
                it.insert(v4(8, 8, 0, 0), 16, it.record(MmdbBuilder.country("US", "US")))
            }.build(buildEpoch = epoch)
            try {
                MmdbReader.open(bytes)
                fail("invalid build epoch $epoch accepted")
            } catch (_: MmdbFormatException) { }
        }
    }
}
