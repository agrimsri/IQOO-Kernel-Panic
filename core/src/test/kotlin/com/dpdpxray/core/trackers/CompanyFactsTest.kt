package com.dpdpxray.core.trackers

import com.dpdpxray.core.model.Category
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A5: company location facts on tracker entries, each one cited; headquarters kept apart from other addresses. */
class CompanyFactsTest {
    private val catalog = TrackerCatalog.loadDefault()
    private val rawText = javaClass.getResource("/trackers/trackers.json")!!.readText()

    @Test
    fun `every headquarters country is cited and the quote says headquarters`() {
        val withHq = catalog.entries().filter { it.hqCountry != null }
        assertTrue("some entities are researched", withHq.size >= 10)
        withHq.forEach { e ->
            val loc = e.location!!
            assertEquals(CompanyBasis.HEADQUARTERS, loc.basis)
            assertEquals(loc.source, e.hqSource)
            assertTrue(e.entity, e.hqSource!!.startsWith("https://"))
            assertTrue("${e.entity} quote must support 'headquarters': ${loc.quote}", loc.quote.contains("headquarter", ignoreCase = true))
        }
    }

    @Test
    fun `researched demo companies have the expected headquarters`() {
        val us = listOf(
            "Google Analytics for Firebase", "Google Analytics", "Firebase Crashlytics", "Google Ads / AdMob",
            "Meta (Facebook SDK)", "Meta Audience Network", "Meta", "AppLovin", "Braze", "Unity Ads", "Microsoft Clarity",
        )
        us.forEach { assertEquals(it, "US", catalog.entryFor(it)?.hqCountry) }
        assertTrue(catalog.entryFor("Meta")!!.hqSource!!.startsWith("https://www.sec.gov/"))
    }

    @Test
    fun `a stated or principal office address is never presented as headquarters`() {
        val appsFlyer = catalog.entryFor("AppsFlyer")!!
        assertEquals("IL", appsFlyer.location?.country)
        assertEquals(CompanyBasis.STATED_ADDRESS, appsFlyer.location?.basis)
        assertNull(appsFlyer.hqCountry)
        assertNull(appsFlyer.hqSource)
        assertTrue(appsFlyer.note!!.contains("three"))

        val amplitude = catalog.entryFor("Amplitude")!!
        assertEquals(CompanyBasis.STATED_ADDRESS, amplitude.location?.basis)
        assertNull(amplitude.hqCountry)
    }

    @Test
    fun `unresolved and unresearched companies stay null`() {
        val cleverTap = catalog.entryFor("CleverTap")!!
        assertNull(cleverTap.location)
        assertNull(cleverTap.hqCountry)
        assertNotNull("why nothing is recorded", cleverTap.note)

        val kochava = catalog.entryFor("Kochava")!!
        assertNull(kochava.location)
        assertNull(kochava.incorporation)
        assertNull(kochava.hqCountry)
        assertNull(catalog.entryFor("Not A Catalogue Entity"))
    }

    @Test
    fun `company facts do not change classification`() {
        val stripped = TrackerCatalog(catalog.entries().map { it.copy(location = null, incorporation = null, note = null) })
        catalog.entries().flatMap { it.domains }.forEach { d ->
            assertEquals(d, stripped.classify(d), catalog.classify(d))
            assertEquals(d, stripped.classify("x.$d"), catalog.classify("x.$d"))
        }
    }

    @Test
    fun `country objects use the shared owner-registry field names`() {
        val allowed = setOf("country", "source", "kind", "asOf", "quote", "basis")
        Json.parseToJsonElement(rawText).jsonArray.map { it.jsonObject }.forEach { entry ->
            listOf("location", "incorporation").mapNotNull { entry[it] as? JsonObject }.forEach { fact ->
                assertTrue("${entry["entity"]}: ${fact.keys}", allowed.containsAll(fact.keys))
            }
        }
    }

    private fun entry(fact: String, field: String = "location") =
        """[{"entity": "X Corp", "category": "ANALYTICS", "domains": ["x.example"], "$field": $fact}]"""

    private val good = """{"country": "US", "source": "https://example.com/10-k", "kind": "COMPANY_DOCUMENT", "asOf": "2026-10-10", "quote": "Our headquarters are located in X.", "basis": "HEADQUARTERS"}"""

    @Test
    fun `a valid entry loads`() {
        val e = TrackerCatalog.fromJson(entry(good)).entryFor("X Corp")!!
        assertEquals("US", e.hqCountry)
        assertEquals(Category.ANALYTICS, e.category)
    }

    @Test
    fun `bad facts fail the load and name the entity`() {
        val bad = listOf(
            good.replace("\"US\"", "\"USA\""),
            good.replace("\"US\"", "\"XX\""),
            good.replace("https://example.com/10-k", "http://example.com/10-k"),
            good.replace("https://example.com/10-k", "https://exa mple.com"),
            good.replace("2026-10-10", "10 Oct 2026"),
            good.replace("Our headquarters are located in X.", " "),
            good.replace("Our headquarters are located in X.", "q".repeat(401)),
            good.replace(""", "basis": "HEADQUARTERS"""", ""),
        )
        bad.forEach { fact ->
            val e = assertThrows(fact, IllegalArgumentException::class.java) { TrackerCatalog.fromJson(entry(fact)) }
            assertTrue(e.message, e.message!!.contains("X Corp"))
        }
        assertThrows(Exception::class.java) { TrackerCatalog.fromJson(entry(good.replace("COMPANY_DOCUMENT", "BLOG"))) }
    }

    @Test
    fun `an incorporation needs its citation but no basis`() {
        val inc = good.replace(""", "basis": "HEADQUARTERS"""", "")
        val e = TrackerCatalog.fromJson(entry(inc, "incorporation")).entryFor("X Corp")!!
        assertEquals("US", e.incorporation?.country)
        assertNull("an incorporation is not a headquarters", e.hqCountry)
        assertThrows(IllegalArgumentException::class.java) { TrackerCatalog.fromJson(entry(inc.replace("https://", "ftp://"), "incorporation")) }
    }

    @Test
    fun `the default catalogue still loads every entity once`() {
        val names = catalog.entries().map { it.entity }
        assertEquals(names.toSet().size, names.size)
        assertFalse(names.isEmpty())
    }
}
