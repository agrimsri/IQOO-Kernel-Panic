package com.dpdpxray.core.geo

import com.dpdpxray.core.model.AnswerStatus
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
    private val catalog = TrackerCatalog.loadDefault()
    private val now = 1_790_694_124L + 3 * 86_400L // three days after the database build used below
    private fun v4(a: Int, b: Int, c: Int, d: Int) = byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())
    private fun dbOf(build: Long = 1_790_694_124L, configure: (MmdbBuilder) -> Unit) =
        GeoSource.Loaded(IpCountryLookup(MmdbReader.open(MmdbBuilder(4).also(configure).build(buildEpoch = build))))

    private val loaded = dbOf {
        it.insert(v4(8, 8, 0, 0), 16, it.record(MmdbBuilder.country("US", "US")))
        it.insert(v4(103, 0, 0, 0), 8, it.record(MmdbBuilder.country("IN", "SG")))
    }

    private fun ev(t: Double, host: String, ips: List<String> = emptyList(), chain: List<String> = emptyList(), status: AnswerStatus? = null, rcode: Int? = null) =
        NetEvent(tNanos = (t * s).toLong(), hostname = host, answerIps = ips, cnameChain = chain, answerStatus = status, rcode = rcode)

    private fun sessionOf(vararg events: NetEvent) = AuditSession(
        id = "r", targetPackage = "com.leakyshop.demo", targetLabel = "LeakyShop", plan = AuditPlan.REJECT,
        consentEvents = listOf(ConsentEvent(2 * s, ConsentKind.SCREEN_APPEARED), ConsentEvent(8 * s, ConsentKind.REJECT)),
        netEvents = events.toList(),
    )

    private val session = sessionOf(
        ev(1.0, "graph.facebook.com", listOf("8.8.8.8"), status = AnswerStatus.COMPLETE, rcode = 0),
        ev(9.0, "launches.appsflyersdk.com", listOf("103.5.5.5"), chain = listOf("launches.appsflyersdk.com", "edge.appsflyer.example"), status = AnswerStatus.COMPLETE, rcode = 0),
        ev(9.5, "app-measurement.com"),
    )
    private val model = EndpointGeoAggregator.build(listOf(session), catalog, loaded)

    private val owners = OwnerRegistry.parse(
        """[{"entity":"AppsFlyer","location":{"country":"IL","basis":"STATED_ADDRESS","source":"https://example.org/appsflyer","kind":"COMPANY_DOCUMENT",
            "asOf":"2026-10-10","quote":"AppsFlyer Ltd., located at 14 Maskit St. Herzliya, Israel"},
            "incorporation":{"country":"IE","source":"https://example.org/reg","kind":"PUBLIC_REGISTRY","asOf":"2026-10-10","quote":"registered in Ireland"}},
           {"entity":"CleverTap","note":"Lists entities in three countries and names no headquarters."}]""",
    )

    private fun view(m: EndpointMapModel = model, mode: MapMode = MapMode.SOURCE_DNS, owners: OwnerRegistry = this.owners) = MapPresenter.view(m, owners, mode, nowEpochSeconds = now)

    private fun allText(v: MapView) = (listOf(v.heading, v.explanation, v.empty.orEmpty()) + v.footer +
        v.rows.flatMap { r -> listOf(r.title) + r.facts.flatMap { listOfNotNull(it.label, it.value, it.note) } }).joinToString("\n")

    // ---- modes --------------------------------------------------------------------------------------------------------

    @Test
    fun `there are exactly two modes with the agreed labels`() {
        assertEquals(listOf("Source country / DNS", "Owner / legal authority"), MapMode.entries.map { it.label })
    }

    @Test
    fun `switching mode changes heading and rows but keeps the same row shape`() {
        val a = view(mode = MapMode.SOURCE_DNS)
        val b = view(mode = MapMode.OWNER_LEGAL)
        assertEquals(MapMode.SOURCE_DNS, a.mode)
        assertEquals(MapMode.OWNER_LEGAL, b.mode)
        assertTrue(a.heading != b.heading)
        assertTrue(a.rows.map { it.title } != b.rows.map { it.title })
        assertEquals(a, view(mode = MapMode.SOURCE_DNS)) // pure: same input, same view
    }

    @Test
    fun `every fact label shown to the user is one of the four evidence classes`() {
        assertEquals(listOf("Observed", "Inferred", "Sourced", "Unknown"), Evidence.entries.map { it.label })
    }

    // ---- source country / DNS -----------------------------------------------------------------------------------------

    @Test
    fun `source mode labels hosts observed, the country inferred and missing answers unknown`() {
        val v = view()
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
        assertTrue(unknown.facts.first().value.contains("app-measurement.com"))
        assertEquals(Evidence.UNKNOWN, unknown.facts.first().evidence)
    }

    @Test
    fun `countries are described as inferred from returned IPs, never as where data went`() {
        val v = view()
        assertTrue(v.explanation, v.explanation.contains("inferred from the IP addresses"))
        val footer = v.footer.joinToString("\n")
        assertTrue(footer.contains("not where data went"))
        assertTrue(footer.contains("not proof of where a server is"))
    }

    @Test
    fun `ip country, registered country and owner location stay in separate facts`() {
        val owner = view(mode = MapMode.OWNER_LEGAL).rows.first { it.title == "AppsFlyer" }.facts.associateBy { it.label }
        assertEquals("India", owner.getValue("Where its lookups were answered").value)
        assertEquals("Israel", owner.getValue("Company location (as stated)").value)
        val source = view().rows.first { it.iso == "IN" }.facts.associateBy { it.label }
        assertEquals("Singapore", source.getValue("Address block registered in").value)
    }

    @Test
    fun `each kind of unusable reply gets its own wording, and only real outcomes are called observed`() {
        val reasons = UnknownReason.entries
        val texts = reasons.map { MapPresenter.unknownText(it) }
        assertEquals(reasons.size, texts.map { it.first }.toSet().size)           // distinct labels
        reasons.forEach { r ->
            val (label, why) = MapPresenter.unknownText(r)
            assertTrue("$r: $label", label.isNotBlank() && why.isNotBlank())
            assertEquals(r.toString(), r.observedOutcome, why.startsWith("Observed:"))
        }
    }

    @Test
    fun `failed and partial replies are listed with the resolver's actual outcome`() {
        val m = EndpointGeoAggregator.build(listOf(sessionOf(
            ev(9.0, "graph.facebook.com", status = AnswerStatus.NO_REPLY),
            ev(9.1, "app-measurement.com", status = AnswerStatus.COMPLETE, rcode = 3),
            ev(9.2, "launches.appsflyersdk.com", status = AnswerStatus.MALFORMED),
        )), catalog, loaded)
        val unknown = view(m).rows.single()
        assertEquals(Evidence.UNKNOWN, unknown.evidence)
        val replies = unknown.facts.first { it.label == "Resolver replies" }
        assertEquals(Evidence.OBSERVED, replies.evidence)
        assertTrue(replies.value, replies.value.contains("app-measurement.com: A: name does not exist (NXDOMAIN)"))
        assertTrue(replies.value, replies.value.contains("graph.facebook.com: A: no reply from any resolver"))
        assertTrue(replies.value, replies.value.contains("launches.appsflyersdk.com: A: reply could not be read"))
    }

    @Test
    fun `no failure is ever presented as a clean empty result`() {
        val banned = Regex("\\bno trackers\\b|\\bclean\\b|\\bnothing found\\b|\\bnone\\b", RegexOption.IGNORE_CASE)
        UnknownReason.entries.forEach { r ->
            val (label, why) = MapPresenter.unknownText(r)
            assertNull("$r", banned.find("$label $why"))
        }
    }

    @Test
    fun `no table installed is explained and nothing is shown as a country`() {
        val m = EndpointGeoAggregator.build(listOf(session), catalog, GeoSource.NotInstalled)
        val v = view(m)
        assertTrue(v.rows.none { it.iso != null })
        assertTrue(allText(v).contains("GeoLite2-Country.mmdb"))
        assertTrue(v.footer.none { it.contains("GeoLite Data created by MaxMind") })
    }

    @Test
    fun `an unreadable table is reported with its reason`() {
        val m = EndpointGeoAggregator.build(listOf(session), catalog, GeoSource.Unreadable("not a MaxMind database"))
        assertTrue(allText(view(m)).contains("not a MaxMind database"))
    }

    @Test
    fun `private addresses are explained as having no public location`() {
        val m = EndpointGeoAggregator.build(listOf(sessionOf(ev(9.0, "graph.facebook.com", listOf("10.0.0.2"), status = AnswerStatus.COMPLETE, rcode = 0))), catalog, loaded)
        val note = view(m).rows.single().facts.first().note!!
        assertTrue(note, note.contains("private or reserved"))
    }

    @Test
    fun `an unplaced registered country is shown as a registration, never as a location`() {
        val db = dbOf { it.insert(v4(1, 0, 0, 0), 8, it.record(MmdbBuilder.country(null, "AU"))) }
        val m = EndpointGeoAggregator.build(listOf(sessionOf(ev(9.0, "graph.facebook.com", listOf("1.1.1.1"), status = AnswerStatus.COMPLETE, rcode = 0))), catalog, db)
        val v = view(m)
        assertTrue(v.rows.none { it.iso != null })
        val note = v.rows.single().facts.first().note!!
        assertTrue(note, note.contains("registered in Australia") && note.contains("not a location"))
    }

    // ---- GeoLite attribution, version and update note -----------------------------------------------------------------

    @Test
    fun `attribution and the database version appear whenever the database is used`() {
        val f = view().footer.joinToString("\n")
        assertTrue(f.contains("This product includes GeoLite Data created by MaxMind, available from https://www.maxmind.com"))
        assertTrue(f.contains("database built 2026-09-29"))
    }

    @Test
    fun `a fresh database gets no age warning and an old one does`() {
        assertTrue(view().footer.none { it.contains("days ago") })
        val old = MapPresenter.view(model, owners, MapMode.SOURCE_DNS, nowEpochSeconds = 1_790_694_124L + 60 * 86_400L).footer.joinToString("\n")
        assertTrue(old, old.contains("built 60 days ago"))
        assertTrue(old, old.contains("GeoLite EULA asks users to move to the updated version"))
    }

    // ---- owner / legal authority --------------------------------------------------------------------------------------

    @Test
    fun `owner mode shows the same four separate facts in the same order`() {
        val expected = listOf("Where its lookups were answered", "Domain registration", "Company location (as stated)", "Legal context")
        val rows = view(mode = MapMode.OWNER_LEGAL).rows
        assertTrue(rows.size >= 3)
        rows.filter { it.title != "Owner not identified" }.forEach { assertEquals(it.title, expected, it.facts.map { f -> f.label }) }
    }

    @Test
    fun `a stated location is sourced with its basis, quote and incorporation kept apart`() {
        val fact = view(mode = MapMode.OWNER_LEGAL).rows.first { it.title == "AppsFlyer" }.facts.first { it.label == "Company location (as stated)" }
        assertEquals(Evidence.SOURCED, fact.evidence)
        assertEquals("Israel", fact.value)
        val note = fact.note!!
        assertTrue(note, note.contains("https://example.org/appsflyer"))
        assertTrue(note, note.contains("AppsFlyer Ltd., located at 14 Maskit St. Herzliya, Israel"))
        assertTrue(note, note.contains("not described as headquarters"))
        assertTrue(note, note.contains("Incorporated in Ireland"))
    }

    @Test
    fun `a location the source calls headquarters says so`() {
        val hq = OwnerRegistry.parse(
            """[{"entity":"AppsFlyer","location":{"country":"IL","basis":"HEADQUARTERS","source":"https://example.org/x","kind":"PUBLIC_REGISTRY","asOf":"2026-10-10","quote":"principal executive offices: Herzliya"}}]""",
        )
        val note = view(mode = MapMode.OWNER_LEGAL, owners = hq).rows.first { it.title == "AppsFlyer" }.facts.first { it.label == "Company location (as stated)" }.note!!
        assertTrue(note, note.contains("Described as headquarters"))
    }

    @Test
    fun `unknown owners stay unknown, with the research note when there is one`() {
        val rows = view(mode = MapMode.OWNER_LEGAL).rows
        val meta = rows.first { it.title == "Meta (Facebook SDK)" }.facts.first { it.label == "Company location (as stated)" }
        assertEquals("Not established", meta.value)
        assertEquals(Evidence.UNKNOWN, meta.evidence)
        assertTrue(meta.note!!.contains("No cited source"))
        val clever = OwnerRegistry.parse("""[{"entity":"Meta (Facebook SDK)","note":"Lists entities in three countries and names no headquarters."}]""")
        val noted = view(mode = MapMode.OWNER_LEGAL, owners = clever).rows.first { it.title == "Meta (Facebook SDK)" }.facts.first { it.label == "Company location (as stated)" }
        assertEquals("Not established", noted.value)
        assertEquals(Evidence.UNKNOWN, noted.evidence)
        assertTrue(noted.note!!.contains("names no headquarters"))
    }

    @Test
    fun `hosts outside the catalogue are listed as owner not identified`() {
        val m = EndpointGeoAggregator.build(listOf(sessionOf(ev(9.0, "api.leakyshop.example", listOf("8.8.8.8"), status = AnswerStatus.COMPLETE, rcode = 0))), catalog, loaded, trackersOnly = false)
        val row = view(m, MapMode.OWNER_LEGAL).rows.single()
        assertEquals("Owner not identified", row.title)
        assertEquals(Evidence.UNKNOWN, row.evidence)
    }

    @Test
    fun `domain registration is never invented`() {
        view(mode = MapMode.OWNER_LEGAL).rows.filter { it.title != "Owner not identified" }.forEach { r ->
            val f = r.facts.first { it.label == "Domain registration" }
            assertEquals("Not looked up", f.value)
            assertEquals(Evidence.UNKNOWN, f.evidence)
        }
    }

    // ---- legal context -------------------------------------------------------------------------------------------------

    @Test
    fun `section 3 is shown as context with its own words and an explicit non-verdict`() {
        val law = view(mode = MapMode.OWNER_LEGAL).rows.first().facts.first { it.label == "Legal context" }
        assertEquals(Evidence.SOURCED, law.evidence)
        assertTrue(law.value.contains("DPDP Act") && law.value.contains("section 3"))
        val note = law.note!!
        assertTrue(note.contains("within the territory of India"))
        assertTrue(note.contains("offering of goods or services to Data Principals within the territory of India"))
        assertTrue(note, note.contains("not a finding that it applies"))
        assertTrue(note, note.contains("India-specific"))
        assertTrue(note, note.contains("not assessed"))
    }

    @Test
    fun `another jurisdiction's context can be supplied without changing anything else`() {
        val eu = LegalContext(
            label = "Legal context", name = "Example Act (Exampleland), article 2",
            quotes = listOf("This Act applies to processing of personal data of persons in Exampleland."),
            caveat = "Shown for context only; not a finding that it applies.",
        )
        val v = MapPresenter.view(model, owners, MapMode.OWNER_LEGAL, legal = eu, nowEpochSeconds = now)
        val law = v.rows.first().facts.first { it.label == "Legal context" }
        assertTrue(law.value.contains("Example Act"))
        assertTrue(law.note!!.contains("persons in Exampleland"))
        assertFalse(allText(v).contains("DPDP"))
    }

    // ---- rendering sizes, empties, repeats ----------------------------------------------------------------------------

    @Test
    fun `empty audits give an explanation instead of empty or zero rows`() {
        val empty = EndpointGeoAggregator.build(emptyList(), catalog, loaded)
        MapMode.entries.forEach {
            val v = view(empty, it)
            assertTrue(v.rows.isEmpty())
            assertNotNull(v.empty)
        }
    }

    @Test
    fun `zero, one and twenty countries each produce exactly that many country rows with the same shape`() {
        val codes = listOf("US", "IN", "IE", "DE", "NL", "GB", "FR", "SG", "JP", "AU", "CA", "BR", "ZA", "SE", "CH", "IL", "KR", "ES", "IT", "NO")
        fun rowsFor(n: Int): List<MapRow> {
            val cs = codes.take(n)
            val db = dbOf { b -> cs.forEachIndexed { i, c -> b.insert(v4(20 + i, 0, 0, 0), 8, b.record(MmdbBuilder.country(c, c))) } }
            val events = cs.indices.map { i -> ev(9.0 + i / 100.0, "h$i.appsflyersdk.com", listOf("${20 + i}.1.1.1"), status = AnswerStatus.COMPLETE, rcode = 0) }
            return view(EndpointGeoAggregator.build(listOf(sessionOf(*events.toTypedArray())), catalog, db)).rows
        }
        assertEquals(0, rowsFor(0).size)
        assertEquals(1, rowsFor(1).size)
        val twenty = rowsFor(20)
        assertEquals(20, twenty.size)
        assertEquals(codes.toSet(), twenty.mapNotNull { it.iso }.toSet())
        assertTrue(twenty.all { it.evidence == Evidence.INFERRED && it.facts.isNotEmpty() })
        assertEquals(1, twenty.map { r -> r.facts.map { it.evidence }.toSet() - setOf(Evidence.INFERRED) }.toSet().size)
    }

    @Test
    fun `map highlights follow the mode and use only sourced locations`() {
        assertEquals(setOf("US", "IN"), MapPresenter.highlight(model, owners, MapMode.SOURCE_DNS))
        assertEquals(setOf("IL"), MapPresenter.highlight(model, owners, MapMode.OWNER_LEGAL))
        assertTrue(MapPresenter.highlight(model, OwnerRegistry.EMPTY, MapMode.OWNER_LEGAL).isEmpty())
    }

    @Test
    fun `identical long notes are marked as repeats after their first appearance`() {
        val long = "x".repeat(60)
        fun row(vararg notes: String?) = MapRow("r", Evidence.INFERRED, null, notes.map { Fact("f", "v", Evidence.UNKNOWN, it) })
        assertEquals(setOf(1 to 1, 2 to 0), MapPresenter.repeatedNotes(listOf(row(long, "short", null), row("short", long, "y".repeat(60)), row(long))))
    }

    @Test
    fun `owner view repeats never hide a location citation`() {
        val v = view(mode = MapMode.OWNER_LEGAL)
        val repeats = MapPresenter.repeatedNotes(v.rows)
        assertTrue(repeats.isNotEmpty())
        v.rows.forEachIndexed { r, row -> row.facts.forEachIndexed { f, fact ->
            if ((r to f) in repeats) assertFalse("${row.title}/${fact.label}", fact.note!!.contains("https://example.org/appsflyer"))
        } }
    }

    // ---- wording ---------------------------------------------------------------------------------------------------------

    @Test
    fun `no mode claims a transfer, violation, breach, theft or safety`() {
        val banned = Regex("transfer|violat|illegal|unlawful|breach|exfiltrat|stolen|theft|leak(ed)? (to|data)|proves?\\b|\\bsafe\\b|compliant", RegexOption.IGNORE_CASE)
        MapMode.entries.forEach { mode ->
            val text = allText(view(mode = mode))
            assertNull("$mode: ${banned.find(text)?.value}", banned.find(text))
        }
    }
}
