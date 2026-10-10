package com.dpdpxray.core.attribution

import com.dpdpxray.core.fixes.FixCatalog
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.Category
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.Finding
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.Phase
import com.dpdpxray.core.rules.SessionClassifier
import com.dpdpxray.core.trackers.AppOwners
import com.dpdpxray.core.trackers.TrackerCatalog
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * One host of a group as this audit saw it: how often it was looked up, and its earliest lookup (seconds after the
 * plan's launch, and where that lookup sat relative to the consent choice).
 */
@Serializable
data class HostSighting(
    val host: String,
    val lookups: Int,
    /** Seconds after the plan started; null when the plan's start was not recorded. */
    val firstSeenSeconds: Double?,
    val firstPlan: AuditPlan,
    /** One of [SdkAttribution.RELATIONS]: "before consent", "after Reject", … */
    val firstRelation: String,
    /** Every relation this host was seen in, in [SdkAttribution.RELATIONS] order. */
    val relations: List<String>,
    /** The catalogue domain reached through an observed CNAME alias, when that alias decided the match. */
    val viaAlias: String? = null,
    /** Package that issued the lookup when it was not the audited app (e.g. Google Play services). */
    val viaPackage: String? = null,
)

/**
 * One service from the tracker catalogue (an SDK or a company's endpoint), or the [SdkAttribution.UNKNOWN_GROUP] of
 * hosts the catalogue does not list. [checks] are the findings that name this service; [impact] is the sum of those
 * findings' score weights (inferred findings count half, as in the score) and only orders the list.
 */
@Serializable
data class SdkGroup(
    val name: String,
    /** Company group from the catalogue (e.g. "Meta"); null when the catalogue does not group the service. */
    val company: String? = null,
    val category: Category,
    /** false only for the unknown-hosts group. */
    val known: Boolean = true,
    /** A cited record names the audited app's publisher as this service's company (see AppOwners). */
    val sameCompanyAsApp: Boolean = false,
    /** OBSERVED when DNS lookups to its hosts were captured; INFERRED when only a documentation-based check names it. */
    val evidence: EvidenceType,
    /** Plain wording of the catalogue match, e.g. "This hostname belongs to Meta (Facebook SDK) in TraceVeil's catalogue." */
    val catalogueMatch: String,
    val hosts: List<HostSighting> = emptyList(),
    val checks: List<CheckId> = emptyList(),
    val impact: Double = 0.0,
    val sdkKey: String? = null,
    /** FixCatalog template ids that apply to [checks]; empty when nothing here needs a code change. */
    val fixIds: List<String> = emptyList(),
) {
    val lookups: Int get() = hosts.sumOf { it.lookups }
}

/**
 * "Which SDK caused this?": turns an audit's findings and sessions into one group per catalogue service, ordered by the
 * score weight of the findings that name it. Deterministic and offline: the same sessions, result and catalogue always
 * give the same groups. Lookups are classified exactly as the rules engine classifies them ([SessionClassifier]).
 *
 * Truth rules: a lookup is an attempt to contact a host, not proof that data was sent; a catalogue match names who the
 * catalogue says runs a host, nothing more; hosts the catalogue does not list stay unknown and are never attributed.
 */
class SdkAttribution(
    private val catalog: TrackerCatalog,
    private val fixes: FixCatalog,
    owners: AppOwners = AppOwners.default,
) {
    private val classifier = SessionClassifier(catalog, owners)

    private data class Seen(val session: AuditSession, val event: NetEvent, val seconds: Double?, val relation: String)

    fun groups(result: AuditResult, sessions: List<AuditSession>): List<SdkGroup> {
        val seen = sessions.flatMap { s ->
            val historyUnknown = s.appDataKept && !s.consentScreenSeen
            classifier.classify(s).map { e -> Seen(s, e, seconds(s, e), relation(e.phase, historyUnknown)) }
        }
        val linked = linkedChecks(result.findings, sessions)
        // The audited app's publisher, from a cited record only (AppOwners); null when not recorded, never guessed.
        val ownerCompany = sessions.firstNotNullOfOrNull { classifier.ownerOf(it)?.company }

        val byEntity = seen.filter { it.event.entity != null }.groupBy { it.event.entity!! }
        val named = (byEntity.keys + linked.keys).distinct()
            .map { entity -> known(entity, byEntity[entity].orEmpty(), linked[entity].orEmpty(), ownerCompany) }
        val unknownSeen = seen.filter { it.event.entity == null && it.event.category == Category.UNKNOWN }
        val unknown = if (unknownSeen.isEmpty()) null else SdkGroup(
            name = UNKNOWN_GROUP, company = null, category = Category.UNKNOWN, known = false,
            evidence = EvidenceType.OBSERVED, catalogueMatch = UNKNOWN_WORDING, hosts = sightings(unknownSeen),
        )
        return named.sortedWith(
            compareByDescending<SdkGroup> { it.impact }
                .thenByDescending { it.checks.size }
                .thenByDescending { it.lookups }
                .thenBy { it.hosts.mapNotNull { h -> h.firstSeenSeconds }.minOrNull() ?: Double.MAX_VALUE }
                .thenBy { it.name },
        ) + listOfNotNull(unknown)
    }

    private fun known(entity: String, seen: List<Seen>, findings: List<Finding>, ownerCompany: String?): SdkGroup {
        val entry = catalog.entryFor(entity)
        val checks = findings.map { it.checkId }.distinct().sortedBy { it.ordinal }
        val sdkKey = entry?.sdkKey
        val template = sdkKey?.let { fixes.template(it) }
        val hosts = sightings(seen)
        return SdkGroup(
            name = entity,
            company = entry?.company,
            category = entry?.category ?: seen.firstOrNull()?.event?.category ?: Category.UNKNOWN,
            sameCompanyAsApp = seen.any { it.event.category == Category.FIRST_PARTY_SAME_COMPANY } ||
                (ownerCompany != null && entry?.company == ownerCompany),
            evidence = if (hosts.isNotEmpty()) EvidenceType.OBSERVED else EvidenceType.INFERRED,
            catalogueMatch = catalogueWording(entity, entry?.company, hosts.size, checks),
            hosts = hosts,
            checks = checks,
            impact = findings.sumOf { weight(it) },
            sdkKey = sdkKey,
            fixIds = listOfNotNull(template?.takeIf { t -> checks.any { it in t.appliesTo } }?.id),
        )
    }

    /** Per host: lookup count, earliest sighting and every relation seen. Hosts in order of first sighting. */
    private fun sightings(seen: List<Seen>): List<HostSighting> = seen.groupBy { it.event.hostname.lowercase().trimEnd('.') }
        .map { (host, list) ->
            val first = list.minWith(compareBy<Seen> { it.seconds ?: Double.MAX_VALUE })
            HostSighting(
                host = host,
                lookups = list.size,
                firstSeenSeconds = first.seconds,
                firstPlan = first.session.plan,
                firstRelation = first.relation,
                relations = RELATIONS.filter { r -> list.any { it.relation == r } },
                viaAlias = list.firstNotNullOfOrNull { it.event.disguisedVia },
                viaPackage = list.firstNotNullOfOrNull { it.event.viaPackage },
            )
        }
        .sortedWith(compareBy<HostSighting> { it.firstSeenSeconds ?: Double.MAX_VALUE }.thenBy { it.host })

    /**
     * Findings that name each catalogue entity. Most findings list their entities; NC names the tracking services it saw
     * only in its text, so its entities are recomputed the way the rules engine finds them.
     */
    private fun linkedChecks(findings: List<Finding>, sessions: List<AuditSession>): Map<String, List<Finding>> {
        val pairs = findings.flatMap { f -> entitiesOf(f, sessions).map { it to f } }
        return pairs.groupBy({ it.first }, { it.second })
    }

    private fun entitiesOf(f: Finding, sessions: List<AuditSession>): List<String> = when (f.checkId) {
        CheckId.NC -> sessions.filterNot { it.appDataKept && !it.consentScreenSeen }
            .flatMap { s -> classifier.classify(s).filter { it.category.isTracking }.mapNotNull { it.entity } }.distinct()
        else -> f.entities.distinct()
    }

    private fun seconds(s: AuditSession, e: NetEvent): Double? = (e.tNanos - s.startedAtNanos).takeIf { it >= 0 }?.let { it / 1e9 }

    companion object {
        const val UNKNOWN_GROUP = "Unknown hosts"
        const val BEFORE_CONSENT = "before consent"
        const val NO_CONSENT_SEEN = "no consent screen seen"
        const val HISTORY_UNKNOWN = "consent history unknown"
        const val AFTER_REJECT = "after Reject"
        const val AFTER_ACCEPT = "after Accept"

        /** Display order of relations. */
        val RELATIONS = listOf(BEFORE_CONSENT, NO_CONSENT_SEEN, HISTORY_UNKNOWN, AFTER_REJECT, AFTER_ACCEPT)

        const val UNKNOWN_WORDING = "Not in TraceVeil's tracker catalogue. These may be the app's own servers or services the " +
            "catalogue does not list; TraceVeil does not guess who runs them."

        /** One-line reminder shown with every group list. */
        const val LOOKUP_CAVEAT = "A lookup is an attempt to contact a host, not proof that data was sent. Groups come from " +
            "TraceVeil's tracker catalogue, not from inspecting the app's traffic."

        /** Same weights as the score: severity weight, half for inferred findings. */
        fun weight(f: Finding): Double = f.severity.weight * if (f.evidenceType == EvidenceType.INFERRED) 0.5 else 1.0

        private fun relation(phase: Phase, historyUnknown: Boolean): String = when {
            // Kept data and no consent screen: earlier consent cannot be checked (RulesEngine U1), so never "before consent".
            historyUnknown -> HISTORY_UNKNOWN
            phase == Phase.BEFORE_CONSENT -> BEFORE_CONSENT
            phase == Phase.AFTER_REJECT -> AFTER_REJECT
            phase == Phase.AFTER_ACCEPT -> AFTER_ACCEPT
            else -> NO_CONSENT_SEEN
        }

        /** "Google's Firebase Crashlytics", or the service name alone when it already starts with the company's. */
        fun displayName(name: String, company: String?): String =
            if (company == null || name.startsWith(company, ignoreCase = true)) name else "$company's $name"

        private fun catalogueWording(name: String, company: String?, hostCount: Int, checks: List<CheckId>): String = when {
            hostCount == 1 -> "This hostname belongs to ${displayName(name, company)} in TraceVeil's catalogue."
            hostCount > 1 -> "These hostnames belong to ${displayName(name, company)} in TraceVeil's catalogue."
            CheckId.C13 in checks -> "Named by the installed manifest check (C13); no lookups to its hosts were captured in this audit."
            else -> "Named by a finding; no lookups to its hosts were captured in this audit."
        }

        /** "first seen 3.0 s after launch · before consent", or "" when the group has no timed sighting. */
        fun firstSeenLine(group: SdkGroup): String {
            val first = group.hosts.filter { it.firstSeenSeconds != null }.minByOrNull { it.firstSeenSeconds!! } ?: return ""
            return "first seen ${String.format(Locale.US, "%.1f", first.firstSeenSeconds)} s after launch · ${first.firstRelation}"
        }

        /** The first [shown] hosts, then "+N more". */
        fun hostPreview(hosts: List<HostSighting>, shown: Int = 2): String {
            val head = hosts.take(shown).joinToString(", ") { it.host }
            val rest = hosts.size - shown
            return if (rest > 0) "$head +$rest more" else head
        }

        /**
         * One calm sentence naming the services (or, with [byCompany], their companies) behind the findings, or null
         * when no group is linked to a finding with score weight. "Most" only when one name carries over half the weight.
         */
        fun headline(groups: List<SdkGroup>, byCompany: Boolean): String? {
            val impactful = groups.filter { it.known && it.impact > 0 }
            if (impactful.isEmpty()) return null
            val weights = LinkedHashMap<String, Double>()
            impactful.forEach { g ->
                val key = if (byCompany) g.company ?: g.name else g.name
                weights[key] = (weights[key] ?: 0.0) + g.impact
            }
            val total = weights.values.sum()
            val names = weights.entries.sortedByDescending { it.value }.map { it.key }
            val top = weights.getValue(names.first())
            if (names.size > 1 && top > total / 2) return "Most of what this check found involves ${names.first()}."
            val shown = names.take(3)
            val list = when {
                names.size == 1 -> shown.first()
                names.size <= 3 -> shown.dropLast(1).joinToString(", ") + " and " + shown.last()
                else -> shown.joinToString(", ") + " and ${names.size - 3} more"
            }
            return "What this check found involves $list."
        }
    }
}
