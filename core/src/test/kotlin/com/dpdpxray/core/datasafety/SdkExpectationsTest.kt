package com.dpdpxray.core.datasafety

import com.dpdpxray.core.model.Category
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdkExpectationsTest {
    private val expectations = SdkExpectations.default
    private val catalog = TrackerCatalog.loadDefault()

    @Test
    fun `every curated expectation cites an https page, a date and a quote per data category`() {
        assertTrue(expectations.curated.size >= 6)
        expectations.curated.forEach { e ->
            assertTrue(e.entity, e.verified)
            assertTrue(e.entity, e.sourceUrl!!.startsWith("https://"))
            assertTrue(e.entity, e.sourceTitle!!.isNotBlank())
            assertTrue(e.entity, Regex("\\d{4}-\\d{2}-\\d{2}").matches(e.checked!!))
            assertTrue(e.entity, e.types.isNotEmpty())
            e.types.forEach { assertTrue("${e.entity} ${it.category}", it.quote.isNotBlank()) }
            if (e.sharesPerSource) assertTrue(e.entity, !e.sharesQuote.isNullOrBlank())
        }
    }

    @Test
    fun `curated names are tracker catalogue entities`() {
        expectations.curated.forEach { assertNotNull(it.entity, catalog.entryFor(it.entity)) }
    }

    @Test
    fun `the four SDKs of the brief are curated with their documented categories`() {
        fun cats(entity: String) = expectations.forEntity(entity, Category.UNKNOWN)!!.categories.toSet()
        assertEquals(
            setOf(DataCategory.DEVICE_OR_OTHER_IDS, DataCategory.APP_ACTIVITY, DataCategory.LOCATION),
            cats("Google Analytics for Firebase"),
        )
        assertEquals(setOf(DataCategory.APP_INFO_AND_PERFORMANCE, DataCategory.DEVICE_OR_OTHER_IDS), cats("Firebase Crashlytics"))
        assertEquals(setOf(DataCategory.DEVICE_OR_OTHER_IDS, DataCategory.APP_ACTIVITY), cats("AppsFlyer"))
        assertEquals(setOf(DataCategory.DEVICE_OR_OTHER_IDS, DataCategory.APP_ACTIVITY), cats("Meta (Facebook SDK)"))
        assertTrue(expectations.forEntity("Google Ads / AdMob", Category.ADVERTISING)!!.sharesPerSource)
    }

    @Test
    fun `an uncurated catalogue SDK gets a soft catalogue expectation by kind, marked unverified`() {
        val clever = expectations.forEntity("CleverTap", Category.ENGAGEMENT)!!
        assertFalse(clever.verified)
        assertNull(clever.sourceUrl)
        assertTrue(DataCategory.APP_ACTIVITY in clever.categories)
        assertEquals(listOf(DataCategory.APP_INFO_AND_PERFORMANCE), expectations.forEntity("Sentry", Category.CRASH_REPORTING)!!.categories)
    }

    @Test
    fun `hosts that are not third-party SDK kinds get no expectation`() {
        listOf(Category.FIRST_PARTY, Category.UNKNOWN, Category.CONTENT_DELIVERY, Category.COMPANY_SERVICE, Category.FIRST_PARTY_SAME_COMPANY)
            .forEach { assertNull(it.name, expectations.forEntity("Somebody", it)) }
    }
}
