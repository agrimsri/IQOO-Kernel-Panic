package com.dpdpxray.app.ui.screens.map

import com.dpdpxray.core.geo.BundledGeoTable
import com.dpdpxray.core.geo.EndpointGeoAggregator
import com.dpdpxray.core.geo.GeoSource
import com.dpdpxray.core.geo.GeoTableChoice
import com.dpdpxray.core.geo.GeoTableInfo
import com.dpdpxray.core.geo.GeoTableKind
import com.dpdpxray.core.geo.IpCountry
import com.dpdpxray.core.geo.IpCountryLookup
import com.dpdpxray.core.geo.MapGuide
import com.dpdpxray.core.geo.MapMode
import com.dpdpxray.core.geo.MapPresenter
import com.dpdpxray.core.geo.MmdbReader
import com.dpdpxray.core.geo.OwnerRegistry
import com.dpdpxray.core.geo.TableOrigin
import com.dpdpxray.core.geo.WorldMap
import com.dpdpxray.core.model.AnswerStatus
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** The DB-IP Lite table actually shipped in the APK: the documented file, readable, and placing well-known addresses. */
class BundledGeoTableTest {
    private companion object {
        val asset: File = listOf("src/main/assets", "app/src/main/assets").map { File(it, GeoTableLoader.BUNDLED_SOURCE) }.first { it.isFile }
        val bytes: ByteArray = GZIPInputStream(asset.inputStream()).use { BundledGeoTable.readFully(it) }
        val reader: MmdbReader = MmdbReader.open(bytes)
        val lookup = IpCountryLookup(reader)
    }

    @Test
    fun `the shipped asset is the documented DB-IP release, unmodified`() {
        assertEquals(BundledGeoTable.BYTES, bytes.size)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(BundledGeoTable.SHA256, sha)
        assertEquals(BundledGeoTable.DATABASE_TYPE, reader.databaseType)
        assertEquals(BundledGeoTable.BUILD_EPOCH, reader.buildEpochSeconds)
        assertEquals(6, reader.ipVersion)
        // Compressed size, so the APK impact stated in docs/ATTRIBUTION.md stays true.
        assertTrue("asset is ${asset.length()} bytes", asset.length() in 4_000_000L..4_500_000L)
        // The asset merge gunzips *.gz, so the loader opens the name without .gz first (see GeoTableLoader).
        assertEquals(GeoTableLoader.BUNDLED_SOURCE.removeSuffix(".gz"), GeoTableLoader.BUNDLED_ASSET)
    }

    @Test
    fun `well-known addresses are placed, with no registered country in DB-IP records`() {
        assertEquals(IpCountry("US", null), lookup.lookup("8.8.8.8"))
        assertEquals(IpCountry("GB", null), lookup.lookup("81.2.69.160"))
        assertEquals(IpCountry("IN", null), lookup.lookup("49.44.0.1"))
        assertEquals(IpCountry("US", null), lookup.lookup("157.240.1.35"))
        assertEquals(IpCountry("IN", null), lookup.lookup("142.250.183.14"))
        // IPv6 works too. This anycast address is answered worldwide; the table lists one country for it.
        assertEquals(IpCountry("CA", null), lookup.lookup("2001:4860:4860::8888"))
    }

    @Test
    fun `the map's anycast example is what the bundled table really says`() {
        assertEquals(IpCountry("AU", null), lookup.lookup("1.1.1.1"))
        val detail = MapGuide.points(listOf("8.8.8.8", "1.1.1.1")).first { it.title.contains("approximate") }.detail
        assertTrue(detail, detail.contains("the bundled DB-IP table, for example, places 1.1.1.1"))
        assertTrue(detail, detail.contains("in Australia"))
    }

    @Test
    fun `the map names the bundled table as DB-IP Lite with its build date`() {
        val info = GeoTableInfo.of(GeoSource.Loaded(lookup, TableOrigin.BUNDLED))
        assertEquals(GeoTableKind.DBIP_LITE, info.kind)
        assertEquals("Countries from DB-IP Lite · bundled · built 2026-10-01", info.headline)
        assertTrue(info.credit!!.startsWith("IP Geolocation by DB-IP (https://db-ip.com)"))
    }

    private fun leakyShopReject(): AuditSession {
        val s = 1_000_000_000L
        return AuditSession(
            id = "r", targetPackage = "com.leakyshop.demo", targetLabel = "LeakyShop", plan = AuditPlan.REJECT,
            consentEvents = listOf(ConsentEvent(2 * s, ConsentKind.SCREEN_APPEARED), ConsentEvent(8 * s, ConsentKind.REJECT)),
            netEvents = listOf(
                NetEvent(tNanos = 1 * s, hostname = "graph.facebook.com", answerIps = listOf("157.240.1.35"), answerStatus = AnswerStatus.COMPLETE, rcode = 0),
                NetEvent(tNanos = 9 * s, hostname = "app-measurement.com", answerIps = listOf("8.8.8.8"), answerStatus = AnswerStatus.COMPLETE, rcode = 0),
            ),
        )
    }

    @Test
    fun `a LeakyShop-style audit gets countries with no file of the person's own`() {
        val s = 1_000_000_000L
        val session = AuditSession(
            id = "r", targetPackage = "com.leakyshop.demo", targetLabel = "LeakyShop", plan = AuditPlan.REJECT,
            consentEvents = listOf(ConsentEvent(2 * s, ConsentKind.SCREEN_APPEARED), ConsentEvent(8 * s, ConsentKind.REJECT)),
            netEvents = listOf(
                NetEvent(tNanos = 1 * s, hostname = "graph.facebook.com", answerIps = listOf("157.240.1.35"), answerStatus = AnswerStatus.COMPLETE, rcode = 0),
                NetEvent(tNanos = 9 * s, hostname = "app-measurement.com", answerIps = listOf("8.8.8.8"), answerStatus = AnswerStatus.COMPLETE, rcode = 0),
            ),
        )
        val choice = GeoTableChoice.choose(null) { GeoSource.Loaded(lookup, TableOrigin.BUNDLED) }
        val model = EndpointGeoAggregator.build(listOf(session), TrackerCatalog.loadDefault(), choice)
        assertEquals(listOf("US"), model.countries.map { it.iso })
        assertTrue(model.unknown.isEmpty())
    }

    @Test
    fun `the LeakyShop map highlights the same countries in both modes and the bundled outline draws them all`() {
        val model = EndpointGeoAggregator.build(listOf(leakyShopReject()), TrackerCatalog.loadDefault(), GeoSource.Loaded(lookup, TableOrigin.BUNDLED))
        val owners = OwnerRegistry.fromCatalog(TrackerCatalog.loadDefault())
        val source = MapPresenter.highlight(model, owners, MapMode.SOURCE_DNS)
        val owner = MapPresenter.highlight(model, owners, MapMode.OWNER_LEGAL)
        assertEquals(setOf("US"), source)
        assertEquals(setOf("US"), owner)
        val world = WorldMap.loadDefault()
        assertTrue(world.undrawn(source + owner).isEmpty())
        assertTrue(world.paths.getValue("US").isNotBlank())
    }
}
