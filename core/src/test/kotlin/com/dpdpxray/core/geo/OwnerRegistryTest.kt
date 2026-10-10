package com.dpdpxray.core.geo

import com.dpdpxray.core.model.Category
import com.dpdpxray.core.trackers.CompanyBasis
import com.dpdpxray.core.trackers.CompanyCountry
import com.dpdpxray.core.trackers.CompanySourceKind
import com.dpdpxray.core.trackers.TrackerCatalog
import com.dpdpxray.core.trackers.TrackerEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner registry is a view over the tracker catalogue: one mapping, validated by the catalogue, never a second file. */
class OwnerRegistryTest {
    private fun country(
        iso: String = "US", basis: CompanyBasis? = CompanyBasis.HEADQUARTERS, kind: CompanySourceKind = CompanySourceKind.PUBLIC_REGISTRY,
        source: String = "https://example.org/filing", quote: String = "principal executive offices: Springfield",
    ) = CompanyCountry(iso, source, kind, "2026-10-10", quote, basis)

    private fun entry(name: String, location: CompanyCountry? = null, incorporation: CompanyCountry? = null, note: String? = null) =
        TrackerEntry(name, Category.ANALYTICS, listOf("${name.lowercase().replace(" ", "")}.example"), location = location, incorporation = incorporation, note = note)

    private fun registry(vararg e: TrackerEntry) = OwnerRegistry.fromCatalog(TrackerCatalog(e.toList()))

    @Test
    fun `a catalogue location maps one to one, keeping source, quote, kind and basis`() {
        val f = registry(entry("Acme", location = country())).find("Acme")!!
        val l = f.location!!
        assertEquals("US", l.iso)
        assertEquals("https://example.org/filing", l.sourceUrl)
        assertEquals(SourceKind.PUBLIC_REGISTRY, l.kind)
        assertEquals(LocationBasis.HEADQUARTERS, l.basis)
        assertEquals("principal executive offices: Springfield", l.quote)
        assertEquals("2026-10-10", l.asOf)
        assertNull(f.incorporation)
    }

    @Test
    fun `every source kind and basis maps by name, so none can drift`() {
        CompanySourceKind.entries.forEach { assertEquals(it.name, SourceKind.valueOf(it.name).name) }
        CompanyBasis.entries.forEach { assertEquals(it.name, LocationBasis.valueOf(it.name).name) }
        assertEquals(CompanySourceKind.entries.size, SourceKind.entries.size)
        assertEquals(CompanyBasis.entries.size, LocationBasis.entries.size)
        CompanySourceKind.entries.forEach { k ->
            assertEquals(k.name, registry(entry("A", location = country(kind = k))).find("A")!!.location!!.kind.name)
        }
    }

    @Test
    fun `incorporation is separate from location and carries no basis`() {
        val f = registry(entry("A", location = country("US"), incorporation = country("IE", basis = null, quote = "a company registered in Ireland"))).find("A")!!
        assertEquals("US", f.location!!.iso)
        assertEquals("IE", f.incorporation!!.iso)
        assertNull(f.incorporation!!.basis)
    }

    @Test
    fun `a stated address stays a stated address and is never promoted to headquarters`() {
        val f = registry(entry("A", location = country(basis = CompanyBasis.STATED_ADDRESS))).find("A")!!
        assertEquals(LocationBasis.STATED_ADDRESS, f.location!!.basis)
    }

    @Test
    fun `an entry that only records why research was inconclusive is kept`() {
        val f = registry(entry("A", note = "Lists entities in three countries and names no headquarters.")).find("A")!!
        assertNull(f.location)
        assertNull(f.incorporation)
        assertEquals("Lists entities in three countries and names no headquarters.", f.note)
    }

    @Test
    fun `entities with no facts at all have no entry, so they read as not established`() {
        val r = registry(entry("Researched", location = country()), entry("Untouched"))
        assertNull(r.find("Untouched"))
        assertEquals(setOf("Researched"), r.entities())
        assertNull(r.find("Somebody Else"))
    }

    @Test
    fun `an empty catalogue gives an empty registry`() {
        assertTrue(registry().isEmpty)
        assertTrue(OwnerRegistry.EMPTY.isEmpty)
    }

    // ---- the real catalogue ---------------------------------------------------------------------------------------------

    private val real = OwnerRegistry.loadDefault()
    private val catalog = TrackerCatalog.loadDefault()

    @Test
    fun `there is one ownership mapping and the second file is gone`() {
        assertNull(OwnerRegistry::class.java.getResource("/geo/owners.json"))
    }

    @Test
    fun `the registry is exactly the catalogue entities that carry facts`() {
        val withFacts = catalog.entries().filter { it.location != null || it.incorporation != null || it.note != null }.map { it.entity }.toSet()
        assertEquals(withFacts, real.entities())
        assertTrue(real.entities().isNotEmpty())
    }

    @Test
    fun `every catalogue fact reaches the registry unchanged`() {
        catalog.entries().forEach { e ->
            val f = real.find(e.entity)
            e.location?.let { c ->
                val l = f!!.location!!
                assertEquals(e.entity, c.country, l.iso)
                assertEquals(e.entity, c.source, l.sourceUrl)
                assertEquals(e.entity, c.quote, l.quote)
                assertEquals(e.entity, c.asOf, l.asOf)
                assertEquals(e.entity, c.kind.name, l.kind.name)
                assertEquals(e.entity, c.basis!!.name, l.basis!!.name)
            } ?: assertNull(e.entity, f?.location)
            e.incorporation?.let { c -> assertEquals(e.entity, c.country, f!!.incorporation!!.iso) } ?: assertNull(e.entity, f?.incorporation)
            assertEquals(e.entity, e.note, f?.note)
        }
    }

    @Test
    fun `headquarters appears only where the catalogue says the source calls it headquarters`() {
        catalog.entries().forEach { e ->
            val basis = real.find(e.entity)?.location?.basis
            assertEquals(e.entity, e.hqCountry != null, basis == LocationBasis.HEADQUARTERS)
            if (e.hqCountry != null) assertEquals(e.hqCountry, real.find(e.entity)!!.location!!.iso)
        }
    }

    @Test
    fun `the demo companies resolve as the cited A5 facts say`() {
        val google = real.find("Google Analytics for Firebase")!!
        assertEquals("US", google.location!!.iso)
        assertEquals(LocationBasis.HEADQUARTERS, google.location!!.basis)
        assertEquals("US", google.incorporation!!.iso)
        assertEquals(google.location, real.find("Google Ads / AdMob")!!.location)
        assertEquals(google.location, real.find("Firebase Crashlytics")!!.location)
        assertEquals(LocationBasis.HEADQUARTERS, real.find("Meta (Facebook SDK)")!!.location!!.basis)
        assertEquals("IL", real.find("AppsFlyer")!!.location!!.iso)
        assertEquals(LocationBasis.STATED_ADDRESS, real.find("AppsFlyer")!!.location!!.basis)
    }

    @Test
    fun `CleverTap stays unresolved with the reason recorded`() {
        val f = real.find("CleverTap")!!
        assertNull(f.location)
        assertNotNull(f.note)
    }

    @Test
    fun `entities that were not researched are not in the registry`() {
        val unresearched = catalog.entries().filter { it.location == null && it.incorporation == null && it.note == null }.map { it.entity }
        assertTrue(unresearched.isNotEmpty())
        unresearched.forEach { assertFalse(it, it in real.entities()) }
    }
}
