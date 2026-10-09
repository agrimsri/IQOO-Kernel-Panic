package com.dpdpxray.core.geo

import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapPresenterTest {
    private val s = 1_000_000_000L
    private fun v4(a: Int, b: Int, c: Int, d: Int) = byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())
    private val loaded = GeoSource.Loaded(IpCountryLookup(MmdbReader.open(MmdbBuilder(4).also {
        it.insert(v4(8, 8, 0, 0), 16, it.record(MmdbBuilder.country("US", "US")))
        it.insert(v4(103, 0, 0, 0), 8, it.record(MmdbBuilder.country("IN", "SG")))
    }.build())))

    private fun ev(t: Double, host: String, ips: List<String> = emptyList(), chain: List<String> = emptyList()) =
        NetEvent(tNanos = (t * s).toLong(), hostname = host, answerIps = ips, cnameChain = chain)

    private val session = AuditSession(
        id = "r", targetPackage = "com.leakyshop.demo", targetLabel = "LeakyShop", plan = AuditPlan.REJECT,
        consentEvents = listOf(ConsentEvent(2 * s, ConsentKind.SCREEN_APPEARED), ConsentEvent(8 * s, ConsentKind.REJECT)),
        netEvents = listOf(
            ev(1.0, "graph.facebook.com", listOf("8.8.8.8")),
            ev(9.0, "launches.appsflyersdk.com", listOf("103.5.5.5"), chain = listOf("launches.appsflyersdk.com", "edge.appsflyer.example")),
            ev(9.5, "app-measurement.com"),
        ),
    )
    private val model = EndpointGeoAggregator.build(listOf(session), TrackerCatalog.loadDefault(), loaded)

    private val owners = OwnerRegistry.parse(
        """[{"entity":"AppsFlyer","headquarters":{"country":"IL","source":"https://example.org/appsflyer","kind":"COMPANY_DOCUMENT","asOf":"2026-10-10"}}]""",
    )

    private fun allText(v: MapView) = (listOf(v.heading, v.explanation, v.empty.orEmpty()) + v.footer +
        v.rows.flatMap { r -> listOf(r.title) + r.facts.flatMap { listOfNotNull(it.label, it.value, it.note) } }).joinToString("\n")

    @Test
    fun `there are exactly two modes with the agreed labels`() {
        assertEquals(listOf("Source country / DNS", "Owner / legal authority"), MapMode.entries.map { it.label })
    }

    @Test
    fun `switching mode changes heading and rows but keeps the same row shape`() {
        val a = MapPresenter.view(model, owners, MapMode.SOURCE_DNS)
        val b = MapPresenter.view(model, owners, MapMode.OWNER_LEGAL)
        assertEquals(MapMode.SOURCE_DNS, a.mode)
        assertEquals(MapMode.OWNER_LEGAL, b.mode)
        assertTrue(a.heading != b.heading)
        assertTrue(a.rows.map { it.title } != b.rows.map { it.title })
        assertTrue(a.rows.isNotEmpty() && b.rows.isNotEmpty())
        assertEquals(a, MapPresenter.view(model, owners, MapMode.SOURCE_DNS)) // pure: same input, same view
    }

    @Test
    fun `source mode labels hosts observed, the country inferred and missing answers unknown`() {
        val v = MapPresenter.view(model, owners, MapMode.SOURCE_DNS)
        val us = v.rows.first { it.title.contains("United States") }
        assertEquals(Evidence.INFERRED, us.evidence)
        assertEquals("US", us.iso)
        val hosts = us.facts.first { it.label == "Before consent" }
        assertEquals(Evidence.OBSERVED, hosts.evidence)
        assertTrue(hosts.value.contains("graph.facebook.com"))
        val india = v.rows.first { it.iso == "IN" }
        assertTrue(india.facts.any { it.label == "DNS answers" && it.evidence == Evidence.OBSERVED && it.value.contains("103.5.5.5") && it.value.contains("edge.appsflyer.example") })
        val registered = india.facts.first { it.label == "Address block registered in" }
        assertEquals("Singapore", registered.value)
        assertEquals(Evidence.INFERRED, registered.evidence)
        val unknown = v.rows.first { it.iso == null }
        assertEquals(Evidence.UNKNOWN, unknown.evidence)
        assertEquals("Country unknown", unknown.title)
        assertTrue(unknown.facts.single().value.contains("app-measurement.com"))
        assertEquals(Evidence.UNKNOWN, unknown.facts.single().evidence)
    }

    @Test
    fun `owner mode always shows the same four separate facts in the same order`() {
        val v = MapPresenter.view(model, owners, MapMode.OWNER_LEGAL)
        val expected = listOf("Where its lookups were answered", "Domain registration", "Company headquarters", "Legal authority that may apply")
        v.rows.filter { it.iso == null && it.title != "Owner not identified" }.forEach { assertEquals(it.title, expected, it.facts.map { f -> f.label }) }
        assertTrue(v.rows.size >= 3)
    }

    @Test
    fun `headquarters is sourced only when the registry has a cited source, otherwise not established`() {
        val v = MapPresenter.view(model, owners, MapMode.OWNER_LEGAL)
        val hq = v.rows.first { it.title == "AppsFlyer" }.facts.first { it.label == "Company headquarters" }
        assertEquals(Evidence.SOURCED, hq.evidence)
        assertEquals("Israel", hq.value)
        assertTrue(hq.note!!.contains("https://example.org/appsflyer"))
        val none = v.rows.first { it.title == "Meta (Facebook SDK)" }.facts.first { it.label == "Company headquarters" }
        assertEquals(Evidence.UNKNOWN, none.evidence)
        assertEquals("Not established", none.value)
    }

    @Test
    fun `server location, registration, headquarters and legal authority are never merged`() {
        val row = MapPresenter.view(model, owners, MapMode.OWNER_LEGAL).rows.first { it.title == "AppsFlyer" }
        val byLabel = row.facts.associateBy { it.label }
        assertEquals("India", byLabel.getValue("Where its lookups were answered").value)       // inferred from the IP
        assertEquals("Israel", byLabel.getValue("Company headquarters").value)                  // sourced
        assertEquals(Evidence.UNKNOWN, byLabel.getValue("Domain registration").evidence)        // no data source exists
        assertEquals("Not looked up", byLabel.getValue("Domain registration").value)
        val law = byLabel.getValue("Legal authority that may apply")
        assertEquals(Evidence.SOURCED, law.evidence)
        assertFalse(law.value.contains("Israel") || law.value.contains("India:"))
    }

    @Test
    fun `legal authority quotes the DPDP Act scope and says what is not assessed`() {
        val law = MapPresenter.view(model, owners, MapMode.OWNER_LEGAL).rows.first().facts.first { it.label == "Legal authority that may apply" }
        assertTrue(law.value.contains("Digital Personal Data Protection Act, 2023"))
        assertTrue(law.note!!.contains("within the territory of India"))
        assertTrue(law.note!!.contains("offering of goods or services to Data Principals within the territory of India"))
        assertTrue(law.note!!.contains("not assessed", ignoreCase = true))
    }

    @Test
    fun `no table installed is explained and nothing is shown as a country`() {
        val m = EndpointGeoAggregator.build(listOf(session), TrackerCatalog.loadDefault(), GeoSource.NotInstalled)
        val v = MapPresenter.view(m, owners, MapMode.SOURCE_DNS)
        assertTrue(v.rows.none { it.iso != null })
        assertTrue(allText(v).contains("GeoLite2-Country.mmdb"))
        assertTrue(v.footer.none { it.contains("GeoLite Data created by MaxMind") })
    }

    @Test
    fun `an unreadable table is reported with its reason`() {
        val m = EndpointGeoAggregator.build(listOf(session), TrackerCatalog.loadDefault(), GeoSource.Unreadable("not a MaxMind database"))
        assertTrue(allText(MapPresenter.view(m, owners, MapMode.SOURCE_DNS)).contains("not a MaxMind database"))
    }

    @Test
    fun `GeoLite2 attribution and the inferred caveat appear when the table is used`() {
        val f = MapPresenter.view(model, owners, MapMode.SOURCE_DNS).footer.joinToString("\n")
        assertTrue(f.contains("This product includes GeoLite Data created by MaxMind, available from https://www.maxmind.com"))
        assertTrue(f.contains("inferred", ignoreCase = true))
        assertTrue(f.contains("database built", ignoreCase = true))
    }

    @Test
    fun `empty audits give an explanation instead of empty or zero rows`() {
        val empty = EndpointGeoAggregator.build(emptyList(), TrackerCatalog.loadDefault(), loaded)
        MapMode.entries.forEach {
            val v = MapPresenter.view(empty, owners, it)
            assertTrue(v.rows.isEmpty())
            assertNotNull(v.empty)
        }
    }

    @Test
    fun `map highlights follow the mode and never include unsourced headquarters`() {
        assertEquals(setOf("US", "IN"), MapPresenter.highlight(model, owners, MapMode.SOURCE_DNS))
        assertEquals(setOf("IL"), MapPresenter.highlight(model, owners, MapMode.OWNER_LEGAL))
        assertTrue(MapPresenter.highlight(model, OwnerRegistry.EMPTY, MapMode.OWNER_LEGAL).isEmpty())
    }

    @Test
    fun `no mode claims a transfer, violation, breach or exfiltration`() {
        val banned = Regex("transfer|violat|illegal|unlawful|breach|exfiltrat|leak(ed)? (to|data)|proves?\\b|\\bsafe\\b|compliant", RegexOption.IGNORE_CASE)
        MapMode.entries.forEach { mode ->
            val text = allText(MapPresenter.view(model, owners, mode))
            assertNull("$mode: ${banned.find(text)?.value}", banned.find(text))
        }
    }

    @Test
    fun `every fact label shown to the user is one of the four evidence classes`() {
        assertEquals(listOf("Observed", "Inferred", "Sourced", "Unknown"), Evidence.entries.map { it.label })
    }

    @Test
    fun `an unplaced registered country is shown as a registration, never as a location`() {
        val anycast = session.copy(netEvents = listOf(ev(9.0, "graph.facebook.com", listOf("1.1.1.1"))))
        val db = GeoSource.Loaded(IpCountryLookup(MmdbReader.open(MmdbBuilder(4).also { it.insert(v4(1, 0, 0, 0), 8, it.record(MmdbBuilder.country(null, "AU"))) }.build())))
        val v = MapPresenter.view(EndpointGeoAggregator.build(listOf(anycast), TrackerCatalog.loadDefault(), db), owners, MapMode.SOURCE_DNS)
        assertTrue(v.rows.none { it.iso != null })
        val note = v.rows.single().facts.single().note!!
        assertTrue(note, note.contains("registered in Australia") && note.contains("not a location"))
    }

    @Test
    fun `identical long notes are marked as repeats after their first appearance`() {
        val long = "x".repeat(60)
        fun row(vararg notes: String?) = MapRow("r", Evidence.INFERRED, null, notes.map { Fact("f", "v", Evidence.UNKNOWN, it) })
        val repeats = MapPresenter.repeatedNotes(listOf(row(long, "short", null), row("short", long, "y".repeat(60)), row(long)))
        assertEquals(setOf(1 to 1, 2 to 0), repeats)
    }

    @Test
    fun `owner view repeats are exactly the standard unknown notes, never the headquarters citation`() {
        val v = MapPresenter.view(model, owners, MapMode.OWNER_LEGAL)
        val repeats = MapPresenter.repeatedNotes(v.rows)
        assertTrue(repeats.isNotEmpty())
        v.rows.forEachIndexed { r, row -> row.facts.forEachIndexed { f, fact ->
            if ((r to f) in repeats) assertFalse("${row.title}/${fact.label}", fact.note!!.contains("https://example.org/appsflyer"))
        } }
    }
}
