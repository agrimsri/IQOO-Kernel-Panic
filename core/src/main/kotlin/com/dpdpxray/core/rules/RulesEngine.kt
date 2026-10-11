package com.dpdpxray.core.rules

import com.dpdpxray.core.manifest.SdkManifestCatalog
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.Category
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.Finding
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.PenaltyBand
import com.dpdpxray.core.model.Phase
import com.dpdpxray.core.model.RejectOption
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.ScreenAudit
import com.dpdpxray.core.model.Severity
import com.dpdpxray.core.trackers.AppOwner
import com.dpdpxray.core.trackers.AppOwners
import com.dpdpxray.core.trackers.TrackerCatalog
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Turns raw audit sessions into DPDP findings. Deterministic: same sessions in, same findings out.
 * The AI layer only enriches explanations; it never decides whether a finding exists.
 *
 * Context (G1): servers of the company that publishes the audited app are first-party data collection, not third-party
 * trackers ([CheckId.S1]); and a plan that ran on the app's kept data without showing a consent screen cannot tell
 * whether consent was given earlier, so its tracker lookups are reported under [CheckId.U1] instead of as "before
 * consent". Plans that started from an approved fresh reset are judged exactly as before.
 */
class RulesEngine(
    private val catalog: TrackerCatalog,
    owners: AppOwners = AppOwners.default,
    sdkCatalog: SdkManifestCatalog = SdkManifestCatalog.default,
    /** Connections from a capture deeper than DNS; [ConnectionSource.NONE] (DNS only) keeps every rule as it was. */
    private val connections: ConnectionSource = ConnectionSource.NONE,
) {

    private val classifier = SessionClassifier(catalog, owners)
    private val extended = ExtendedChecks(catalog, sdkCatalog) { s, e, relation -> describe(Ctx(s, emptyList(), null), e, relation) }

    private data class Ctx(val session: AuditSession, val events: List<NetEvent>, val owner: AppOwner?) {
        /** Kept data and no consent screen in this plan: consent may have been given before the audit (e.g. at sign-up). */
        val historyUnknown: Boolean get() = session.appDataKept && !session.consentScreenSeen
    }

    fun evaluate(recorded: List<AuditSession>): AuditResult {
        val sessions = recorded.map(::withNamedConnections)
        val ctx = sessions.map { s -> Ctx(s, classifier.classify(s), classifier.ownerOf(s)) }
        val findings = mutableListOf<Finding>()

        val allEvents = ctx.flatMap { it.events }
        val anyTracking = allEvents.any { it.category.isTracking }
        if (allEvents.size < MIN_EVENTS && !anyTracking) findings += lowVisibility(recorded)

        c1BeforeConsent(ctx)?.let { findings += it }
        noConsentMechanism(ctx)?.let { findings += it }
        c2AfterReject(ctx)?.let { findings += it }
        val screen = sessions.mapNotNull { it.screenAudit }.firstOrNull { it.isConsentScreen }
        val screenPlans = sessions.filter { it.screenAudit?.isConsentScreen == true }.map { it.plan }.distinct()
        if (screen != null) findings += screenChecks(screen, screenPlans)
        c9Children(ctx, screen)?.let { findings += it }
        c10AdIds(ctx)?.let { findings += it }
        consentHistoryUnknown(ctx)?.let { findings += it }
        sameCompany(ctx)?.let { findings += it }
        // C11–C15 and V1 (ExtendedChecks): only from evidence the session recorded; silent when it was not recorded.
        findings += extended.evaluate(ctx.map { PlanEvidence(it.session, it.events, it.owner, it.historyUnknown) })

        val (score, band) = ScoreCalculator.compute(findings)
        return AuditResult(findings.sortedWith(compareBy({ it.severity.ordinal }, { it.checkId.ordinal })), score, band)
    }

    private fun preConsent(e: NetEvent) = e.phase == Phase.BEFORE_CONSENT || e.phase == Phase.NO_CONSENT_SEEN

    /**
     * A plan with too few lookups, captured more deeply: connections whose server name was read stand in for the missing
     * lookups (marked [NetEvent.fromConnection]), so the plan can be judged from them. Names already looked up are not
     * added again, and plans with enough lookups are left exactly as recorded.
     */
    private fun withNamedConnections(s: AuditSession): AuditSession {
        if (s.netEvents.size >= MIN_EVENTS) return s
        val seen = connections.connections(s) ?: return s
        val looked = s.netEvents.map { it.hostname.lowercase().trimEnd('.') }.toSet()
        val extra = seen.mapNotNull { c ->
            c.serverName?.lowercase()?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() && it !in looked }
                ?.let { NetEvent(tNanos = c.tNanos, hostname = it, fromConnection = true) }
        }.distinctBy { it.hostname }
        return if (extra.isEmpty()) s else s.copy(netEvents = (s.netEvents + extra).sortedBy { it.tNanos })
    }

    private fun c1BeforeConsent(ctx: List<Ctx>): Finding? {
        val hits = ctx.filterNot { it.historyUnknown }
            .flatMap { c -> c.events.filter { it.category.isTracking && preConsent(it) }.map { c to it } }
        if (hits.isEmpty()) return null
        val entities = hits.mapNotNull { it.second.entity }.distinct()
        return Finding(
            checkId = CheckId.C1, severity = Severity.HIGH, evidenceType = EvidenceType.OBSERVED,
            title = CheckId.C1.title,
            explanation = "The app looked up hosts the tracker catalogue lists for ${entities.joinToString()} before the user " +
                "made any consent choice. $CATALOGUE_MATCH Under the DPDP Act, personal data may be processed only with consent (or a legitimate use), " +
                "and consent must be given by a clear affirmative action before processing starts.",
            evidence = hits.map { (c, e) -> describe(c, e) }.distinct().take(MAX_EVIDENCE),
            plans = hits.map { it.first.session.plan }.distinct(),
            dpdpRefs = listOf("s4(1)", "s6(1)"),
            penaltyBand = PenaltyBand.RESIDUAL_50,
            entities = entities,
        )
    }

    private fun noConsentMechanism(ctx: List<Ctx>): Finding? {
        if (ctx.any { it.session.consentScreenSeen }) return null
        // A plan that kept the app's data may simply not repeat a consent screen the user already answered.
        val checked = ctx.filterNot { it.historyUnknown }
        val trackers = checked.flatMap { it.events.filter { e -> e.category.isTracking } }
        if (trackers.isEmpty()) return null
        return Finding(
            checkId = CheckId.NC, severity = Severity.HIGH, evidenceType = EvidenceType.OBSERVED,
            title = CheckId.NC.title,
            explanation = "No consent screen or privacy notice was seen during this capture " +
                "(${checked.joinToString { c -> c.session.plan.label + (captured(c.session)?.let { ", $it" } ?: "") }}), yet the app looked up hosts the tracker " +
                "catalogue lists for ${trackers.mapNotNull { it.entity }.distinct().joinToString()}. This covers only the captured " +
                "window: the app may ask for consent later or elsewhere (for example at sign-up), which this audit cannot see. " +
                "DPDP requires a notice (s.5) and valid consent (s.6) before processing, and the Data Fiduciary must be able " +
                "to prove both (s.6(10)).",
            evidence = checked.map { c ->
                "No consent screen seen in ${c.session.plan.label}" + (captured(c.session)?.let { " during the $it captured" } ?: "") + "."
            },
            plans = checked.map { it.session.plan }.distinct(),
            dpdpRefs = listOf("s5(1)", "s6(1)", "s6(10)"),
            penaltyBand = PenaltyBand.RESIDUAL_50,
        )
    }

    private fun c2AfterReject(ctx: List<Ctx>): Finding? {
        val hits = ctx.flatMap { c -> c.events.filter { it.category.isTracking && it.phase == Phase.AFTER_REJECT }.map { c to it } }
        if (hits.isEmpty()) return null
        val entities = hits.mapNotNull { it.second.entity }.distinct()
        return Finding(
            checkId = CheckId.C2, severity = Severity.CRITICAL, evidenceType = EvidenceType.OBSERVED,
            title = CheckId.C2.title,
            explanation = "The user refused consent, but the app still looked up hosts the tracker catalogue lists for " +
                "${entities.joinToString()}. $CATALOGUE_MATCH Consent must be free and specific; processing based on a refused consent has no lawful ground.",
            evidence = hits.map { (c, e) -> describe(c, e) }.distinct().take(MAX_EVIDENCE),
            plans = hits.map { it.first.session.plan }.distinct(),
            dpdpRefs = listOf("s4(1)", "s6(1)"),
            penaltyBand = PenaltyBand.RESIDUAL_50,
            entities = entities,
        )
    }

    private fun screenChecks(a: ScreenAudit, plans: List<AuditPlan>): List<Finding> {
        val out = mutableListOf<Finding>()
        fun ev(field: String) = if (field in a.observedFields) EvidenceType.OBSERVED else EvidenceType.INFERRED
        fun add(id: CheckId, sev: Severity, field: String, explanation: String, evidence: String, refs: List<String>) {
            out += Finding(
                checkId = id, severity = sev, evidenceType = ev(field), title = id.title, explanation = explanation,
                evidence = listOf(evidence) + a.conflicts.filter { it.startsWith(field) }.map { "Note: tree and AI disagree — $it" } +
                    lensSupport(a, field),
                plans = plans, dpdpRefs = refs, penaltyBand = PenaltyBand.RESIDUAL_50,
                confidence = if (ev(field) == EvidenceType.OBSERVED) 1.0 else a.confidence,
            )
        }
        if (a.preTicked == true) add(
            CheckId.C3, Severity.HIGH, "preTicked",
            "A consent option is already switched on before the user acts. DPDP consent needs a clear affirmative action by the user.",
            "Consent screen shows a pre-ticked option.", listOf("s6(1)"),
        )
        if (a.rejectOption == RejectOption.NONE || a.rejectOption == RejectOption.HIDDEN) add(
            CheckId.C4, Severity.HIGH, "rejectOption",
            "Refusing is " + (if (a.rejectOption == RejectOption.NONE) "not possible" else "harder than agreeing (hidden behind another screen)") +
                ". Consent must be free, and withdrawing it must be as easy as giving it.",
            "Reject option: ${a.rejectOption.name.lowercase()}.", listOf("s6(1)", "s6(4)"),
        )
        if ((a.purposesCount ?: 0) >= 2 && a.separateChoices == false) add(
            CheckId.C5, Severity.MEDIUM, "separateChoices",
            "One button covers ${a.purposesCount} purposes. Consent must be specific to each specified purpose.",
            "${a.purposesCount} purposes, no separate choices.", listOf("s6(1)", "r3(b)"),
        )
        if (a.itemisedData == false || a.purposeStated == false) add(
            CheckId.C6, Severity.MEDIUM, if (a.itemisedData == false) "itemisedData" else "purposeStated",
            "The notice does not " + listOfNotNull(
                "list exactly which personal data is collected".takeIf { a.itemisedData == false },
                "state the specific purpose".takeIf { a.purposeStated == false },
            ).joinToString(" or ") + ", as Rule 3 requires.",
            "Notice: itemised data=${a.itemisedData}, purpose stated=${a.purposeStated}.", listOf("s5(1)", "r3(b)"),
        )
        if (a.withdrawalInfo == false) add(
            CheckId.C7, Severity.MEDIUM, "withdrawalInfo",
            "The notice does not explain how to withdraw consent later, exercise rights, or complain to the Board.",
            "No withdrawal information visible.", listOf("s5(1)", "s6(4)", "r3(c)"),
        )
        val langs = a.languageOptions
        if (langs != null && langs.all { it.equals("English", ignoreCase = true) }) add(
            CheckId.C8, Severity.LOW, "languageOptions",
            "The notice can only be read in English. DPDP requires an option to access it in English or any Eighth Schedule language.",
            "Languages offered: ${langs.ifEmpty { listOf("none shown") }.joinToString()}.", listOf("s5(3)", "s6(3)"),
        )
        return out
    }

    /** Names the on-device model and the on-screen strings behind a field ConsentLens inferred (INFERRED, not observed). */
    private fun lensSupport(a: ScreenAudit, field: String): List<String> {
        val lens = a.consentLens ?: return emptyList()
        if (field !in lens.inferredFields) return emptyList()
        return listOf("AI-inferred by ${lens.modelId} (on-device model) from: " + lens.support[field].orEmpty().joinToString("; "))
    }

    private fun c9Children(ctx: List<Ctx>, screen: ScreenAudit?): Finding? {
        val declared = ctx.any { it.session.childFacing }
        val signalled = screen?.childAudienceSignals == true
        if (!declared && !signalled) return null
        val hits = ctx.flatMap { c -> c.events.filter { it.category.isTracking }.map { c to it } }
        if (hits.isEmpty()) return null
        val entities = hits.mapNotNull { it.second.entity }.distinct()
        return Finding(
            checkId = CheckId.C9, severity = Severity.CRITICAL,
            evidenceType = if (declared) EvidenceType.OBSERVED else EvidenceType.INFERRED,
            title = CheckId.C9.title,
            explanation = "This app ${if (declared) "is declared child-facing" else "looks aimed at children"} and looks up " +
                "hosts the tracker catalogue lists for ${entities.joinToString()}. $CATALOGUE_MATCH DPDP bars tracking, behavioural monitoring and targeted advertising directed at " +
                "children, and requires verifiable parental consent.",
            evidence = hits.map { (c, e) -> describe(c, e) }.distinct().take(MAX_EVIDENCE),
            plans = hits.map { it.first.session.plan }.distinct(),
            dpdpRefs = listOf("s9(1)", "s9(3)"),
            penaltyBand = PenaltyBand.CHILDREN_200,
            confidence = if (declared) 1.0 else (screen?.confidence ?: 0.5),
            entities = entities,
        )
    }

    private fun c10AdIds(ctx: List<Ctx>): Finding? {
        val hits = ctx.filterNot { it.historyUnknown }.flatMap { c ->
            c.events.filter {
                preConsent(it) && it.category != Category.FIRST_PARTY_SAME_COMPANY &&
                    catalog.classify(it.hostname, it.cnameChain).collectsAdIdAtInit
            }.map { c to it }
        }
        if (hits.isEmpty()) return null
        val entities = hits.mapNotNull { it.second.entity }.distinct()
        return Finding(
            checkId = CheckId.C10, severity = Severity.MEDIUM, evidenceType = EvidenceType.INFERRED,
            title = CheckId.C10.title,
            explanation = "Per vendor documentation, ${entities.joinToString()} read a device or advertising identifier when they " +
                "initialise. They were looked up before consent, so identifiers were likely shared without consent. " +
                "(Inferred from SDK behaviour; payloads were not inspected.)",
            evidence = hits.map { (c, e) -> describe(c, e) }.distinct().take(MAX_EVIDENCE),
            plans = hits.map { it.first.session.plan }.distinct(),
            dpdpRefs = listOf("s6(1)"),
            penaltyBand = PenaltyBand.RESIDUAL_50,
            confidence = 0.7,
            entities = entities,
        )
    }

    /** Kept-data plans without a consent screen: earlier consent cannot be checked, so nothing here is "before consent". */
    private fun consentHistoryUnknown(ctx: List<Ctx>): Finding? {
        val unknown = ctx.filter { it.historyUnknown }
        if (unknown.isEmpty()) return null
        val hits = unknown.flatMap { c -> c.events.filter { it.category.isTracking }.map { c to it } }
        val entities = hits.mapNotNull { it.second.entity }.distinct()
        val state = unknown.map { it.session.plan }.distinct()
            .map { "${it.label}: the app kept its data (it did not start from an approved fresh reset) and no consent screen appeared." }
        val lookups = if (hits.isEmpty()) listOf("No third-party tracker lookups were recorded in these plans.")
            else hits.map { (c, e) -> describe(c, e, "consent history unknown") }.distinct()
        return Finding(
            checkId = CheckId.U1, severity = Severity.INFO, evidenceType = EvidenceType.OBSERVED,
            title = CheckId.U1.title,
            explanation = "This app kept its data from earlier use and no consent screen appeared during this run, so consent " +
                "may have been given earlier (for example at sign-up). TraceVeil cannot check that history, so these lookups " +
                "are not treated as made before consent and do not change the score. " +
                (if (entities.isEmpty()) "No third-party tracker lookups were recorded in these plans. "
                else "Third-party tracker lookups are listed below for review: ${entities.joinToString()}. ") +
                "To test first-launch consent, audit a test install with an approved reset.",
            evidence = (state + lookups).take(MAX_EVIDENCE),
            plans = unknown.map { it.session.plan }.distinct(),
            dpdpRefs = emptyList(),
            penaltyBand = PenaltyBand.NONE,
            entities = entities,
        )
    }

    /** Catalogued tracking services of the app's own publisher: first-party data collection, shown but not penalised. */
    private fun sameCompany(ctx: List<Ctx>): Finding? {
        val hits = ctx.flatMap { c -> c.events.filter { it.category == Category.FIRST_PARTY_SAME_COMPANY }.map { c to it } }
        if (hits.isEmpty()) return null
        val owners = hits.mapNotNull { it.first.owner }.distinctBy { it.packageName }
        val company = owners.map { it.company }.distinct().joinToString()
        val entities = hits.mapNotNull { it.second.entity }.distinct()
        return Finding(
            checkId = CheckId.S1, severity = Severity.INFO, evidenceType = EvidenceType.INFERRED,
            title = CheckId.S1.title,
            explanation = "The app's cited listing names its developer as " +
                owners.joinToString { "\"${it.quote}\" (${it.app}, ${it.source}, read ${it.asOf})" } +
                ", and these lookups went to servers the tracker catalogue attributes to the same company ($company). They are treated as " +
                "first-party data collection by the app's own company, not as third-party tracking, so the consent checks " +
                "and the score do not count them. A lookup is an attempt to contact a host, not proof that personal data was " +
                "sent. Some laws still require consent for first-party analytics or advertising; this audit does not assess that.",
            evidence = hits.map { (c, e) -> describeSameCompany(c, e) }.distinct().take(MAX_EVIDENCE),
            plans = hits.map { it.first.session.plan }.distinct(),
            dpdpRefs = emptyList(),
            penaltyBand = PenaltyBand.NONE,
            entities = entities,
        )
    }

    /**
     * V0 names its likely causes from what the phone recorded ([VisibilityDiagnosis]) instead of generic advice; it never
     * tells anyone to clear a personal app's data. The result stays inconclusive: unknown stays unknown.
     */
    private fun lowVisibility(sessions: List<AuditSession>): Finding {
        val diagnosis = VisibilityDiagnosis.diagnose(sessions, connections)
        return Finding(
            checkId = CheckId.V0, severity = Severity.INFO, evidenceType = EvidenceType.OBSERVED,
            title = CheckId.V0.title,
            explanation = "Almost no DNS lookups were captured, so this audit cannot call the app clean and gives no score. " +
                "Likely cause: ${VisibilityDiagnosis.explanation(diagnosis)}",
            evidence = (sessions.map { "${it.plan.label}: ${it.netEvents.size} lookups" } + diagnosis.flatMap { it.evidence })
                .distinct().take(MAX_EVIDENCE),
            plans = sessions.map { it.plan }.distinct(),
            dpdpRefs = emptyList(),
            penaltyBand = PenaltyBand.NONE,
        )
    }

    private fun describe(c: Ctx, e: NetEvent, relation: String? = null): String {
        val sinceLaunch = secs(e.tNanos - c.session.startedAtNanos)
        val choice = c.session.consentChoice
        val rel = when {
            relation != null -> relation
            choice == null && e.phase == Phase.NO_CONSENT_SEEN -> "no consent screen seen in this capture" + (captured(c.session)?.let { " ($it)" } ?: "")
            choice == null -> "the user had not chosen yet"
            e.tNanos < choice.tNanos -> "${secs(choice.tNanos - e.tNanos)} s before the consent choice"
            else -> "${secs(e.tNanos - choice.tNanos)} s after the user chose ${choice.kind.name.lowercase()}"
        }
        val who = e.entity?.let { " ($it)" } ?: ""
        val via = e.viaPackage?.let { " via $it" } ?: ""
        val match = e.entity?.let { " — catalogue match: ${e.category.name.lowercase().replace('_', ' ')}" } ?: ""
        val seen = if (e.fromConnection) "connected $sinceLaunch s after launch (server name read from the connection; no lookup seen)"
            else "looked up $sinceLaunch s after launch"
        return "${e.hostname}$who$via${alias(e)}$match — $seen, $rel [${c.session.plan.label}]"
    }

    /** How long the plan was watched ("35.3 s"), so consent claims stay inside the capture window; null if unrecorded. */
    private fun captured(s: AuditSession): String? = (s.endedAtNanos - s.startedAtNanos).takeIf { it > 0 }?.let { "${secs(it)} s" }

    private fun describeSameCompany(c: Ctx, e: NetEvent): String {
        val label = c.owner?.let { SessionClassifier.sameCompanyLabel(it.company) } ?: "Same company as the app"
        val purpose = classifier.catalogueCategory(e).name.lowercase().replace('_', ' ')
        val who = e.entity?.let { " ($it)" } ?: ""
        val via = e.viaPackage?.let { " via $it" } ?: ""
        return "${e.hostname}$who$via${alias(e)} — $label; catalogued as $purpose — " +
            "${secs(e.tNanos - c.session.startedAtNanos)} s after launch [${c.session.plan.label}]"
    }

    /** " — alias observed: a → b → tracker" up to the alias that decided the classification, or "" if none did. */
    private fun alias(e: NetEvent): String {
        val target = e.disguisedVia ?: return ""
        val chain = e.cnameChain.map { it.lowercase().trimEnd('.') }
        val end = chain.indexOf(target)
        val shown = if (end > 0) chain.take(end + 1) else listOf(e.hostname, target)
        return " — alias observed: ${shown.joinToString(" → ")}"
    }

    private fun secs(nanos: Long) = String.format(Locale.US, "%.1f", nanos / 1e9)

    companion object {
        const val MIN_EVENTS = 2

        /** Network findings rest on DNS lookups matched against the tracker catalogue, never on inspected payloads. */
        const val CATALOGUE_MATCH = "Each lookup is a catalogue match: the app tried to reach a host the tracker catalogue " +
            "lists as a tracking endpoint. It shows an attempt to contact, not proof that personal data was sent."
        const val MAX_EVIDENCE = 12
    }
}

