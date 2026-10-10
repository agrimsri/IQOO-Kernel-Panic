package com.dpdpxray.core.geo

import com.dpdpxray.core.model.AnswerStatus
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** The bundled DB-IP Lite table: its record shape, how the map names it, and how the loader picks a table. */
class GeoTableTest {
    private val s = 1_000_000_000L
    private fun v4(a: Int, b: Int, c: Int, d: Int) = byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())

    /** A tiny file in the same shape as the real DB-IP Lite: IPv6 tree, 24-bit records, no registered_country. */
    private val dbipBytes = MmdbBuilder(6, 24).also {
        it.insert(v4(8, 8, 8, 0), 24, it.record(MmdbBuilder.dbipCountry("US", "NA", 6252001)))
        it.insert(v4(81, 2, 69, 0), 24, it.record(MmdbBuilder.dbipCountry("GB", "EU", 2635167)))
        it.insert(v4(49, 44, 0, 0), 16, it.record(MmdbBuilder.dbipCountry("IN", "AS", 1269750)))
        it.insert(byteArrayOf(0x2a, 0x03, 0x28, 0x80.toByte()) + ByteArray(12), 32, it.record(MmdbBuilder.dbipCountry("IE", "EU", 2963597, inEu = true)))
    }.build(buildEpoch = BundledGeoTable.BUILD_EPOCH, type = BundledGeoTable.DATABASE_TYPE)

    private val dbip = IpCountryLookup(MmdbReader.open(dbipBytes))
    private val bundled = GeoSource.Loaded(dbip, TableOrigin.BUNDLED)

    @Test
    fun `DB-IP records place the address and have no registered country`() {
        assertEquals(IpCountry("US", null), dbip.lookup("8.8.8.8"))
        assertEquals(IpCountry("GB", null), dbip.lookup("81.2.69.160"))
        assertEquals(IpCountry("IN", null), dbip.lookup("49.44.0.1"))
        assertEquals(IpCountry("IE", null), dbip.lookup("2a03:2880:f12f:83:face:b00c:0:25de"))
        assertNull(dbip.lookup("9.9.9.9"))
        assertEquals("DBIP-Country-Lite", dbip.databaseType)
        assertEquals(BundledGeoTable.BUILD_EPOCH, dbip.buildEpochSeconds)
    }

    @Test
    fun `the table is identified from its own metadata, not its file name`() {
        assertEquals(GeoTableKind.DBIP_LITE, GeoTableKind.of("DBIP-Country-Lite"))
        assertEquals(GeoTableKind.GEOLITE2, GeoTableKind.of("GeoLite2-Country"))
        assertEquals(GeoTableKind.OTHER, GeoTableKind.of("DBIP-Country"))        // DB-IP's paid edition is not the Lite one
        assertEquals(GeoTableKind.OTHER, GeoTableKind.of("GeoIP2-Country"))
        assertEquals(GeoTableKind.OTHER, GeoTableKind.of(null))
    }

    @Test
    fun `the bundled table is named, dated and credited as DB-IP asks`() {
        val info = GeoTableInfo.of(bundled)
        assertEquals("2026-10-01", info.builtDate)
        assertEquals("DB-IP IP to Country Lite (bundled with TraceVeil, database built 2026-10-01)", info.description)
        assertEquals("Countries from DB-IP Lite · bundled · built 2026-10-01", info.headline)
        assertTrue(info.credit!!, info.credit!!.startsWith("IP Geolocation by DB-IP (https://db-ip.com)"))
        assertTrue(info.credit!!, info.credit!!.contains("CC BY 4.0"))
    }

    @Test
    fun `a GeoLite2 file keeps MaxMind's exact attribution and an unknown table gets none`() {
        val geolite = GeoSource.Loaded(IpCountryLookup(MmdbReader.open(MmdbBuilder(4).build(buildEpoch = 1_790_694_124L))))
        val info = GeoTableInfo.of(geolite)
        assertEquals(TableOrigin.USER_FILE, info.origin)
        assertEquals(MapPresenter.ATTRIBUTION, info.credit)
        assertEquals("Countries from GeoLite2 · your file · built 2026-09-29", info.headline)
        val other = GeoTableInfo.of(GeoSource.Loaded(IpCountryLookup(MmdbReader.open(MmdbBuilder(4).build(type = "Example-Country")))))
        assertNull(other.credit)
        assertTrue(other.description, other.description.contains("\"Example-Country\""))
    }

    @Test
    fun `age notes follow each table's own terms`() {
        val info = GeoTableInfo.of(bundled)
        assertNull(info.ageNote(BundledGeoTable.BUILD_EPOCH + 30 * 86_400L))
        val old = info.ageNote(BundledGeoTable.BUILD_EPOCH + 45 * 86_400L)!!
        assertTrue(old, old.contains("45 days ago"))
        assertFalse(old, old.contains("EULA"))
    }

    // ---- the map footer --------------------------------------------------------------------------------------------

    private val catalog = TrackerCatalog.loadDefault()
    private val session = AuditSession(
        id = "r", targetPackage = "com.leakyshop.demo", targetLabel = "LeakyShop", plan = AuditPlan.REJECT,
        consentEvents = listOf(ConsentEvent(2 * s, ConsentKind.SCREEN_APPEARED), ConsentEvent(8 * s, ConsentKind.REJECT)),
        netEvents = listOf(NetEvent(tNanos = 9 * s, hostname = "graph.facebook.com", answerIps = listOf("8.8.8.8"), answerStatus = AnswerStatus.COMPLETE, rcode = 0)),
    )

    private fun footer(source: GeoSource) =
        MapPresenter.view(EndpointGeoAggregator.build(listOf(session), catalog, source), OwnerRegistry.EMPTY, MapMode.SOURCE_DNS,
            nowEpochSeconds = BundledGeoTable.BUILD_EPOCH + 9 * 86_400L).footer.joinToString("\n")

    @Test
    fun `the map footer says DB-IP Lite was used, when it was built and credits DB-IP, not MaxMind`() {
        val f = footer(bundled)
        assertTrue(f, f.contains("using DB-IP IP to Country Lite (bundled with TraceVeil, database built 2026-10-01)"))
        assertTrue(f, f.contains("IP Geolocation by DB-IP (https://db-ip.com)"))
        assertFalse(f, f.contains("GeoLite Data created by MaxMind"))
        assertTrue(f, f.contains("not where data went"))
    }

    @Test
    fun `with the bundled table a LeakyShop-style lookup is placed without any user file`() {
        val model = EndpointGeoAggregator.build(listOf(session), catalog, bundled)
        assertEquals(listOf("US"), model.countries.map { it.iso })
        assertTrue(model.unknown.isEmpty())
        // DB-IP carries no registered country, so nothing is ever shown as "registered elsewhere".
        assertTrue(model.countries.all { it.registeredElsewhere.isEmpty() })
    }

    @Test
    fun `a note about the table choice is printed in the footer`() {
        val chosen = GeoTableChoice.choose(GeoSource.Unreadable("not a MaxMind database (no metadata marker)")) { bundled }
        val f = footer(chosen)
        assertTrue(f, f.contains("Your GeoLite2-Country.mmdb could not be read (not a MaxMind database (no metadata marker)), so the bundled table was used instead."))
    }

    // ---- choosing a table ------------------------------------------------------------------------------------------

    @Test
    fun `the person's own GeoLite2 file wins and the bundled table is then not even read`() {
        val own = GeoSource.Loaded(IpCountryLookup(MmdbReader.open(MmdbBuilder(4).build())))
        assertSame(own, GeoTableChoice.choose(own) { fail("bundled table read although the user's file loaded"); bundled })
    }

    @Test
    fun `without a file of their own the bundled table is used, silently`() {
        val chosen = GeoTableChoice.choose(null) { bundled }
        assertSame(bundled, chosen)
        assertTrue((chosen as GeoSource.Loaded).notes.isEmpty())
    }

    @Test
    fun `if both fail, both reasons are shown`() {
        val chosen = GeoTableChoice.choose(GeoSource.Unreadable("bad pointer")) { GeoSource.Unreadable("could not read the bundled table") }
        assertEquals(GeoSource.Unreadable("your GeoLite2-Country.mmdb: bad pointer; bundled table: could not read the bundled table"), chosen)
        assertEquals(GeoSource.Unreadable("x"), GeoTableChoice.choose(null) { GeoSource.Unreadable("x") })
    }

    // ---- reading the bundled file ------------------------------------------------------------------------------------

    /** Hands out at most [chunk] bytes per read, like a decompressing stream does. */
    private class Trickle(private val data: ByteArray, private val chunk: Int) : InputStream() {
        private var at = 0
        override fun read(): Int = if (at < data.size) data[at++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (at >= data.size) return -1
            val n = minOf(len, chunk, data.size - at)
            data.copyInto(b, off, at, at + n); at += n
            return n
        }
    }

    @Test
    fun `reading fills one array of the expected size, and shorter or longer streams still read exactly`() {
        val data = ByteArray(10_000) { (it * 31).toByte() }
        assertArrayEquals(data, BundledGeoTable.readFully(Trickle(data, 777), expected = data.size))
        assertArrayEquals(data, BundledGeoTable.readFully(Trickle(data, 777), expected = 4_000))
        assertArrayEquals(data, BundledGeoTable.readFully(Trickle(data, 777), expected = 50_000))
        assertArrayEquals(ByteArray(0), BundledGeoTable.readFully(ByteArrayInputStream(ByteArray(0)), expected = 10))
    }

    @Test
    fun `a stream larger than the cap is refused instead of exhausting memory`() {
        val endless = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = len
        }
        try {
            BundledGeoTable.readFully(endless, expected = 1024, limit = 64 * 1024)
            fail("expected a refusal")
        } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.contains("larger than"))
        }
    }
}
