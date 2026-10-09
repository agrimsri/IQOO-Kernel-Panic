package com.dpdpxray.core.geo

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class OwnerRegistryException(message: String) : Exception(message)

/** How strong a citation is, strongest first. */
enum class SourceKind(val label: String) {
    COMPANY_DOCUMENT("company document"),
    PUBLIC_REGISTRY("public registry"),
    COMMUNITY_DATABASE("community database, not verified"),
}

/** A country claim that always carries its citation. There is no way to build one without a source. */
data class SourcedCountry(val iso: String, val sourceUrl: String, val kind: SourceKind, val asOf: String)

data class OwnerFacts(val entity: String, val headquarters: SourcedCountry?, val incorporation: SourcedCountry?)

/**
 * Company facts for the vendors in TraceVeil's tracker catalogue, keyed by catalogue entity name. A headquarters or
 * incorporation country exists here only with a cited https source, a source kind and a date; a bad entry fails the
 * whole load. Headquarters, incorporation, server location and applicable law are separate facts and never derived
 * from one another.
 */
class OwnerRegistry private constructor(private val byEntity: Map<String, OwnerFacts>) {
    val isEmpty: Boolean get() = byEntity.isEmpty()
    fun find(entity: String): OwnerFacts? = byEntity[entity]
    fun entities(): Set<String> = byEntity.keys

    @Serializable
    private class RawCountry(val country: String = "", val source: String = "", val kind: String = "", val asOf: String = "")

    @Serializable
    private class RawEntry(val entity: String = "", val headquarters: RawCountry? = null, val incorporation: RawCountry? = null)

    companion object {
        val EMPTY = OwnerRegistry(emptyMap())
        private val json = Json { ignoreUnknownKeys = true }
        private val date = Regex("\\d{4}-\\d{2}-\\d{2}")

        fun parse(text: String): OwnerRegistry {
            val raw = try { json.decodeFromString<List<RawEntry>>(text) } catch (e: SerializationException) {
                throw OwnerRegistryException("unreadable owner registry: ${e.message?.take(120)}")
            } catch (e: IllegalArgumentException) {
                throw OwnerRegistryException("unreadable owner registry: ${e.message?.take(120)}")
            }
            val out = LinkedHashMap<String, OwnerFacts>()
            for (r in raw) {
                if (r.entity.isBlank()) throw OwnerRegistryException("entry without an entity name")
                if (r.entity in out) throw OwnerRegistryException("duplicate entity ${r.entity}")
                out[r.entity] = OwnerFacts(r.entity, r.headquarters?.let { sourced(r.entity, "headquarters", it) }, r.incorporation?.let { sourced(r.entity, "incorporation", it) })
            }
            return OwnerRegistry(out)
        }

        fun loadDefault(): OwnerRegistry =
            OwnerRegistry::class.java.getResourceAsStream("/geo/owners.json")?.bufferedReader(Charsets.UTF_8)?.use { parse(it.readText()) } ?: EMPTY

        private fun sourced(entity: String, field: String, c: RawCountry): SourcedCountry {
            fun bad(why: String): Nothing = throw OwnerRegistryException("$entity $field: $why")
            if (!CountryNames.isIso(c.country)) bad("'${c.country}' is not an ISO 3166 country code")
            if (!c.source.startsWith("https://") || c.source.length < 12 || c.source.any { it.isWhitespace() }) bad("needs an https source URL")
            val kind = SourceKind.entries.firstOrNull { it.name == c.kind } ?: bad("unknown source kind '${c.kind}'")
            if (!date.matches(c.asOf)) bad("needs an asOf date (yyyy-mm-dd)")
            return SourcedCountry(c.country, c.source, kind, c.asOf)
        }
    }
}
