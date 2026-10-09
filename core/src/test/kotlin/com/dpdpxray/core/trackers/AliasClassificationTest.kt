package com.dpdpxray.core.trackers

import com.dpdpxray.core.model.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Literal CNAME chains as DnsAnswers produces them: queried name first, then each alias. */
class AliasClassificationTest {
    private val catalog = TrackerCatalog(
        listOf(
            TrackerEntry("Meta", Category.SOCIAL, listOf("facebook.com")),
            TrackerEntry("Google Analytics for Firebase", Category.ANALYTICS, listOf("app-measurement.com"), collectsAdIdAtInit = true),
            TrackerEntry("Firebase Crashlytics", Category.CRASH_REPORTING, listOf("crashlytics.com")),
        ),
    ).withFirstParty(setOf("shop.test"))

    @Test
    fun `an alias to a catalogue tracker wins over the first-party mark`() {
        val info = catalog.classify("metrics.shop.test", listOf("metrics.shop.test", "region1.app-measurement.com"))
        assertEquals("Google Analytics for Firebase", info.entity)
        assertEquals(Category.ANALYTICS, info.category)
        assertEquals("region1.app-measurement.com", info.disguisedVia)
        assertEquals(true, info.collectsAdIdAtInit)
    }

    @Test
    fun `an unknown host behind an intermediate CDN alias is attributed to the first catalogue alias`() {
        val info = catalog.classify("t.example.org", listOf("t.example.org", "edge.cdn.example", "x.app-measurement.com", "graph.facebook.com"))
        assertEquals("Google Analytics for Firebase", info.entity)
        assertEquals("x.app-measurement.com", info.disguisedVia)
    }

    @Test
    fun `a host that is itself a tracker keeps its direct classification`() {
        val info = catalog.classify("graph.facebook.com", listOf("graph.facebook.com", "star.app-measurement.com"))
        assertEquals("Meta", info.entity)
        assertNull(info.disguisedVia)
    }

    @Test
    fun `ordinary lookups are unchanged`() {
        for (host in listOf("api.shop.test", "graph.facebook.com", "unknown.example", "app-measurement.com")) {
            val plain = catalog.classify(host)
            assertEquals(plain, catalog.classify(host, emptyList()))
            assertEquals(plain, catalog.classify(host, listOf(host, "edge.cdn.example")))
            assertNull(plain.disguisedVia)
        }
        assertEquals(Category.FIRST_PARTY, catalog.classify("api.shop.test", listOf("api.shop.test", "lb.shop.test")).category)
    }

    @Test
    fun `an alias the user marked first-party is not treated as a tracker`() {
        val own = catalog.withFirstParty(setOf("facebook.com"))
        val info = own.classify("img.shop.test", listOf("img.shop.test", "static.facebook.com"))
        assertEquals(Category.FIRST_PARTY, info.category)
        assertNull(info.disguisedVia)
    }

    @Test
    fun `alias names are matched case-insensitively and without a trailing dot`() {
        val info = catalog.classify("Metrics.Shop.Test.", listOf("Metrics.Shop.Test", "X.Crashlytics.COM."))
        assertEquals("Firebase Crashlytics", info.entity)
        assertEquals("x.crashlytics.com", info.disguisedVia)
    }
}
