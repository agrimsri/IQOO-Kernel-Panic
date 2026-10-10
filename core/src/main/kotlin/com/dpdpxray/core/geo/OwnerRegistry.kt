package com.dpdpxray.core.geo

import com.dpdpxray.core.trackers.CompanyBasis
import com.dpdpxray.core.trackers.CompanyCountry
import com.dpdpxray.core.trackers.CompanySourceKind
import com.dpdpxray.core.trackers.TrackerCatalog

/** How strong a citation is, strongest first. Names match the catalogue's [CompanySourceKind] one to one. */
enum class SourceKind(val label: String) {
    COMPANY_DOCUMENT("company document"),
    PUBLIC_REGISTRY("public registry"),
    COMMUNITY_DATABASE("community database, not verified"),
}

/**
 * What a source says about the place it names. Names match the catalogue's [CompanyBasis] one to one. A stated address
 * is not a headquarters, and neither is an incorporation.
 */
enum class LocationBasis(val phrase: String) {
    HEADQUARTERS("Headquarters"),
    STATED_ADDRESS("Stated address (the company gives it for contracts or notices; not described as headquarters)"),
}

/** A country claim that always carries its citation and the words that support it. */
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

private fun CompanyCountry.toSourced() = SourcedCountry(
    iso = country, sourceUrl = source, kind = SourceKind.valueOf(kind.name), asOf = asOf, quote = quote.trim(),
    basis = basis?.let { LocationBasis.valueOf(it.name) },
)

/**
 * Company facts for the vendors in TraceVeil's tracker catalogue, keyed by catalogue entity name. This is a view over
 * the catalogue, not a second data file: the catalogue already refuses a country without an https source, a date, the
 * source's own words and a basis, so there is exactly one ownership mapping. Entities nobody researched have no entry,
 * which the screen shows as "not established". Stated location, incorporation, server location, IP registration and
 * legal context are separate facts and never derived from one another.
 */
class OwnerRegistry private constructor(private val byEntity: Map<String, OwnerFacts>) {
    val isEmpty: Boolean get() = byEntity.isEmpty()
    fun find(entity: String): OwnerFacts? = byEntity[entity]
    fun entities(): Set<String> = byEntity.keys

    companion object {
        val EMPTY = OwnerRegistry(emptyMap())

        /** Entries for every catalogue entity that carries a location, an incorporation or a note. */
        fun fromCatalog(catalog: TrackerCatalog): OwnerRegistry = OwnerRegistry(
            catalog.entries()
                .filter { it.location != null || it.incorporation != null || it.note != null }
                .associate { e -> e.entity to OwnerFacts(e.entity, e.location?.toSourced(), e.incorporation?.toSourced(), e.note) },
        )

        fun loadDefault(): OwnerRegistry = fromCatalog(TrackerCatalog.loadDefault())

        /** Builds a registry from ready facts. For previews and tests; the app reads the catalogue. */
        fun of(facts: List<OwnerFacts>): OwnerRegistry {
            require(facts.map { it.entity }.toSet().size == facts.size) { "duplicate entity" }
            return OwnerRegistry(facts.associateBy { it.entity })
        }
    }
}
