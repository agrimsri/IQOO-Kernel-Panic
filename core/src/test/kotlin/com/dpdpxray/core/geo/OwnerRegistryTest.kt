package com.dpdpxray.core.geo

import com.dpdpxray.core.trackers.TrackerEntry
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OwnerRegistryTest {
    private fun entry(hq: String = """{"country":"US","source":"https://example.org/filing","kind":"COMPANY_DOCUMENT","asOf":"2026-10-10"}""") =
        """[{"entity":"Acme Analytics","headquarters":$hq}]"""

    private fun rejects(json: String) = try { OwnerRegistry.parse(json); fail("accepted: $json") } catch (_: OwnerRegistryException) {}

    @Test
    fun `a sourced headquarters is kept with its source`() {
        val f = OwnerRegistry.parse(entry()).find("Acme Analytics")!!
        assertEquals("US", f.headquarters!!.iso)
        assertEquals("https://example.org/filing", f.headquarters!!.sourceUrl)
        assertEquals(SourceKind.COMPANY_DOCUMENT, f.headquarters!!.kind)
        assertNull(f.incorporation)
    }

    @Test
    fun `incorporation is separate from headquarters`() {
        val j = """[{"entity":"A","headquarters":{"country":"US","source":"https://a.example/x","kind":"PUBLIC_REGISTRY","asOf":"2026-10-10"},
            "incorporation":{"country":"IE","source":"https://b.example/y","kind":"PUBLIC_REGISTRY","asOf":"2026-10-10"}}]"""
        val f = OwnerRegistry.parse(j).find("A")!!
        assertEquals("US", f.headquarters!!.iso)
        assertEquals("IE", f.incorporation!!.iso)
    }

    @Test
    fun `every country claim needs a valid https source, kind and date`() {
        rejects(entry("""{"country":"US","kind":"COMPANY_DOCUMENT","asOf":"2026-10-10"}"""))                       // no source
        rejects(entry("""{"country":"US","source":"","kind":"COMPANY_DOCUMENT","asOf":"2026-10-10"}"""))           // blank source
        rejects(entry("""{"country":"US","source":"http://insecure.example","kind":"COMPANY_DOCUMENT","asOf":"2026-10-10"}"""))
        rejects(entry("""{"country":"US","source":"https://a.example","kind":"GUESS","asOf":"2026-10-10"}"""))     // unknown kind
        rejects(entry("""{"country":"US","source":"https://a.example","kind":"COMPANY_DOCUMENT"}"""))             // no date
        rejects(entry("""{"country":"US","source":"https://a.example","kind":"COMPANY_DOCUMENT","asOf":"yesterday"}"""))
    }

    @Test
    fun `country codes must be real ISO 3166 codes`() {
        rejects(entry("""{"country":"ZZ","source":"https://a.example","kind":"COMPANY_DOCUMENT","asOf":"2026-10-10"}"""))
        rejects(entry("""{"country":"usa","source":"https://a.example","kind":"COMPANY_DOCUMENT","asOf":"2026-10-10"}"""))
    }

    @Test
    fun `duplicate entities and garbage are rejected`() {
        rejects("""[{"entity":"A"},{"entity":"A"}]""")
        rejects("not json")
        rejects("""[{"headquarters":null}]""")
    }

    @Test
    fun `an entity with no entry is null, never a guess`() = assertNull(OwnerRegistry.parse(entry()).find("Somebody Else"))

    @Test
    fun `an empty registry is valid`() = assertTrue(OwnerRegistry.parse("[]").isEmpty)

    @Test
    fun `the bundled registry is valid and only names catalogue entities`() {
        val registry = OwnerRegistry.loadDefault()
        val entities = Json { ignoreUnknownKeys = true }.decodeFromString(
            ListSerializer(TrackerEntry.serializer()),
            TrackerEntry::class.java.getResourceAsStream("/trackers/trackers.json")!!.bufferedReader().readText(),
        ).map { it.entity }.toSet()
        assertTrue(registry.entities().all { it in entities })
    }
}
