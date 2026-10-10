package com.dpdpxray.core.datasafety

import com.dpdpxray.core.attribution.SdkAttribution
import com.dpdpxray.core.attribution.SdkGroup
import com.dpdpxray.core.fixes.FixCatalog
import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.Category
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.trackers.AppOwners
import com.dpdpxray.core.trackers.TrackerCatalog
import kotlinx.serialization.Serializable

/** Saved checks store these by name: add new values at the end and never rename one. */
@Serializable
enum class LabelPointKind {
    /** "No data collected" while SDK hosts whose documentation describes collection were looked up. The strongest point. */
    NO_COLLECTION_VS_SDKS,

    /** A category the contacted SDKs' documentation describes is not declared as collected or shared. */
    TYPE_NOT_DECLARED,

    /** "No data shared with third parties" while advertising or attribution SDK hosts were looked up. Needs review: Play does not count service providers as sharing. */
    NO_SHARING_VS_THIRD_PARTY_SDKS,

    /** Declared as collected but not shared, while third-party SDK hosts whose documentation describes it were looked up. */
    COLLECTED_NOT_SHARED,

    /** Declared, but no contacted SDK's documentation describes it. For information only. */
    DECLARED_NOT_EXPLAINED,
}

/** One point to review: a neutral sentence, the SDKs and categories it rests on, and the caveat that goes with it. */
@Serializable
data class LabelPoint(
    val kind: LabelPointKind,
    val text: String,
    val sdks: List<String> = emptyList(),
    val categories: List<DataCategory> = emptyList(),
    /** True for a note for information, false for a point to review. */
    val informational: Boolean = false,
    val caveat: String? = null,
)

/** A catalogued third-party SDK whose hosts this audit looked up, and what its documentation says it collects. */
@Serializable
data class ObservedSdk(
    val entity: String,
    val company: String? = null,
    val category: Category,
    val lookups: Int,
    /** Every relation to the consent choice it was seen in, in [SdkAttribution.RELATIONS] order. */
    val relations: List<String>,
    /** The relation of its earliest lookup ("before consent", "after Accept", ...). */
    val firstRelation: String,
    /** Advertising, attribution or social SDK, or one whose vendor page says it shares data: relevant to "sharing". */
    val sharingRelevant: Boolean,
    val expectation: SdkExpectation,
    /** Earliest lookup, seconds after its plan started; null when not recorded. */
    val firstSeenSeconds: Double? = null,
)

/**
 * The result of setting a declared Play label against what one audit saw. Separate from the audit's findings and score:
 * it never changes either. [notCompared] says, in plain words, what could not be compared and why.
 */
@Serializable
data class PlayLabelCheck(
    val label: DataSafetyLabel,
    val observed: List<ObservedSdk>,
    val points: List<LabelPoint>,
    val notCompared: List<String> = emptyList(),
    /** Catalogued services run by the app's own company (cited record), left out of the comparison. */
    val sameCompany: List<String> = emptyList(),
) {
    val toReview: List<LabelPoint> get() = points.filterNot { it.informational }

    /** One calm line for a folded card or everyday mode. */
    val summary: String
        get() = when {
            !label.readable -> "Could not read a Data safety label in this text"
            toReview.isEmpty() -> "No points to review in this comparison"
            toReview.size == 1 -> "1 point to review"
            else -> "${toReview.size} points to review"
        }
}

/**
 * "Play label vs what this check saw": compares a declared [DataSafetyLabel] with the catalogued SDK hosts an audit looked
 * up, using each SDK's own documentation ([SdkExpectations]). Deterministic and offline. Lookups are grouped exactly as
 * "Which SDK caused this?" groups them, so same-company services are left out.
 *
 * Truth rules: a label is the developer's own declaration; a DNS lookup is an attempt to contact a host, not proof that data
 * was sent; Play does not count a transfer to a service provider as sharing. So the output is points to review, never a
 * verdict, and it is never part of the score.
 */
