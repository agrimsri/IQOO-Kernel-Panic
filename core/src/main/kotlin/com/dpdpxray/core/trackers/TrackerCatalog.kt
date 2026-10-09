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
)

data class TrackerInfo(
    val entity: String?,
    val category: Category,
    val sdkKey: String? = null,
    val collectsAdIdAtInit: Boolean = false,
    /** The observed alias that decided the classification, when the queried name itself did not. */
    val disguisedVia: String? = null,
)

/**
 * Suffix-matching hostname classifier. Longest matching domain wins; user first-party marks win for the name they
 * cover. An observed CNAME alias to a catalogue domain wins over the queried name's first-party mark (CNAME cloaking).
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
        if (!hostFirstParty) entryFor(host)?.let { return info(it) }
        // Checked before the first-party shortcut: a first-party name can be an alias for a tracker.
        cnameChain.asSequence().map(::normalise).filter { it != host && !isFirstParty(it) }
            .firstNotNullOfOrNull { alias -> entryFor(alias)?.let { info(it, alias) } }
            ?.let { return it }
        return TrackerInfo(null, if (hostFirstParty) Category.FIRST_PARTY else Category.UNKNOWN)
    }

    private fun normalise(name: String) = name.lowercase().trimEnd('.')
    private fun isFirstParty(host: String) = suffixes(host).any { it in firstParty }
    private fun entryFor(host: String) = suffixes(host).firstNotNullOfOrNull { byDomain[it] }
    private fun info(e: TrackerEntry, via: String? = null) = TrackerInfo(e.entity, e.category, e.sdkKey, e.collectsAdIdAtInit, via)

    fun sdkKeyFor(entity: String): String? = entries.firstOrNull { it.entity == entity }?.sdkKey

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

        fun fromJson(text: String): TrackerCatalog = TrackerCatalog(json.decodeFromString<List<TrackerEntry>>(text))

        fun loadDefault(): TrackerCatalog {
            val text = TrackerCatalog::class.java.getResourceAsStream("/trackers/trackers.json")
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("trackers.json missing from resources")
            return fromJson(text)
        }
    }
}
