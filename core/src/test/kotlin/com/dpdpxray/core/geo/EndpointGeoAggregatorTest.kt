package com.dpdpxray.core.geo

import com.dpdpxray.core.model.AnswerStatus
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.Phase
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointGeoAggregatorTest {
    private val s = 1_000_000_000L
    private val catalog = TrackerCatalog.loadDefault()
    private fun v4(a: Int, b: Int, c: Int, d: Int) = byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())

    private val geo = GeoSource.Loaded(IpCountryLookup(MmdbReader.open(MmdbBuilder(6).also {
        it.insert(v4(8, 8, 0, 0), 16, it.record(MmdbBuilder.country("US", "US")))
        it.insert(v4(103, 0, 0, 0), 8, it.record(MmdbBuilder.country("IN", "SG")))
        it.insert(v4(34, 0, 0, 0), 8, it.record(MmdbBuilder.country("IE", "US")))
        it.insert(v4(1, 0, 0, 0), 8, it.record(MmdbBuilder.country(null, "AU")))
        it.insert(ByteArray(16).also { b -> b[0] = 0x2a; b[1] = 0x03; b[2] = 0x28; b[3] = 0x80.toByte() }, 32, it.record(MmdbBuilder.country("US", "IE")))
    }.build())))

    private fun ev(
        t: Double, host: String, ips: List<String> = emptyList(), chain: List<String> = emptyList(), via: String? = null,
        status: AnswerStatus? = null, rcode: Int? = null, qtype: Int = 1,
    ) = NetEvent(tNanos = (t * s).toLong(), hostname = host, queryType = qtype, answerIps = ips, cnameChain = chain, disguisedVia = via,
        answerStatus = status, rcode = rcode)

    private fun rejectSession(vararg events: NetEvent, plan: AuditPlan = AuditPlan.REJECT) = AuditSession(
        id = plan.name, targetPackage = "com.leakyshop.demo", targetLabel = "LeakyShop", plan = plan, netEvents = events.toList(),
        consentEvents = listOf(ConsentEvent(2 * s, ConsentKind.SCREEN_APPEARED), ConsentEvent(8 * s, ConsentKind.REJECT)),
    )

    private fun build(vararg events: NetEvent, source: GeoSource = geo, trackersOnly: Boolean = true) =
        EndpointGeoAggregator.build(listOf(rejectSession(*events)), catalog, source, trackersOnly)

    private val base = rejectSession(
        ev(1.0, "graph.facebook.com", listOf("8.8.8.8"), status = AnswerStatus.COMPLETE, rcode = 0),
        ev(9.0, "launches.appsflyersdk.com", listOf("103.5.5.5"), status = AnswerStatus.COMPLETE, rcode = 0),
        ev(9.5, "app-measurement.com"),
        ev(10.5, "an.facebook.com", listOf("9.9.9.9"), status = AnswerStatus.COMPLETE, rcode = 0),
        ev(11.0, "cdn.leakyshop.example", listOf("8.8.8.8"), status = AnswerStatus.COMPLETE, rcode = 0),
    )

    @Test
    fun `groups tracker hosts by located country and consent phase`() {
        val m = EndpointGeoAggregator.build(listOf(base), catalog, geo)
        assertEquals(mapOf(Phase.BEFORE_CONSENT to listOf("graph.facebook.com")), m.countries.single { it.iso == "US" }.hostsByPhase)
        assertEquals(mapOf(Phase.AFTER_REJECT to listOf("launches.appsflyersdk.com")), m.countries.single { it.iso == "IN" }.hostsByPhase)
    }

    @Test
    fun `registered country is kept apart from the located one`() {
        val m = EndpointGeoAggregator.build(listOf(base), catalog, geo)
        assertEquals(setOf("SG"), m.countries.single { it.iso == "IN" }.registeredElsewhere)
        assertTrue(m.countries.single { it.iso == "US" }.registeredElsewhere.isEmpty())
    }

    @Test
    fun `hosts without a usable answer are unknown with the reason, never given a country`() {
        val m = EndpointGeoAggregator.build(listOf(base), catalog, geo)
        assertEquals(listOf("app-measurement.com"), m.unknown[UnknownReason.NOT_RECORDED])
        assertEquals(listOf("an.facebook.com"), m.unknown[UnknownReason.ADDRESS_NOT_IN_TABLE])
        assertTrue(m.countries.none { c -> c.hostsByPhase.values.flatten().any { it == "app-measurement.com" || it == "an.facebook.com" } })
    }

    @Test
    fun `non-tracker hosts are left out by default and included on request`() {
        assertFalse(EndpointGeoAggregator.build(listOf(base), catalog, geo).hosts.any { it.host == "cdn.leakyshop.example" })
        assertTrue(EndpointGeoAggregator.build(listOf(base), catalog, geo, trackersOnly = false).hosts.any { it.host == "cdn.leakyshop.example" })
    }

    // ---- missing or unusable database -------------------------------------------------------------------------

    @Test
    fun `without a table every host with an address is unknown for that reason`() {
        val m = EndpointGeoAggregator.build(listOf(base), catalog, GeoSource.NotInstalled)
        assertTrue(m.countries.isEmpty())
        assertEquals(setOf("graph.facebook.com", "launches.appsflyersdk.com", "an.facebook.com"), m.unknown[UnknownReason.NO_TABLE]!!.toSet())
        assertEquals(listOf("app-measurement.com"), m.unknown[UnknownReason.NOT_RECORDED])
        assertEquals(GeoSource.NotInstalled, m.source)
    }

    @Test
    fun `an unreadable table behaves like a missing one and keeps its reason`() {
        val m = EndpointGeoAggregator.build(listOf(base), catalog, GeoSource.Unreadable("not a MaxMind database"))
        assertTrue(m.countries.isEmpty())
        assertEquals(3, m.unknown[UnknownReason.NO_TABLE]!!.size)
        assertEquals(GeoSource.Unreadable("not a MaxMind database"), m.source)
    }

    // ---- IPv4, IPv6 and special-purpose addresses ------------------------------------------------------------------

    @Test
    fun `IPv6 answers are placed like IPv4 ones`() {
        val m = build(ev(9.0, "graph.facebook.com", listOf("2a03:2880:f12f:83:face:b00c:0:25de"), qtype = 28, status = AnswerStatus.COMPLETE, rcode = 0))
        val us = m.countries.single()
        assertEquals("US", us.iso)
        assertEquals(setOf("IE"), us.registeredElsewhere)
    }

    @Test
    fun `an IPv4 and an IPv6 answer for one name are both used`() {
        val m = build(
            ev(9.0, "graph.facebook.com", listOf("8.8.8.8"), status = AnswerStatus.COMPLETE, rcode = 0),
            ev(9.1, "graph.facebook.com", listOf("2a03:2880:f12f:83:face:b00c:0:25de"), qtype = 28, status = AnswerStatus.COMPLETE, rcode = 0),
        )
        assertEquals(listOf("US"), m.countries.map { it.iso })
        assertEquals(2, m.hosts.single().addresses.size)
    }

    @Test
    fun `private, loopback and other reserved addresses are never placed and are named as such`() {
        listOf("10.1.2.3", "192.168.0.9", "172.16.5.5", "127.0.0.1", "0.0.0.0", "169.254.1.1", "100.64.0.7", "224.0.0.1", "::1", "fe80::1", "fd00::5", "192.0.2.10", "2001:db8::1")
            .forEach { ip ->
                val m = build(ev(9.0, "graph.facebook.com", listOf(ip), status = AnswerStatus.COMPLETE, rcode = 0))
                assertTrue("$ip placed", m.countries.isEmpty())
                assertEquals(ip, listOf("graph.facebook.com"), m.unknown[UnknownReason.PRIVATE_OR_RESERVED])
            }
    }

    @Test
    fun `a reserved address next to a public one is ignored for placement but still listed`() {
        val m = build(ev(9.0, "graph.facebook.com", listOf("0.0.0.0", "8.8.8.8"), status = AnswerStatus.COMPLETE, rcode = 0))
        assertEquals(listOf("US"), m.countries.map { it.iso })
        assertEquals(listOf("0.0.0.0", "8.8.8.8"), m.hosts.single().addresses)
    }

    // ---- DNS answer outcomes (A4 contract) --------------------------------------------------------------------------

    @Test
    fun `each unusable reply keeps its own reason`() {
        val cases = listOf(
            Triple(null as AnswerStatus?, null as Int?, UnknownReason.NOT_RECORDED),
            Triple(AnswerStatus.NO_REPLY, null, UnknownReason.NO_REPLY),
            Triple(AnswerStatus.MALFORMED, null, UnknownReason.UNREADABLE_REPLY),
            Triple(AnswerStatus.MISMATCHED, null, UnknownReason.UNREADABLE_REPLY),
            Triple(AnswerStatus.PARTIAL, null, UnknownReason.PARTIAL_NO_ADDRESS),
            Triple(AnswerStatus.COMPLETE, 3, UnknownReason.NO_SUCH_NAME),
            Triple(AnswerStatus.COMPLETE, 0, UnknownReason.NO_ADDRESS_RECORDS),
            Triple(AnswerStatus.COMPLETE, 2, UnknownReason.RESOLVER_ERROR),
        )
        cases.forEach { (status, rcode, reason) ->
            val m = build(ev(9.0, "graph.facebook.com", status = status, rcode = rcode))
            assertTrue("$status/$rcode", m.countries.isEmpty())
            assertEquals("$status/$rcode", listOf("graph.facebook.com"), m.unknown[reason])
        }
    }

    @Test
    fun `NXDOMAIN and NODATA are real outcomes, not missing evidence`() {
        assertTrue(UnknownReason.NO_SUCH_NAME.observedOutcome)
        assertTrue(UnknownReason.NO_ADDRESS_RECORDS.observedOutcome)
        listOf(UnknownReason.NOT_RECORDED, UnknownReason.NO_REPLY, UnknownReason.UNREADABLE_REPLY, UnknownReason.PARTIAL_NO_ADDRESS)
            .forEach { assertFalse(it.toString(), it.observedOutcome) }
    }

    @Test
    fun `a partial reply with addresses is placed but marked uncertain`() {
        val m = build(ev(9.0, "graph.facebook.com", listOf("8.8.8.8"), status = AnswerStatus.PARTIAL, rcode = 0))
        val us = m.countries.single()
        assertTrue(us.uncertainHosts.getValue("graph.facebook.com").contains("cut short"))
        assertTrue(us.allUncertain)
    }

    @Test
    fun `a failed lookup does not erase a successful one for the same name`() {
        val m = build(
            ev(9.0, "graph.facebook.com", listOf("8.8.8.8"), status = AnswerStatus.COMPLETE, rcode = 0),
            ev(9.1, "graph.facebook.com", qtype = 28, status = AnswerStatus.NO_REPLY),
        )
        assertEquals(listOf("US"), m.countries.map { it.iso })
        assertTrue(m.unknown.isEmpty())
        assertEquals("A: answered; AAAA: no reply from any resolver", m.hosts.single().answerSummary)
    }

    @Test
    fun `the reply summary names every query type and never reads a failure as an empty answer`() {
        val m = build(
            ev(9.0, "graph.facebook.com", status = AnswerStatus.COMPLETE, rcode = 0),
            ev(9.1, "graph.facebook.com", qtype = 28, status = AnswerStatus.MALFORMED),
        )
        assertEquals("A: no address records (NODATA); AAAA: reply could not be read", m.hosts.single().answerSummary)
        assertEquals(UnknownReason.NO_ADDRESS_RECORDS, m.hosts.single().unknown)
    }

    @Test
    fun `addresses from an older audit with no recorded status are still placed`() {
        val m = build(ev(9.0, "graph.facebook.com", listOf("8.8.8.8")))
        assertEquals(listOf("US"), m.countries.map { it.iso })
        assertEquals("A: answered", m.hosts.single().answerSummary)
    }

    // ---- aliases, CDN, several countries ----------------------------------------------------------------------------

    @Test
    fun `a CDN alias makes the country uncertain and says why`() {
        val m = build(ev(9.0, "metrics.shop.test", listOf("34.1.1.1"), chain = listOf("metrics.shop.test", "d111.cloudfront.net", "app-measurement.com"),
            via = "app-measurement.com", status = AnswerStatus.COMPLETE, rcode = 0))
        val ie = m.countries.single { it.iso == "IE" }
        assertTrue(ie.uncertainHosts.getValue("metrics.shop.test").contains("CDN", ignoreCase = true))
        assertEquals("app-measurement.com", m.hosts.single().disguisedVia)
    }

    @Test
    fun `an alias chain is carried through unchanged and decides the owner`() {
        val chain = listOf("metrics.shop.test", "edge.example.net", "app-measurement.com")
        val h = build(ev(9.0, "metrics.shop.test", listOf("8.8.8.8"), chain = chain, via = "app-measurement.com", status = AnswerStatus.COMPLETE, rcode = 0)).hosts.single()
        assertEquals(chain, h.cnameChain)
        assertEquals("Google Analytics for Firebase", h.entity)
    }

    @Test
    fun `answers in several countries put the host in each and mark it uncertain`() {
        val m = build(ev(9.0, "graph.facebook.com", listOf("8.8.8.8", "103.1.1.1"), status = AnswerStatus.COMPLETE, rcode = 0))
        assertEquals(setOf("US", "IN"), m.countries.map { it.iso }.toSet())
        assertTrue(m.countries.all { it.uncertainHosts.containsKey("graph.facebook.com") })
    }

    @Test
    fun `an anycast-style address keeps its registered country but gets no location`() {
        val m = build(ev(9.0, "graph.facebook.com", listOf("1.1.1.1"), status = AnswerStatus.COMPLETE, rcode = 0))
        assertTrue(m.countries.isEmpty())
        assertEquals(setOf("AU"), m.hosts.single().unplacedRegistered)
        assertEquals(listOf("graph.facebook.com"), m.unknown[UnknownReason.ADDRESS_NOT_IN_TABLE])
    }

    // ---- merging, ordering, safety -----------------------------------------------------------------------------------

    @Test
    fun `the same host in several sessions merges its phases and addresses`() {
        val a = rejectSession(ev(1.0, "graph.facebook.com", listOf("8.8.8.8")), plan = AuditPlan.REJECT)
        val b = rejectSession(ev(9.0, "graph.facebook.com", listOf("8.8.8.8")), plan = AuditPlan.ACCEPT)
        val h = EndpointGeoAggregator.build(listOf(a, b), catalog, geo).hosts.single()
        assertEquals(setOf(Phase.BEFORE_CONSENT, Phase.AFTER_REJECT), h.phases)
        assertEquals(listOf("8.8.8.8"), h.addresses)
    }

    @Test
    fun `sessions with no events give an empty model`() {
        val m = EndpointGeoAggregator.build(emptyList(), catalog, geo)
        assertTrue(m.hosts.isEmpty() && m.countries.isEmpty() && m.unknown.isEmpty())
    }

    @Test
    fun `observed DNS facts are carried through unchanged`() {
        val h = EndpointGeoAggregator.build(listOf(base), catalog, geo).hosts.first { it.host == "launches.appsflyersdk.com" }
        assertEquals(listOf("103.5.5.5"), h.addresses)
        assertEquals("AppsFlyer", h.entity)
        assertNull(h.disguisedVia)
    }

    @Test
    fun `a non-literal string in answerIps is ignored, not resolved`() {
        val m = build(ev(9.0, "graph.facebook.com", listOf("example.com"), status = AnswerStatus.COMPLETE, rcode = 0))
        assertEquals(listOf("graph.facebook.com"), m.unknown[UnknownReason.ADDRESS_NOT_IN_TABLE])
    }

    @Test
    fun `a country is wholly uncertain only when every host placed there is`() {
        val sure = rejectSession(ev(1.0, "graph.facebook.com", listOf("8.8.8.8")), ev(2.0, "app-measurement.com", listOf("8.8.4.4"),
            chain = listOf("app-measurement.com", "x.cloudfront.net")))
        assertFalse(EndpointGeoAggregator.build(listOf(sure), catalog, geo).countries.single { it.iso == "US" }.allUncertain)
        val cdn = rejectSession(ev(1.0, "app-measurement.com", listOf("8.8.4.4"), chain = listOf("app-measurement.com", "x.cloudfront.net")))
        assertTrue(EndpointGeoAggregator.build(listOf(cdn), catalog, geo).countries.single { it.iso == "US" }.allUncertain)
    }

    @Test
    fun `zero, one and twenty countries aggregate without losing or inventing any`() {
        fun countriesFor(n: Int): Int {
            val codes = isoCodes.take(n)
            val db = GeoSource.Loaded(IpCountryLookup(MmdbReader.open(MmdbBuilder(4).also { b ->
                codes.forEachIndexed { i, c -> b.insert(v4(20 + i, 0, 0, 0), 8, b.record(MmdbBuilder.country(c, c))) }
            }.build())))
            val events = codes.indices.map { i -> ev(9.0 + i / 100.0, "h$i.appsflyersdk.com", listOf("${20 + i}.1.1.1"), status = AnswerStatus.COMPLETE, rcode = 0) }
            val m = EndpointGeoAggregator.build(listOf(rejectSession(*events.toTypedArray())), catalog, db)
            assertEquals(codes.toSet(), m.countries.map { it.iso }.toSet())
            assertTrue(m.unknown.isEmpty())
            return m.countries.size
        }
        assertEquals(0, countriesFor(0))
        assertEquals(1, countriesFor(1))
        assertEquals(20, countriesFor(20))
    }

    private val isoCodes = listOf("US", "IN", "IE", "DE", "NL", "GB", "FR", "SG", "JP", "AU", "CA", "BR", "ZA", "SE", "CH", "IL", "KR", "ES", "IT", "NO")
}
