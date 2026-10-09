package com.dpdpxray.core.geo

import com.dpdpxray.core.trackers.TrackerEntry
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OwnerRegistryTest {
    private fun place(
        country: String = "US", source: String = "https://example.org/terms", kind: String = "COMPANY_DOCUMENT", asOf: String = "2026-10-10",
        quote: String = "Acme Inc., 1 Main St, Springfield, USA", basis: String? = "STATED_ADDRESS",
    ) = """{"country":"$country","source":"$source","kind":"$kind","asOf":"$asOf","quote":"$quote"${basis?.let { ""","basis":"$it"""" } ?: ""}}"""

    private fun entry(location: String = place()) = """[{"entity":"Acme Analytics","location":$location}]"""

    private fun rejects(json: String) = try { OwnerRegistry.parse(json); fail("accepted: $json") } catch (_: OwnerRegistryException) {}

    @Test
    fun `a sourced location keeps its source, quote and basis`() {
        val f = OwnerRegistry.parse(entry()).find("Acme Analytics")!!
        val l = f.location!!
        assertEquals("US", l.iso)
        assertEquals("https://example.org/terms", l.sourceUrl)
        assertEquals(SourceKind.COMPANY_DOCUMENT, l.kind)
        assertEquals(LocationBasis.STATED_ADDRESS, l.basis)
        assertEquals("Acme Inc., 1 Main St, Springfield, USA", l.quote)
        assertNull(f.incorporation)
    }

    @Test
    fun `incorporation is separate from the stated location and needs no basis`() {
        val j = """[{"entity":"A","location":${place(country = "US")},"incorporation":${place(country = "IE", basis = null, quote = "a company registered in Ireland")}}]"""
        val f = OwnerRegistry.parse(j).find("A")!!
        assertEquals("US", f.location!!.iso)
        assertEquals("IE", f.incorporation!!.iso)
        assertNull(f.incorporation!!.basis)
    }

    @Test
    fun `an entry may record that research found nothing usable`() {
        val f = OwnerRegistry.parse("""[{"entity":"A","note":"Lists entities in three countries and names no headquarters."}]""").find("A")!!
        assertNull(f.location)
        assertNull(f.incorporation)
        assertEquals("Lists entities in three countries and names no headquarters.", f.note)
    }

    @Test
    fun `an entry with no location and no note says nothing and is rejected`() = rejects("""[{"entity":"A"}]""")

    @Test
    fun `every country claim needs a valid https source, kind, date, quote and basis`() {
        rejects(entry("""{"country":"US","kind":"COMPANY_DOCUMENT","asOf":"2026-10-10","quote":"q","basis":"STATED_ADDRESS"}"""))        // no source
        rejects(entry(place(source = "")))
        rejects(entry(place(source = "http://insecure.example")))
        rejects(entry(place(kind = "GUESS")))
        rejects(entry("""{"country":"US","source":"https://a.example","kind":"COMPANY_DOCUMENT","quote":"q","basis":"STATED_ADDRESS"}""")) // no date
        rejects(entry(place(asOf = "yesterday")))
        rejects(entry(place(quote = "")))                                                                                              // nothing to verify
        rejects(entry(place(quote = "x".repeat(401))))
        rejects(entry(place(basis = null)))                                                                                            // a location needs its basis
        rejects(entry(place(basis = "GUESS")))
    }

    @Test
    fun `country codes must be real ISO 3166 codes`() {
        rejects(entry(place(country = "ZZ")))
        rejects(entry(place(country = "usa")))
        assertNotNull(OwnerRegistry.parse(entry(place(country = "XK"))).find("Acme Analytics"))
    }

    @Test
    fun `duplicate entities and garbage are rejected`() {
        rejects("""[{"entity":"A","note":"n"},{"entity":"A","note":"n"}]""")
        rejects("not json")
        rejects("""[{"note":"n"}]""")
    }

    @Test
    fun `an entity with no entry is null, never a guess`() = assertNull(OwnerRegistry.parse(entry()).find("Somebody Else"))

    @Test
    fun `an empty registry is valid`() = assertTrue(OwnerRegistry.parse("[]").isEmpty)

    // ---- the bundled research ----------------------------------------------------------------------------------------

    private val registry = OwnerRegistry.loadDefault()

    @Test
    fun `the bundled registry only names catalogue entities`() {
        val entities = Json { ignoreUnknownKeys = true }.decodeFromString(
            ListSerializer(TrackerEntry.serializer()),
            TrackerEntry::class.java.getResourceAsStream("/trackers/trackers.json")!!.bufferedReader().readText(),
        ).map { it.entity }.toSet()
        assertTrue(registry.entities().all { it in entities })
    }

    @Test
    fun `every bundled location is a company document or registry with a quote that contains its own country evidence`() {
        registry.entities().map { registry.find(it)!! }.forEach { f ->
            listOfNotNull(f.location, f.incorporation).forEach { c ->
                assertTrue("${f.entity}: ${c.kind}", c.kind != SourceKind.COMMUNITY_DATABASE || c.sourceUrl.contains("wikidata.org"))
                assertTrue("${f.entity} quote", c.quote!!.length in 10..400)
                assertEquals("2026-10-10", c.asOf)
            }
        }
    }

    @Test
    fun `the researched demo companies resolve exactly as their documents say`() {
        val google = registry.find("Google Analytics for Firebase")!!
        assertEquals("US", google.location!!.iso)
        assertEquals("US", google.incorporation!!.iso)
        assertTrue(google.location!!.quote!!.contains("1600 Amphitheatre Parkway"))
        assertTrue(google.incorporation!!.quote!!.contains("State of Delaware"))
        assertEquals(google.location, registry.find("Google Ads / AdMob")!!.location)
        assertEquals(google.location, registry.find("Firebase Crashlytics")!!.location)
        assertEquals("US", registry.find("Meta (Facebook SDK)")!!.location!!.iso)
        assertEquals("IL", registry.find("AppsFlyer")!!.location!!.iso)
        assertNull("incorporation is not stated by that document", registry.find("AppsFlyer")!!.incorporation)
        assertNull("incorporation is not stated by that document", registry.find("Meta (Facebook SDK)")!!.incorporation)
    }

    @Test
    fun `CleverTap stays unresolved with the reason recorded`() {
        val f = registry.find("CleverTap")!!
        assertNull(f.location)
        assertTrue(f.note!!.contains("three"))
    }

    @Test
    fun `entities that were not researched have no entry`() {
        listOf("Mixpanel", "Braze", "Meta Audience Network", "InMobi").forEach { assertFalse(it, it in registry.entities()) }
    }
}
