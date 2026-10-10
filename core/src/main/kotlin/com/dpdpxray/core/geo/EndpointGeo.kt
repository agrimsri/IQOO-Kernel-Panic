package com.dpdpxray.core.geo

import com.dpdpxray.core.audit.PhaseAssigner
import com.dpdpxray.core.model.AnswerStatus
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.Phase
import com.dpdpxray.core.trackers.TrackerCatalog

/**
 * Where country lookups come from: the DB-IP Lite table bundled with the app, or the person's own GeoLite2 file on
 * the phone ([GeoTableChoice]). [Loaded.notes] carry anything the map must say about how the table was chosen.
 */
sealed interface GeoSource {
    data object NotInstalled : GeoSource
    data class Unreadable(val reason: String) : GeoSource
    data class Loaded(
        val lookup: IpCountryLookup,
        val origin: TableOrigin = TableOrigin.USER_FILE,
        val notes: List<String> = emptyList(),
    ) : GeoSource
}

/**
 * Why a host has no country. Always one of these, never a blank or a guess. Declared in the order they are preferred
 * when one host has several: a real resolver outcome beats a failure, and a failure beats "not recorded".
 *
 * [observedOutcome] is true when the reason is something the resolver (or the answer itself) actually said, rather than
 * the absence of evidence. NXDOMAIN and NODATA are findings; "no reply" and "not recorded" are gaps.
 */
enum class UnknownReason(val observedOutcome: Boolean) {
    /** A complete reply, no address, RCODE 3. */
    NO_SUCH_NAME(true),
    /** A complete reply, no address, RCODE 0. */
    NO_ADDRESS_RECORDS(true),
    /** A complete reply, no address, another RCODE. */
    RESOLVER_ERROR(true),
    /** The reply matched but was cut short, and no address was read from it. */
    PARTIAL_NO_ADDRESS(false),
    /** MALFORMED or MISMATCHED: a reply arrived but it does not answer this lookup. */
    UNREADABLE_REPLY(false),
    /** Every resolver failed. */
    NO_REPLY(false),
    /** The status was never recorded: an older audit, or the reply had not arrived when the session was saved. */
    NOT_RECORDED(false),
    /** The only addresses are private or reserved ones, which have no public location. */
    PRIVATE_OR_RESERVED(true),
    ADDRESS_NOT_IN_TABLE(false),
    NO_TABLE(false),
}

/** One resolver reply for one query type, reduced to what can be said about it. */
internal data class Outcome(val qtype: Int, val status: AnswerStatus?, val rcode: Int?, val hasAddresses: Boolean) {
    val typeName: String get() = when (qtype) { 1 -> "A"; 28 -> "AAAA"; else -> "type $qtype" }

    val description: String
        get() = when {
            hasAddresses && status == AnswerStatus.PARTIAL -> "partial reply, may be incomplete"
            hasAddresses -> "answered"
            else -> when (status) {
                null -> "not recorded"
                AnswerStatus.NO_REPLY -> "no reply from any resolver"
                AnswerStatus.MISMATCHED -> "reply did not answer this lookup"
                AnswerStatus.MALFORMED -> "reply could not be read"
                AnswerStatus.PARTIAL -> "partial reply, no address read"
                AnswerStatus.COMPLETE -> when (rcode) {
                    3 -> "name does not exist (NXDOMAIN)"
                    0 -> "no address records (NODATA)"
                    null -> "complete reply with no address, code not recorded"
                    else -> "resolver error (code $rcode)"
                }
            }
        }

    /** The reason this outcome gives for having no country, when it carries no address. */
    val reason: UnknownReason
        get() = when (status) {
            null -> UnknownReason.NOT_RECORDED
            AnswerStatus.NO_REPLY -> UnknownReason.NO_REPLY
            AnswerStatus.MISMATCHED, AnswerStatus.MALFORMED -> UnknownReason.UNREADABLE_REPLY
            AnswerStatus.PARTIAL -> UnknownReason.PARTIAL_NO_ADDRESS
            AnswerStatus.COMPLETE -> when (rcode) { 3 -> UnknownReason.NO_SUCH_NAME; 0 -> UnknownReason.NO_ADDRESS_RECORDS; else -> UnknownReason.RESOLVER_ERROR }
        }
}

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
    /** What each query type came back with, e.g. "A: answered; AAAA: no reply from any resolver". */
    val answerSummary: String,
    /** Only matched COMPLETE/PARTIAL replies associate a country with a particular consent phase. */
    val locatedByPhase: Map<Phase, Set<String>> = emptyMap(),
)