object ScoreCalculator {
    /**
     * 100 minus severity weights (inferred findings count half). Ready needs 85 or more and no High or Critical problem
     * seen on the phone; Needs work is 60 to 84; At risk is below 60. The result is inconclusive, with no score, when too few
     * lookups were captured (V0) or when third-party trackers were looked up but consent history is unknown (U1 with
     * entities): a kept-data run cannot tell whether consent came first, so it must never read as "Ready".
     */
    fun compute(findings: List<Finding>): Pair<Int?, ScoreBand> {
        if (Inconclusive.reason(findings) != null) return null to ScoreBand.INCONCLUSIVE
        val penalty = findings.sumOf { f -> f.severity.weight * if (f.evidenceType == EvidenceType.INFERRED) 0.5 else 1.0 }
        val score = (100 - penalty).coerceAtLeast(0.0).roundToInt()
        // One High alone leaves exactly 85, so points cannot keep a serious problem out of Ready; this gate does.
        // Only problems seen on the phone close the gate: an AI-inferred finding costs points but never decides alone.
        val seriousSeen = findings.any { it.evidenceType == EvidenceType.OBSERVED && it.severity.weight >= Severity.HIGH.weight }
        val band = when {
            score >= 85 && !seriousSeen -> ScoreBand.READY
            score >= 60 -> ScoreBand.NEEDS_WORK
            else -> ScoreBand.AT_RISK
        }
        return score to band
    }

