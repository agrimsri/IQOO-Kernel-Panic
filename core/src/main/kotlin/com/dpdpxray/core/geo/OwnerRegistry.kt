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

/** What a source says about the place it names. A stated address is not automatically the headquarters. */
enum class LocationBasis(val phrase: String) {
    HEADQUARTERS("Described as headquarters"),
    STATED_ADDRESS("The address the company gives for contracts or notices; not described as headquarters or country of incorporation"),
}

/** A country claim that always carries its citation and the words that support it. There is no way to build one without. */
data class SourcedCountry(
    val iso: String,
    val sourceUrl: String,
    val kind: SourceKind,
    val asOf: String,
    /** The source's own wording, kept so a reviewer can check the claim without trusting this file. */
    val quote: String,
    /** Set for a location; null for an incorporation. */
    val basis: LocationBasis?,
)

/**
 * What research established about one catalogue entity. [location] and [incorporation] are separate claims; either may
 * be absent. [note] records caveats or why nothing could be established.
 */
data class OwnerFacts(val entity: String, val location: SourcedCountry?, val incorporation: SourcedCountry?, val note: String?)

/**
 * Company facts for the vendors in TraceVeil's tracker catalogue, keyed by catalogue entity name. A country exists here
 * only with a cited https source, a source kind, a date, a basis and a quote; a bad entry fails the whole load. Stated
 * location, incorporation, server location, IP registration and applicable law are separate facts and never derived
 * from one another. Entities nobody researched have no entry, which the screen shows as "not established".
 */
class OwnerRegistry private constructor(private val byEntity: Map<String, OwnerFacts>) {
    val isEmpty: Boolean get() = byEntity.isEmpty()
    fun find(entity: String): OwnerFacts? = byEntity[entity]
    fun entities(): Set<String> = byEntity.keys

    @Serializable
    private class RawCountry(
        val country: String = "", val source: String = "", val kind: String = "", val asOf: String = "",
        val quote: String = "", val basis: String? = null,
    )

    @Serializable
    private class RawEntry(val entity: String = "", val location: RawCountry? = null, val incorporation: RawCountry? = null, val note: String? = null)

    companion object {
        val EMPTY = OwnerRegistry(emptyMap())
        private const val MAX_QUOTE = 400
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
                val note = r.note?.trim()?.takeIf { it.isNotEmpty() }
                if (r.location == null && r.incorporation == null && note == null) throw OwnerRegistryException("${r.entity}: an entry needs a location, an incorporation or a note")
                out[r.entity] = OwnerFacts(
                    r.entity,
                    r.location?.let { sourced(r.entity, "location", it, needsBasis = true) },
                    r.incorporation?.let { sourced(r.entity, "incorporation", it, needsBasis = false) },
                    note,
                )
            }
            return OwnerRegistry(out)
        }

        fun loadDefault(): OwnerRegistry =
            OwnerRegistry::class.java.getResourceAsStream("/geo/owners.json")?.bufferedReader(Charsets.UTF_8)?.use { parse(it.readText()) } ?: EMPTY

        private fun sourced(entity: String, field: String, c: RawCountry, needsBasis: Boolean): SourcedCountry {
            fun bad(why: String): Nothing = throw OwnerRegistryException("$entity $field: $why")
            if (!CountryNames.isIso(c.country)) bad("'${c.country}' is not an ISO 3166 country code")
            if (!c.source.startsWith("https://") || c.source.length < 12 || c.source.any { it.isWhitespace() }) bad("needs an https source URL")
            val kind = SourceKind.entries.firstOrNull { it.name == c.kind } ?: bad("unknown source kind '${c.kind}'")
            if (!date.matches(c.asOf)) bad("needs an asOf date (yyyy-mm-dd)")
            val quote = c.quote.trim()
            if (quote.isEmpty() || quote.length > MAX_QUOTE) bad("needs the source's own wording as a quote (1..$MAX_QUOTE characters)")
            val basis = if (needsBasis) LocationBasis.entries.firstOrNull { it.name == c.basis } ?: bad("needs a basis (HEADQUARTERS or STATED_ADDRESS)") else null
            return SourcedCountry(c.country, c.source, kind, c.asOf, quote, basis)
        }
    }
}
