package com.dpdpxray.core.geo

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

    private val geo = GeoSource.Loaded(IpCountryLookup(MmdbReader.open(MmdbBuilder(4).also {
        it.insert(v4(8, 8, 0, 0), 16, it.record(MmdbBuilder.country("US", "US")))
        it.insert(v4(103, 0, 0, 0), 8, it.record(MmdbBuilder.country("IN", "SG")))
        it.insert(v4(34, 0, 0, 0), 8, it.record(MmdbBuilder.country("IE", "US")))
        it.insert(v4(1, 0, 0, 0), 8, it.record(MmdbBuilder.country(null, "AU")))
    }.build())))

    private fun ev(t: Double, host: String, ips: List<String> = emptyList(), chain: List<String> = emptyList(), via: String? = null) =
        NetEvent(tNanos = (t * s).toLong(), hostname = host, answerIps = ips, cnameChain = chain, disguisedVia = via)

    private fun rejectSession(vararg events: NetEvent, plan: AuditPlan = AuditPlan.REJECT) = AuditSession(
        id = plan.name, targetPackage = "com.leakyshop.demo", targetLabel = "LeakyShop", plan = plan, netEvents = events.toList(),
        consentEvents = listOf(ConsentEvent(2 * s, ConsentKind.SCREEN_APPEARED), ConsentEvent(8 * s, ConsentKind.REJECT)),
    )

    private val base = rejectSession(
        ev(1.0, "graph.facebook.com", listOf("8.8.8.8")),
        ev(9.0, "launches.appsflyersdk.com", listOf("103.5.5.5")),
        ev(9.5, "app-measurement.com"),
        ev(10.5, "an.facebook.com", listOf("9.9.9.9")),
        ev(11.0, "cdn.leakyshop.example", listOf("8.8.8.8")),
    )

    @Test
    fun `groups tracker hosts by located country and consent phase`() {
        val m = EndpointGeoAggregator.build(listOf(base), catalog, geo)
        val us = m.countries.single { it.iso == "US" }
        assertEquals(mapOf(Phase.BEFORE_CONSENT to listOf("graph.facebook.com")), us.hostsByPhase)
        val india = m.countries.single { it.iso == "IN" }
        assertEquals(mapOf(Phase.AFTER_REJECT to listOf("launches.appsflyersdk.com")), india.hostsByPhase)
    }

    @Test
    fun `registered country is kept apart from the located one`() {
        val india = EndpointGeoAggregator.build(listOf(base), catalog, geo).countries.single { it.iso == "IN" }
        assertEquals(setOf("SG"), india.registeredElsewhere)
        assertTrue(EndpointGeoAggregator.build(listOf(base), catalog, geo).countries.single { it.iso == "US" }.registeredElsewhere.isEmpty())
    }

    @Test
    fun `hosts without a usable answer are unknown with the reason, never given a country`() {
        val m = EndpointGeoAggregator.build(listOf(base), catalog, geo)
        assertEquals(listOf("app-measurement.com"), m.unknown[UnknownReason.NO_ADDRESS_RECORDED])
        assertEquals(listOf("an.facebook.com"), m.unknown[UnknownReason.ADDRESS_NOT_IN_TABLE])
        assertTrue(m.countries.none { c -> c.hostsByPhase.values.flatten().any { it == "app-measurement.com" || it == "an.facebook.com" } })
    }

    @Test
    fun `non-tracker hosts are left out by default and included on request`() {
        assertFalse(EndpointGeoAggregator.build(listOf(base), catalog, geo).hosts.any { it.host == "cdn.leakyshop.example" })
        assertTrue(EndpointGeoAggregator.build(listOf(base), catalog, geo, trackersOnly = false).hosts.any { it.host == "cdn.leakyshop.example" })
    }

    @Test
    fun `without a table every host with an address is unknown for that reason`() {
        val m = EndpointGeoAggregator.build(listOf(base), catalog, GeoSource.NotInstalled)
        assertTrue(m.countries.isEmpty())
        assertEquals(setOf("graph.facebook.com", "launches.appsflyersdk.com", "an.facebook.com"), m.unknown[UnknownReason.NO_TABLE]!!.toSet())
        assertEquals(listOf("app-measurement.com"), m.unknown[UnknownReason.NO_ADDRESS_RECORDED])
        assertEquals(GeoSource.NotInstalled, m.source)
    }

    @Test
    fun `a CDN alias makes the country uncertain and says why`() {
        val s1 = rejectSession(ev(9.0, "metrics.shop.test", listOf("34.1.1.1"), chain = listOf("metrics.shop.test", "d111.cloudfront.net", "app-measurement.com"), via = "app-measurement.com"))
        val m = EndpointGeoAggregator.build(listOf(s1), catalog, geo)
        val ie = m.countries.single { it.iso == "IE" }
        assertTrue(ie.uncertainHosts.getValue("metrics.shop.test").contains("CDN", ignoreCase = true))
        assertEquals("app-measurement.com", m.hosts.single().disguisedVia)
    }

    @Test
    fun `answers in several countries put the host in each and mark it uncertain`() {
        val s1 = rejectSession(ev(9.0, "graph.facebook.com", listOf("8.8.8.8", "103.1.1.1")))
        val m = EndpointGeoAggregator.build(listOf(s1), catalog, geo)
        assertEquals(setOf("US", "IN"), m.countries.map { it.iso }.toSet())
        assertTrue(m.countries.all { it.uncertainHosts.containsKey("graph.facebook.com") })
    }

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
        val s1 = rejectSession(ev(9.0, "graph.facebook.com", listOf("example.com")))
        val m = EndpointGeoAggregator.build(listOf(s1), catalog, geo)
        assertEquals(listOf("graph.facebook.com"), m.unknown[UnknownReason.ADDRESS_NOT_IN_TABLE])
    }

    @Test
    fun `an anycast-style address keeps its registered country but gets no location`() {
        val s1 = rejectSession(ev(9.0, "graph.facebook.com", listOf("1.1.1.1")))
        val m = EndpointGeoAggregator.build(listOf(s1), catalog, geo)
        assertTrue(m.countries.isEmpty())
        assertEquals(setOf("AU"), m.hosts.single().unplacedRegistered)
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
}