class PlayLabelComparator(
    private val catalog: TrackerCatalog,
    private val expectations: SdkExpectations = SdkExpectations.default,
    private val owners: AppOwners = AppOwners.default,
) {
    /** Groups [sessions] the way the result screen does, then compares. */
    fun compare(label: DataSafetyLabel, sessions: List<AuditSession>): PlayLabelCheck {
        val groups = SdkAttribution(catalog, FixCatalog(emptyList()), owners).groups(NO_FINDINGS, sessions)
        return compare(label, groups)
    }

    /** [groups] are "Which SDK caused this?" groups of one audit. */
    @JvmName("compareGroups")
    fun compare(label: DataSafetyLabel, groups: List<SdkGroup>): PlayLabelCheck {
        val seen = groups.filter { it.known && it.hosts.isNotEmpty() && (it.category.isTracking || it.category == Category.CRASH_REPORTING) }
        val sameCompany = seen.filter { it.sameCompanyAsApp }.map { it.name }
        val observed = seen.filterNot { it.sameCompanyAsApp }
            .mapNotNull { g -> expectations.forEntity(g.name, g.category)?.let { observedSdk(g, it) } }
            .sortedWith(compareBy<ObservedSdk> { it.firstSeenSeconds ?: Double.MAX_VALUE }.thenBy { it.entity })

        if (!label.readable) return PlayLabelCheck(label, observed, emptyList(), listOf(UNREADABLE), sameCompany)

        val points = mutableListOf<LabelPoint>()
        val notes = mutableListOf<String>()

        // Collection first: Play's "collect" covers data an app's SDKs transmit, so this is the safest comparison.
        if (label.noDataCollected && observed.isNotEmpty()) {
            points += LabelPoint(
                LabelPointKind.NO_COLLECTION_VS_SDKS,
                "Label says no data collected, but this check saw lookups to ${timed(observed)}. ${documented(observed, null)}",
                observed.map { it.entity }, common(observed), caveat = COLLECT_CAVEAT,
            )
        }
        val collected = label.collected
        when {
            label.noDataCollected -> Unit
            collected == null -> notes += "The pasted text has no collection statement, so collected data types were not compared."
            collected.notListed > 0 -> notes += "The pasted summary names only some collected types (\"and ${collected.notListed} others\"), " +
                "so undeclared types were not checked. Paste the text from \"See details\" on the Play listing for a full comparison."
            else -> points += byCategory(observed) { it !in label.declaredCategories }.map { (cat, sdks) ->
                LabelPoint(
                    LabelPointKind.TYPE_NOT_DECLARED,
                    "Label does not list ${cat.label} among collected or shared data, but this check saw lookups to ${timed(sdks)}. " +
                        documented(sdks, cat),
                    sdks.map { it.entity }, listOf(cat), caveat = CONFIG_CAVEAT,
                )
            }
        }

        // Sharing second, and worded for review: Play exempts transfers to service providers from "sharing".
        val thirdParty = observed.filter { it.sharingRelevant }
        if (label.noDataShared && thirdParty.isNotEmpty()) {
            points += LabelPoint(
                LabelPointKind.NO_SHARING_VS_THIRD_PARTY_SDKS,
                "Label says no data shared with third parties, while this check saw lookups to advertising or attribution SDK hosts: " +
                    "${timed(thirdParty)}. ${documented(thirdParty, null)}${sharesSentence(thirdParty)}",
                thirdParty.map { it.entity }, common(thirdParty), caveat = SHARING_CAVEAT,
            )
        }
        val shared = label.shared
        when {
            label.noDataShared -> Unit
            shared == null -> notes += "The pasted text has no sharing statement, so shared data types were not compared."
            shared.notListed > 0 -> notes += "The pasted summary names only some shared types (\"and ${shared.notListed} others\"), " +
                "so \"collected but not shared\" was not checked."
            collected != null && collected.notListed == 0 -> points += byCategory(thirdParty) { it in collected.categories && it !in shared.categories }
                .map { (cat, sdks) ->
                    LabelPoint(
                        LabelPointKind.COLLECTED_NOT_SHARED,
                        "Label declares ${cat.label} as collected but not shared, while third-party SDK hosts were contacted: ${timed(sdks)}. " +
                            documented(sdks, cat) + sharesSentence(sdks),
                        sdks.map { it.entity }, listOf(cat), caveat = SHARING_CAVEAT,
                    )
                }
        }

        val explained = observed.flatMap { it.expectation.categories }.toSet()
        val unexplained = label.declaredCategories.filter { it !in explained }.sortedBy { it.ordinal }
        if (unexplained.isNotEmpty()) {
            points += LabelPoint(
                LabelPointKind.DECLARED_NOT_EXPLAINED,
                "Declared types the observed SDKs don't explain: ${join(unexplained.map { it.label })}. They may come from the app's own " +
                    "servers, from features this check did not use, or from SDKs it did not see.",
                emptyList(), unexplained, informational = true,
            )
        }

        if (observed.isEmpty()) notes += NOTHING_SEEN
        if (sameCompany.isNotEmpty()) notes += "Same company as the app, not compared: ${join(sameCompany)}."
        return PlayLabelCheck(label, observed, points, notes, sameCompany)
    }

    private fun observedSdk(g: SdkGroup, e: SdkExpectation): ObservedSdk {
        val first = g.hosts.minWithOrNull(compareBy { it.firstSeenSeconds ?: Double.MAX_VALUE }) ?: g.hosts.first()
        return ObservedSdk(
            entity = g.name, company = g.company, category = g.category, lookups = g.lookups,
            relations = SdkAttribution.RELATIONS.filter { r -> g.hosts.any { r in it.relations } },
            firstRelation = first.firstRelation,
            sharingRelevant = g.category in SHARING_KINDS || e.sharesPerSource,
            expectation = e, firstSeenSeconds = first.firstSeenSeconds,
        )
    }

    /** Categories (in the order of [keep]) with the SDKs that document each; most SDKs first, then Play's order. */
    private fun byCategory(sdks: List<ObservedSdk>, keep: (DataCategory) -> Boolean): List<Pair<DataCategory, List<ObservedSdk>>> =
        DataCategory.entries.filter(keep)
            .map { cat -> cat to sdks.filter { cat in it.expectation.categories } }
            .filter { it.second.isNotEmpty() }
            .sortedWith(compareByDescending<Pair<DataCategory, List<ObservedSdk>>> { p -> p.second.count { it.expectation.verified } }.thenBy { it.first.ordinal })

    companion object {
        private val NO_FINDINGS = AuditResult(emptyList(), null, ScoreBand.INCONCLUSIVE)
        private val SHARING_KINDS = setOf(Category.ADVERTISING, Category.ATTRIBUTION, Category.SOCIAL)

        const val UNREADABLE = "Could not find a Data safety statement in this text. Paste the Data safety section from the " +
            "app's Google Play listing (English), or the text from its \"See details\" page."
        const val NOTHING_SEEN = "This check saw no lookups to catalogued third-party SDK hosts, so there is nothing to set against " +
            "the label. That does not show the label is complete: the catalogue is limited and some lookups can be hidden."
        const val COLLECT_CAVEAT = "Play counts data that an app's SDKs transmit off the device as collected. A lookup is an " +
            "attempt to contact a host, not proof that data was sent."
        const val CONFIG_CAVEAT = "What an SDK collects depends on how the app configures it. A lookup is an attempt to contact a " +
            "host, not proof that data was sent."
        const val SHARING_CAVEAT = "This may need review rather than showing a mismatch: Play does not count a transfer to a " +
            "service provider that processes data on the developer's behalf as sharing. A lookup is an attempt to contact a host, " +
            "not proof that data was sent."

        /** Method and limits, said once wherever the comparison is shown. */
        const val ABOUT = "A Data safety label is the developer's own declaration; Google says Play's review \"is not designed " +
            "to verify the accuracy and completeness of your data safety declarations\". TraceVeil sets the label you paste " +
            "against the catalogued SDK hosts this check looked up and what each SDK's own documentation says it collects. " +
            "These are points to review with the developer, not verdicts, and they never change the score. " +
            SdkExpectations.MAPPING_NOTE

        private fun relationPhrase(relation: String): String = when (relation) {
            SdkAttribution.NO_CONSENT_SEEN -> "in a run with no consent screen"
            SdkAttribution.HISTORY_UNKNOWN -> "while consent history was unknown"
            else -> relation
        }

        /** "A and B before consent, and C after Accept": SDKs grouped by the relation of their first lookup. */
        fun timed(sdks: List<ObservedSdk>): String {
            val parts = sdks.groupBy { it.firstRelation }.entries
                .sortedBy { SdkAttribution.RELATIONS.indexOf(it.key).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                .map { (relation, list) -> "${join(list.map { it.entity })} ${relationPhrase(relation)}" }
            return when (parts.size) {
                1 -> parts.single()
                2 -> "${parts[0]}, and ${parts[1]}"
                else -> parts.dropLast(1).joinToString(", ") + ", and " + parts.last()
            }
        }

        /** "Their documentation says these SDKs can collect X", with unverified SDKs named softly. */
        private fun documented(sdks: List<ObservedSdk>, category: DataCategory?): String {
            val cats = category?.let { listOf(it) } ?: common(sdks)
            val unverified = sdks.filterNot { it.expectation.verified }.map { it.entity }
            if (cats.isEmpty()) return "Their documentation describes what each of them collects (see sources)."
            val what = join(cats.map { it.label })
            return when {
                unverified.size == sdks.size ->
                    "TraceVeil's catalogue expects SDKs of this kind to collect $what (${SdkExpectations.UNVERIFIED})."
                unverified.isEmpty() -> "Their documentation says these SDKs can collect $what."
                else -> "Their documentation says these SDKs can collect $what (for ${join(unverified)} this is a ${SdkExpectations.UNVERIFIED})."
            }
        }

        /** Categories every SDK in [sdks] documents, in the first SDK's order. */
        private fun common(sdks: List<ObservedSdk>): List<DataCategory> {
            val first = sdks.firstOrNull()?.expectation?.categories ?: return emptyList()
            return first.filter { c -> sdks.all { c in it.expectation.categories } }
        }

        private fun sharesSentence(sdks: List<ObservedSdk>): String = sdks.filter { it.expectation.sharesPerSource && it.expectation.sharesQuote != null }
            .joinToString("") { " The documentation for ${it.entity} says it \"${it.expectation.sharesQuote}\"" }

        fun join(items: List<String>): String = when (items.size) {
            0 -> ""
            1 -> items.single()
            else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
        }
    }
}
