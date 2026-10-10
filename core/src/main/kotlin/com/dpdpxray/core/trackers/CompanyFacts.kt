package com.dpdpxray.core.trackers

import kotlinx.serialization.Serializable
import java.util.Locale

/** How strong a citation is, strongest first. */
@Serializable
enum class CompanySourceKind { COMPANY_DOCUMENT, PUBLIC_REGISTRY, COMMUNITY_DATABASE }

/** What the source says about the place. A stated address (contracts, notices, principal office) is not a headquarters. */
@Serializable
enum class CompanyBasis { HEADQUARTERS, STATED_ADDRESS }

/**
 * One cited country claim about a tracker's company. Same JSON fields as the map's owner registry
 * (`core/src/main/resources/geo/owners.json`), so one file can feed both. Where the company is, where it is incorporated,
 * where its servers answer from and which law applies are separate facts; none is derived from another.
 */
@Serializable
data class CompanyCountry(
    /** ISO 3166-1 alpha-2. */
    val country: String,
    /** https URL of the page that was opened and quoted. */
    val source: String,
    val kind: CompanySourceKind,
    /** Date the source was read, yyyy-mm-dd. */
    val asOf: String,
    /** The source's own words, so a reviewer can check the claim without trusting this file. */
    val quote: String,
    /** Required for a location; absent for an incorporation. */
    val basis: CompanyBasis? = null,
)

internal object CompanyFactRules {
    private const val MAX_QUOTE = 400
    private val isoCountries: Set<String> = Locale.getISOCountries().toSet()
    private val date = Regex("\\d{4}-\\d{2}-\\d{2}")

    /** Throws [IllegalArgumentException] naming the entity when a claim lacks its citation or basis. */
    fun check(e: TrackerEntry) {
        require(e.company == null || e.company.isNotBlank()) { "${e.entity} company: must be a name or absent, not blank" }
        e.location?.let { check(e.entity, "location", it, needsBasis = true) }
        e.incorporation?.let { check(e.entity, "incorporation", it, needsBasis = false) }
    }

    private fun check(entity: String, field: String, c: CompanyCountry, needsBasis: Boolean) {
        fun ensure(ok: Boolean, why: String) = require(ok) { "$entity $field: $why" }
        ensure(c.country in isoCountries, "'${c.country}' is not an ISO 3166 alpha-2 country code")
        ensure(c.source.startsWith("https://") && c.source.length >= 12 && c.source.none { it.isWhitespace() }, "needs an https source URL")
        ensure(date.matches(c.asOf), "needs an asOf date (yyyy-mm-dd)")
        ensure(c.quote.isNotBlank() && c.quote.trim().length <= MAX_QUOTE, "needs the source's own words as a quote (1..$MAX_QUOTE characters)")
        if (needsBasis) ensure(c.basis != null, "needs a basis (HEADQUARTERS or STATED_ADDRESS)")
    }
}
