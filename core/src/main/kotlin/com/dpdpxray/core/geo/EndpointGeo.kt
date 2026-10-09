package com.dpdpxray.core.geo

import com.dpdpxray.core.audit.PhaseAssigner
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.Phase
import com.dpdpxray.core.trackers.TrackerCatalog

/** Where country lookups come from. The database file is never bundled: it is provisioned on the phone. */
sealed interface GeoSource {
    data object NotInstalled : GeoSource
    data class Unreadable(val reason: String) : GeoSource
    data class Loaded(val lookup: IpCountryLookup) : GeoSource
}

/** Why a host has no country. Always one of these, never a blank or a guess. */
enum class UnknownReason { NO_ADDRESS_RECORDED, ADDRESS_NOT_IN_TABLE, NO_TABLE }

/** One hostname across all sessions: DNS facts as observed, plus the country the table places its answers in. */
data class HostEndpoint(
    val host: String,
    val entity: String?,
    val phases: Set<Phase>,
    val cnameChain: List<String>,
    val disguisedVia: String?,
    val addresses: List<String>,
    /** Per placed address; addresses the table has no country for are absent. */
    val lookups: List<IpCountry>,
    val located: Set<String>,
    /** Registered countries of addresses the table gives no location for (often anycast); a registration, not a position. */
    val unplacedRegistered: Set<String>,
    val unknown: UnknownReason?,
    val uncertainWhy: String?,
)

data class CountryEndpoints(
    val iso: String,
    val hostsByPhase: Map<Phase, List<String>>,
    /** host → why its country is uncertain (CDN alias, answers in several countries). */
    val uncertainHosts: Map<String, String>,
    /** Registered countries of the address blocks placed here that differ from [iso]. */
    val registeredElsewhere: Set<String>,
) {
    /** True when every host placed here is uncertain, so the country as a whole should not be drawn as settled. */
    val allUncertain: Boolean get() = uncertainHosts.isNotEmpty() && uncertainHosts.keys.containsAll(hostsByPhase.values.flatten())
}

data class EndpointMapModel(
    val source: GeoSource,
    val hosts: List<HostEndpoint>,
    val countries: List<CountryEndpoints>,
    val unknown: Map<UnknownReason, List<String>>,
)

object EndpointGeoAggregator {
    /**
     * Widely documented CDN / anycast edge domains. A name that resolves through one of these may be answered from a
     * different country than the service it fronts, so its country is marked uncertain. A heuristic, not a catalogue.
     */
    private val CDN_SUFFIXES = listOf(
        "cloudfront.net", "akamaiedge.net", "akamai.net", "akamaitechnologies.com", "edgekey.net", "edgesuite.net",
        "fastly.net", "fastlylb.net", "cloudflare.net", "azureedge.net", "googleusercontent.com",
    )

    fun build(sessions: List<AuditSession>, catalog: TrackerCatalog, geo: GeoSource, trackersOnly: Boolean = true): EndpointMapModel {
        class Acc(val host: String) {
            val phases = linkedSetOf<Phase>()
            val addresses = linkedSetOf<String>()
            var chain: List<String> = emptyList()
            var via: String? = null
            var entity: String? = null
        }
        val acc = LinkedHashMap<String, Acc>()
        for (session in sessions) {
            for (e in PhaseAssigner.assign(session.netEvents, session.consentEvents)) {
                val host = e.hostname.lowercase().trimEnd('.')
                val info = catalog.classify(host, e.cnameChain)
                if (trackersOnly && !info.category.isTracking && e.disguisedVia == null) continue
                val a = acc.getOrPut(host) { Acc(host) }
                a.phases += e.phase
                a.addresses += e.answerIps
                if (a.chain.isEmpty()) a.chain = e.cnameChain
                a.via = a.via ?: e.disguisedVia ?: info.disguisedVia
                a.entity = a.entity ?: info.entity
            }
        }
        val hosts = acc.values.map { a -> host(a.host, a.entity, a.phases, a.chain, a.via, a.addresses.toList(), geo) }.sortedBy { it.host }
        return EndpointMapModel(geo, hosts, countries(hosts), unknown(hosts))
    }

    private fun host(
        name: String, entity: String?, phases: Set<Phase>, chain: List<String>, via: String?, addresses: List<String>, geo: GeoSource,
    ): HostEndpoint {
        val lookup = (geo as? GeoSource.Loaded)?.lookup
        val found = if (lookup == null) emptyList() else addresses.mapNotNull { lookup.lookup(it) }
        val lookups = found.filter { it.located != null }
        val unplaced = found.filter { it.located == null }.mapNotNull { it.registered }.toCollection(linkedSetOf())
        val located = lookups.mapNotNull { it.located }.toCollection(linkedSetOf())
        val unknown = when {
            addresses.isEmpty() -> UnknownReason.NO_ADDRESS_RECORDED
            lookup == null -> UnknownReason.NO_TABLE
            located.isEmpty() -> UnknownReason.ADDRESS_NOT_IN_TABLE
            else -> null
        }
        val why = buildList {
            chain.firstOrNull { c -> CDN_SUFFIXES.any { c == it || c.endsWith(".$it") } }?.let {
                add("The name resolves through a CDN ($it), so the answering country may differ from where the service runs")
            }
            if (located.size > 1) add("Its answers are placed in ${located.size} countries")
        }.joinToString(". ").ifEmpty { null }
        return HostEndpoint(name, entity, phases, chain, via, addresses, lookups, located, unplaced, unknown, why)
    }

    private fun countries(hosts: List<HostEndpoint>): List<CountryEndpoints> {
        val byIso = LinkedHashMap<String, MutableList<HostEndpoint>>()
        hosts.forEach { h -> h.located.forEach { byIso.getOrPut(it) { mutableListOf() } += h } }
        return byIso.map { (iso, hs) ->
            CountryEndpoints(
                iso = iso,
                hostsByPhase = Phase.entries.mapNotNull { p -> hs.filter { p in it.phases }.map { it.host }.takeIf { it.isNotEmpty() }?.let { p to it } }.toMap(),
                uncertainHosts = hs.mapNotNull { h -> h.uncertainWhy?.let { h.host to it } }.toMap(),
                registeredElsewhere = hs.flatMap { it.lookups }.filter { it.located == iso }.mapNotNull { it.registered }.filter { it != iso }.toSet(),
            )
        }.sortedWith(compareByDescending<CountryEndpoints> { it.hostsByPhase.values.flatten().distinct().size }.thenBy { it.iso })
    }

    private fun unknown(hosts: List<HostEndpoint>): Map<UnknownReason, List<String>> =
        UnknownReason.entries.mapNotNull { r -> hosts.filter { it.unknown == r }.map { it.host }.takeIf { it.isNotEmpty() }?.let { r to it } }.toMap()
}