data class CountryEndpoints(
    val iso: String,
    val hostsByPhase: Map<Phase, List<String>>,
    /** host → why its country is uncertain (CDN alias, answers in several countries, a cut-short reply). */
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
            val addressesByPhase = linkedMapOf<Phase, MutableSet<String>>()
            val outcomes = ArrayList<Outcome>()
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
                if (e.hasUsableReply()) {
                    a.addresses += e.answerIps
                    a.addressesByPhase.getOrPut(e.phase) { linkedSetOf() } += e.answerIps
                }
                a.outcomes += outcome(e)
                if (a.chain.isEmpty()) a.chain = e.cnameChain
                a.via = a.via ?: e.disguisedVia ?: info.disguisedVia
                a.entity = a.entity ?: info.entity
            }
        }
        val hosts = acc.values.map { a -> host(a.host, a.entity, a.phases, a.chain, a.via, a.addresses.toList(), a.addressesByPhase, a.outcomes, geo) }.sortedBy { it.host }
        return EndpointMapModel(geo, hosts, countries(hosts), unknown(hosts))
    }

    private fun NetEvent.hasUsableReply() = answerStatus == AnswerStatus.COMPLETE || answerStatus == AnswerStatus.PARTIAL

    private fun outcome(e: NetEvent) = Outcome(e.queryType, e.answerStatus, e.rcode, e.hasUsableReply() && e.answerIps.isNotEmpty())

    private fun summary(outcomes: List<Outcome>): String =
        outcomes.groupBy { it.typeName }.entries.joinToString("; ") { (type, os) -> "$type: ${os.map { it.description }.distinct().joinToString(" / ")}" }

    private fun host(
        name: String, entity: String?, phases: Set<Phase>, chain: List<String>, via: String?, addresses: List<String>,
        addressesByPhase: Map<Phase, Set<String>>,
        outcomes: List<Outcome>, geo: GeoSource,
    ): HostEndpoint {
        val lookup = (geo as? GeoSource.Loaded)?.lookup
        val literals = addresses.mapNotNull { IpLiteral.parse(it) }
        val reservedOnly = literals.isNotEmpty() && literals.all { IpLiteral.isReserved(it) }
        val byAddress = if (lookup == null) emptyMap() else addresses.associateWith { lookup.lookup(it) }
        val found = byAddress.values.filterNotNull()
        val locatedByPhase = addressesByPhase.mapValues { (_, ips) -> ips.mapNotNull { byAddress[it]?.located }.toSet() }
        val lookups = found.filter { it.located != null }
        val unplaced = found.filter { it.located == null }.mapNotNull { it.registered }.toCollection(linkedSetOf())
        val located = lookups.mapNotNull { it.located }.toCollection(linkedSetOf())
        val unknown = when {
            located.isNotEmpty() -> null
            addresses.isEmpty() -> outcomes.map { it.reason }.minByOrNull { it.ordinal } ?: UnknownReason.NOT_RECORDED
            reservedOnly -> UnknownReason.PRIVATE_OR_RESERVED
            lookup == null -> UnknownReason.NO_TABLE
            else -> UnknownReason.ADDRESS_NOT_IN_TABLE
        }
        val why = buildList {
            chain.firstOrNull { c -> CDN_SUFFIXES.any { c == it || c.endsWith(".$it") } }?.let {
                add("The name resolves through a CDN ($it), so the answering country may differ from where the service runs")
            }
            if (located.size > 1) add("Its answers are placed in ${located.size} countries")
            if (outcomes.any { it.hasAddresses && it.status == AnswerStatus.PARTIAL }) add("The reply was cut short, so its address list may be incomplete")
        }.joinToString(". ").ifEmpty { null }
        return HostEndpoint(name, entity, phases, chain, via, addresses, lookups, located, unplaced, unknown, why, summary(outcomes), locatedByPhase)
    }

    private fun countries(hosts: List<HostEndpoint>): List<CountryEndpoints> {
        val byIso = LinkedHashMap<String, MutableList<HostEndpoint>>()
        hosts.forEach { h -> h.located.forEach { byIso.getOrPut(it) { mutableListOf() } += h } }
        return byIso.map { (iso, hs) ->
            CountryEndpoints(
                iso = iso,
                hostsByPhase = Phase.entries.mapNotNull { p -> hs.filter { iso in it.locatedByPhase[p].orEmpty() }.map { it.host }.takeIf { it.isNotEmpty() }?.let { p to it } }.toMap(),
                uncertainHosts = hs.mapNotNull { h -> h.uncertainWhy?.let { h.host to it } }.toMap(),
                registeredElsewhere = hs.flatMap { it.lookups }.filter { it.located == iso }.mapNotNull { it.registered }.filter { it != iso }.toSet(),
            )
        }.sortedWith(compareByDescending<CountryEndpoints> { it.hostsByPhase.values.flatten().distinct().size }.thenBy { it.iso })
    }

    private fun unknown(hosts: List<HostEndpoint>): Map<UnknownReason, List<String>> =
        UnknownReason.entries.mapNotNull { r -> hosts.filter { it.unknown == r }.map { it.host }.takeIf { it.isNotEmpty() }?.let { r to it } }.toMap()
}
