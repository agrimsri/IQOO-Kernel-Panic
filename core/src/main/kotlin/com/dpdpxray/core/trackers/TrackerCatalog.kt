package com.dpdpxray.core.trackers

import com.dpdpxray.core.model.Category
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TrackerEntry(
    val entity: String,
    val category: Category,
    val domains: List<String>,
    /** Links the entity to a FixCatalog template. */
    val sdkKey: String? = null,
    /** Per vendor documentation, the SDK reads a device/advertising identifier when it initialises. */
    val collectsAdIdAtInit: Boolean = false,
    /** Where a cited source places the company; see [CompanyCountry.basis]. null = not established. */
    val location: CompanyCountry? = null,
    /** Where a cited source says the company is incorporated. null = not established. */
    val incorporation: CompanyCountry? = null,
    /** Caveats, or why nothing could be established (e.g. several contracting entities). */
    val note: String? = null,
    /**
     * Company group that runs this service (e.g. "Meta", "Google"), matched against the audited app's publisher in
     * `app_owners.json` to recognise same-company traffic. null = not grouped; never guessed from a domain name.
     */
    val company: String? = null,
    /**
     * The vendor's own page that shows these domains in use by its tracking tag (read 11 October 2026 for the website
     * trackers added with the website check). null for older entries, which predate this field.
     */
    val domainSource: String? = null,
) {
    /** Headquarters country, only when a cited source describes the place as headquarters; never a registration. */
    val hqCountry: String? get() = location?.takeIf { it.basis == CompanyBasis.HEADQUARTERS }?.country

    /** The source for [hqCountry]; null exactly when [hqCountry] is. */
    val hqSource: String? get() = location?.takeIf { it.basis == CompanyBasis.HEADQUARTERS }?.source
}

data class TrackerInfo(
    val entity: String?,
    val category: Category,
    val sdkKey: String? = null,
    val collectsAdIdAtInit: Boolean = false,
    /** The observed alias that decided the classification, when the queried name itself did not. */
    val disguisedVia: String? = null,
    /** Company group of the matched entry; see [TrackerEntry.company]. */
    val company: String? = null,
)

/**
 * Suffix-matching hostname classifier. Longest matching domain wins; user first-party marks win for the name they
 * cover. An observed CNAME alias to a catalogue domain wins over the queried name's first-party mark (CNAME cloaking).
 * An alias to a content-delivery or company-service entry is ordinary infrastructure, not cloaking, so it never decides
 * a classification.
 */
class TrackerCatalog(
    private val entries: List<TrackerEntry>,
    private val firstParty: Set<String> = emptySet(),
) {
    private val byDomain: Map<String, TrackerEntry> =
        entries.flatMap { e -> e.domains.map { it.lowercase().trimEnd('.') to e } }.toMap()

    val size: Int get() = byDomain.size

    /** [cnameChain] is the verified alias chain from the reply, queried name first; empty means none observed. */
    fun classify(hostname: String, cnameChain: List<String> = emptyList()): TrackerInfo {
        val host = normalise(hostname)
        val hostFirstParty = isFirstParty(host)
        if (!hostFirstParty) entryForHost(host)?.let { return info(it) }
        // Checked before the first-party shortcut: a first-party name can be an alias for a tracker.
        cnameChain.asSequence().map(::normalise).filter { it != host && !isFirstParty(it) }
            .firstNotNullOfOrNull { alias -> entryForHost(alias)?.takeIf { it.category !in INFRASTRUCTURE }?.let { info(it, alias) } }
            ?.let { return it }
        return TrackerInfo(null, if (hostFirstParty) Category.FIRST_PARTY else Category.UNKNOWN)
    }

    private fun normalise(name: String) = name.lowercase().trimEnd('.')
    private fun isFirstParty(host: String) = suffixes(host).any { it in firstParty }
    private fun entryForHost(host: String) = suffixes(host).firstNotNullOfOrNull { byDomain[it] }
    private fun info(e: TrackerEntry, via: String? = null) = TrackerInfo(e.entity, e.category, e.sdkKey, e.collectsAdIdAtInit, via, e.company)

    fun sdkKeyFor(entity: String): String? = entries.firstOrNull { it.entity == entity }?.sdkKey

    /** The catalogue entry for an entity name as stored on events and findings, including its company facts. */
    fun entryFor(entity: String): TrackerEntry? = entries.firstOrNull { it.entity == entity }

    fun entries(): List<TrackerEntry> = entries

    fun withFirstParty(domains: Set<String>): TrackerCatalog =
        TrackerCatalog(entries, firstParty + domains.map { it.lowercase().trimEnd('.') })

    /** "a.b.c.com" → ["a.b.c.com", "b.c.com", "c.com", "com"] (longest first). */
    private fun suffixes(host: String): Sequence<String> = sequence {
        var rest = host
        while (rest.isNotEmpty()) {
            yield(rest)
            val dot = rest.indexOf('.')
            if (dot < 0) break
            rest = rest.substring(dot + 1)
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val INFRASTRUCTURE = setOf(Category.CONTENT_DELIVERY, Category.COMPANY_SERVICE)

        /** Fails with [IllegalArgumentException] if any company fact lacks its citation or basis. */
        fun fromJson(text: String): TrackerCatalog =
            TrackerCatalog(json.decodeFromString<List<TrackerEntry>>(text).onEach(CompanyFactRules::check))

        fun loadDefault(): TrackerCatalog {
            val text = TrackerCatalog::class.java.getResourceAsStream("/trackers/trackers.json")
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("trackers.json missing from resources")
            return fromJson(text)
        }
    }
}