    /**
     * How far AI or documentation readings could move this result, without changing it: the worst case counts every
     * INFERRED finding at full weight as if seen on the phone (so an inferred High or Critical also closes the Ready
     * gate), the best case drops every INFERRED finding as if it were wrong. Both ends use [compute] itself. Null when the
     * result is inconclusive (V0 and U1 are OBSERVED, so neither end can change that).
     */
    fun robustness(findings: List<Finding>): ScoreRobustness? {
        val (score, band) = compute(findings)
        if (score == null) return null
        val confirmed = findings.map { if (it.evidenceType == EvidenceType.INFERRED) it.copy(evidenceType = EvidenceType.OBSERVED) else it }
        val (worst, worstBand) = compute(confirmed)
        val (best, bestBand) = compute(findings.filter { it.evidenceType == EvidenceType.OBSERVED })
        if (worst == null || best == null) return null
        val inferred = findings.filter { it.evidenceType == EvidenceType.INFERRED && it.severity.weight > 0 }
            .map { it.checkId }.distinct().sortedBy { it.ordinal }
        return ScoreRobustness(score, band, worst, worstBand, best, bestBand, inferred)
    }
}

/** Why a result has no score. Screens, reports and spoken summaries word "Inconclusive" by this reason. */
object Inconclusive {
    enum class Reason(val en: String, val hi: String) {
        LOW_VISIBILITY(
            "Inconclusive: too little was captured to judge this app.",
            "अनिर्णायक: इस ऐप को परखने लायक पर्याप्त नहीं दिखा।",
        ),
        CONSENT_HISTORY_UNKNOWN(
            "Inconclusive: the app kept its data, so whether consent came before these tracker lookups is unknown.",
            "अनिर्णायक: ऐप ने अपना डेटा रखा, इसलिए यह पता नहीं कि ट्रैकर लुकअप से पहले सहमति ली गई थी या नहीं।",
        ),
    }

    fun reason(findings: List<Finding>): Reason? = when {
        findings.any { it.checkId == CheckId.V0 } -> Reason.LOW_VISIBILITY
        findings.any { it.checkId == CheckId.U1 && it.entities.isNotEmpty() } -> Reason.CONSENT_HISTORY_UNKNOWN
        else -> null
    }
}
