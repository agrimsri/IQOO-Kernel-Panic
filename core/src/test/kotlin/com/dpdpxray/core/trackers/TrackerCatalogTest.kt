package com.dpdpxray.core.trackers

import com.dpdpxray.core.model.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerCatalogTest {
    private val catalog = TrackerCatalog(
        listOf(
            TrackerEntry("Meta", Category.SOCIAL, listOf("facebook.com")),
            TrackerEntry("Meta Audience Network", Category.ADVERTISING, listOf("an.facebook.com")),
            TrackerEntry("Google Analytics for Firebase", Category.ANALYTICS, listOf("app-measurement.com"), sdkKey = "firebase-analytics"),
        ),
    )

    @Test
    fun `matches a subdomain by suffix`() {
        val info = catalog.classify("region1.app-measurement.com")
        assertEquals("Google Analytics for Firebase", info.entity)
        assertEquals(Category.ANALYTICS, info.category)
        assertEquals("firebase-analytics", info.sdkKey)
    }

    @Test
    fun `longest matching suffix wins`() {
        assertEquals(Category.ADVERTISING, catalog.classify("an.facebook.com").category)
        assertEquals(Category.SOCIAL, catalog.classify("graph.facebook.com").category)
    }

    @Test
    fun `does not match on a partial label`() {
        val info = catalog.classify("notfacebook.com")
        assertEquals(Category.UNKNOWN, info.category)
        assertNull(info.entity)
    }

    @Test
    fun `ignores case and a trailing dot`() {
        assertEquals("Meta", catalog.classify("GRAPH.Facebook.com.").entity)
    }

    @Test
    fun `first-party override beats the tracker list`() {
        val custom = catalog.withFirstParty(setOf("facebook.com"))
        assertEquals(Category.FIRST_PARTY, custom.classify("graph.facebook.com").category)
    }

    @Test
    fun `default catalog loads and knows the common SDK hosts`() {
        val default = TrackerCatalog.loadDefault()
        assertEquals(Category.ANALYTICS, default.classify("app-measurement.com").category)
        assertTrue(default.classify("graph.facebook.com").category.isTracking)
        assertEquals(Category.ATTRIBUTION, default.classify("launches.appsflyersdk.com").category)
        assertEquals(Category.CRASH_REPORTING, default.classify("firebase-settings.crashlytics.com").category)
        assertEquals(Category.ENGAGEMENT, default.classify("in1.clevertap-prod.com").category)
        assertTrue("catalog should not be tiny", default.size >= 30)
    }

    @Test
    fun `meta content delivery is not tracking but meta tracking endpoints still are`() {
        val default = TrackerCatalog.loadDefault()
        for (host in listOf("fbcdn.net", "scontent.xx.fbcdn.net", "static.xx.fbcdn.net", "video.fdel1-1.fna.fbcdn.net")) {
            val info = default.classify(host)
            assertEquals(host, Category.CONTENT_DELIVERY, info.category)
            assertFalse(host, info.category.isTracking)
            assertEquals("Meta", info.company)
            assertFalse(info.collectsAdIdAtInit)
        }
        assertTrue(default.classify("graph.facebook.com").category.isTracking)
        assertTrue(default.classify("connect.facebook.net").category.isTracking)
        assertTrue(default.classify("an.facebook.com").category.isTracking)
        // facebook.com itself is Meta's general service domain: a catalogue match, not a tracking endpoint.
        assertEquals(Category.COMPANY_SERVICE, default.classify("www.facebook.com").category)
        assertFalse(default.classify("www.facebook.com").category.isTracking)
        assertTrue(default.entryFor("Meta CDN")!!.note!!.contains("content"))
    }

    @Test
    fun `an alias to a content CDN is not treated as cloaking`() {
        val default = TrackerCatalog.loadDefault()
        val cdnOnly = default.classify("img.shop.example", listOf("img.shop.example", "scontent.xx.fbcdn.net"))
        assertEquals(Category.UNKNOWN, cdnOnly.category)
        assertNull(cdnOnly.disguisedVia)
        // A tracker further down the chain is still found.
        val behind = default.classify("t.shop.example", listOf("t.shop.example", "scontent.xx.fbcdn.net", "graph.facebook.com"))
        assertEquals("Meta (Facebook SDK)", behind.entity)
        assertEquals("graph.facebook.com", behind.disguisedVia)
    }

    @Test
    fun `catalogue entries carry their company group`() {
        val default = TrackerCatalog.loadDefault()
        listOf("Meta (Facebook SDK)", "Meta Audience Network", "Meta", "Meta CDN").forEach { assertEquals(it, "Meta", default.entryFor(it)?.company) }
        listOf("Google Analytics for Firebase", "Google Analytics", "Firebase Crashlytics", "Google Ads / AdMob").forEach {
            assertEquals(it, "Google", default.entryFor(it)?.company)
        }
        assertNull("unresearched groupings stay unknown", default.entryFor("Kochava")?.company)
        assertEquals("Meta", default.classify("graph.facebook.com").company)
        assertEquals("Google", default.classify("googleads.g.doubleclick.net").company)
        assertNull(default.classify("unknown.example").company)
    }

    @Test
    fun `a blank company fails the load`() {
        val bad = """[{"entity": "X", "category": "ANALYTICS", "domains": ["x.example"], "company": " "}]"""
        val e = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { TrackerCatalog.fromJson(bad) }
        assertTrue(e.message!!.contains("X"))
    }
}
