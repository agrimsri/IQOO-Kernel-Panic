package com.dpdpxray.core.model

import kotlinx.serialization.Serializable

/**
 * What a contacted host is used for. TRACKING_CATEGORIES are the ones DPDP consent checks care about.
 * Saved audits store these by name: add new values at the end and never rename one.
 */
@Serializable
enum class Category {
    ANALYTICS, ADVERTISING, ATTRIBUTION, SOCIAL, CRASH_REPORTING, ENGAGEMENT, FIRST_PARTY, UNKNOWN,

    /** Serves images, video or static files (a content CDN). Not tracking by itself. */
    CONTENT_DELIVERY,

    /**
     * A catalogued company's general service domain (for example facebook.com: login, sharing, the company's own apps'
     * APIs). A lookup to it is a catalogue match only: it is not a known tracking endpoint, so no consent check counts it.
     * The company's specific tracking endpoints (graph.facebook.com, connect.facebook.net, an.facebook.com) keep their
     * own entries and categories.
     */
    COMPANY_SERVICE,

    /**
     * A catalogued analytics, advertising or social service run by the same company that publishes the audited app
     * (for example Meta servers contacted by Instagram). First-party data collection by the app's own company, not a
     * third-party tracker. Assigned in context by the rules engine, never by the catalogue alone.
     */
    FIRST_PARTY_SAME_COMPANY;

    val isTracking: Boolean get() = this in TRACKING_CATEGORIES

    companion object {
        val TRACKING_CATEGORIES = setOf(ANALYTICS, ADVERTISING, ATTRIBUTION, SOCIAL, ENGAGEMENT)
    }
}

/** Where a network event sits relative to the user's consent choice. */
@Serializable
enum class Phase { BEFORE_CONSENT, AFTER_ACCEPT, AFTER_REJECT, NO_CONSENT_SEEN }

/** Saved audits store these by name: add new values at the end and never rename one. */
@Serializable
enum class ConsentKind {
    SCREEN_APPEARED, ACCEPT, REJECT, SETTINGS, MANUAL_MARK,

    /**
     * Android's own runtime-permission dialog (the system permission controller) appeared while the audited app was
     * armed; [ConsentEvent.nodeText] holds its visible text. Never a consent choice and never a consent screen.
     */
    PERMISSION_DIALOG,
}

/** The three audit plans: accept the consent, reject it, or never touch the app. */
@Serializable
enum class AuditPlan(val label: String) {
    ACCEPT("Plan A · Accept"),
    REJECT("Plan B · Reject"),
    SILENT("Plan C · No interaction"),
}

@Serializable
enum class Severity(val weight: Int, val label: String) {
    CRITICAL(30, "Critical"),
    HIGH(15, "High"),
    MEDIUM(8, "Medium"),
    LOW(3, "Low"),
    INFO(0, "Info"),
}

/** OBSERVED = seen on the device. INFERRED = derived from SDK documentation or AI judgement. */
@Serializable
enum class EvidenceType { OBSERVED, INFERRED }

/** Statutory ceilings from the DPDP Act Schedule. Never show the ₹250 crore security band for consent issues. */
@Serializable
enum class PenaltyBand(val label: String) {
    RESIDUAL_50("Up to ₹50 crore (DPDP Schedule, residual)"),
    CHILDREN_200("Up to ₹200 crore (DPDP Schedule, children's data)"),
    NONE("—"),
}

@Serializable
data class NetEvent(
    val tNanos: Long,
    val hostname: String,
    val queryType: Int = 1,
    val entity: String? = null,
    val category: Category = Category.UNKNOWN,
    val phase: Phase = Phase.NO_CONSENT_SEEN,
    /** Package that issued the lookup, when it is not the audited app (e.g. Google Play services). */
    val viaPackage: String? = null,
    /** CNAME aliases observed in the reply, queried name first, in answer order. Empty = none observed or not read. */
    val cnameChain: List<String> = emptyList(),
    /** A/AAAA addresses answered for this query, in textual form. Empty = none observed or not read. */
    val answerIps: List<String> = emptyList(),
    /** Catalogue tracker domain reached through an observed alias, when that alias decided the classification. */
    val disguisedVia: String? = null,
    /** Outcome of the resolver reply for this lookup. null = not recorded, never a measured outcome. */
    val answerStatus: AnswerStatus? = null,
    /** DNS RCODE of a matching reply (0 = no error, 3 = NXDOMAIN). null when no matching reply was read. */
    val rcode: Int? = null,
)

@Serializable
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** A flattened-but-nested snapshot of an accessibility node. */
@Serializable
data class UiNode(
    val className: String = "",
    val text: String? = null,
    val contentDescription: String? = null,
    val isCheckable: Boolean = false,
    val isChecked: Boolean = false,
    val isClickable: Boolean = false,
    val bounds: Bounds? = null,
    val children: List<UiNode> = emptyList(),
    /** A text field whose content is hidden (a password or PIN box). Older saved audits read as false. */
    val isPassword: Boolean = false,
    /** A field the person types into. Older saved audits read as false. */
    val isEditable: Boolean = false,
    /** The placeholder shown inside an empty field ("Phone number"). Older saved audits read as null. */
    val hint: String? = null,
) {
    val label: String get() = listOfNotNull(text, contentDescription).joinToString(" ").trim()

    fun flatten(): List<UiNode> = listOf(this) + children.flatMap { it.flatten() }
}

@Serializable
data class ConsentEvent(
    val tNanos: Long,
    val kind: ConsentKind,
    val nodeText: String? = null,
    val screenshotPath: String? = null,
    val tree: UiNode? = null,
)

@Serializable
enum class RejectOption { VISIBLE, HIDDEN, NONE, UNKNOWN }

@Serializable
enum class AuditSource { VLM, DETERMINISTIC, MERGED }

/** Result of reviewing a consent screen, from the on-device VLM, the accessibility tree, or both. */
@Serializable
data class ScreenAudit(
    val isConsentScreen: Boolean,
    val preTicked: Boolean? = null,
    val rejectOption: RejectOption = RejectOption.UNKNOWN,
    val purposesCount: Int? = null,
    val separateChoices: Boolean? = null,
    val itemisedData: Boolean? = null,
    val purposeStated: Boolean? = null,
    val withdrawalInfo: Boolean? = null,
    /** null = unknown. A list containing only English means no Indian-language option was found. */
    val languageOptions: List<String>? = null,
    val childAudienceSignals: Boolean? = null,
    val darkPatterns: List<String> = emptyList(),
    val summary: String = "",
    val confidence: Double = 1.0,
    val source: AuditSource = AuditSource.DETERMINISTIC,
    /** Field names confirmed from the accessibility tree (OBSERVED); all others came from the VLM (INFERRED). */
    val observedFields: Set<String> = emptySet(),
    val conflicts: List<String> = emptyList(),
    val modelId: String? = null,
    val latencyMs: Long? = null,
    /** What ConsentLens-1 (on-device string-role model) added; null when it did not run. Its fields are INFERRED. */
    val consentLens: ConsentLensEvidence? = null,
)

/**
 * ConsentLens-1's contribution to a [ScreenAudit]. Every field it set is listed in [inferredFields] (never in
 * observedFields), with the on-screen strings that support it in [support]. Disagreements with OBSERVED tree facts
 * are kept in [ScreenAudit.conflicts]; disagreements with the screen-review model are kept in [disagreements].
 */
@Serializable
data class ConsentLensEvidence(
    val modelId: String,
    val backend: String? = null,
    val threshold: Double,
    val labels: List<ConsentLensLabel> = emptyList(),
    val inferredFields: List<String> = emptyList(),
    val support: Map<String, List<String>> = emptyMap(),
    /** true when a privacy-policy/notice link string was found; null = none found (not proof that none exists). */
    val privacyLinkFound: Boolean? = null,
    val disagreements: List<String> = emptyList(),
)

@Serializable
data class ConsentLensLabel(val text: String, val role: String, val confidence: Double, val clickable: Boolean)

@Serializable
data class AuditSession(
    val id: String,
    val targetPackage: String,
    val targetLabel: String,
    val plan: AuditPlan,
    val childFacing: Boolean = false,
    val startedAtNanos: Long = 0,
    val endedAtNanos: Long = 0,
    val startedAtEpochMs: Long = 0,
    val netEvents: List<NetEvent> = emptyList(),
    val consentEvents: List<ConsentEvent> = emptyList(),
    val screenAudit: ScreenAudit? = null,
    val deviceModel: String = "",
    val androidVersion: String = "",
    val privateDnsActive: Boolean? = null,
    val notes: List<String> = emptyList(),
    /** Capture completeness for this session. null = not recorded (audits saved before it existed), not "healthy". */
    val captureHealth: CaptureHealth? = null,
    /**
     * true = this plan started right after an approved reset actually cleared the app's data; false = the app kept its
     * data (reset not approved, not possible, or failed). null = not recorded (audits saved before this field existed).
     */
    val freshState: Boolean? = null,
    // --- Evidence for checks C12–C14 (all null on audits saved before they existed: not recorded, never "none") ---
    /**
     * When the audited app was confirmed off screen after TraceVeil pressed Home at the end of the plan's observation
     * window (same clock as [NetEvent.tNanos]); capture continued until [endedAtNanos]. null = no background step ran
     * or the app could not be confirmed off screen, so background behaviour is not established.
     */
    val backgroundStartedNanos: Long? = null,
    /**
     * true = the consent watcher also recorded Android's permission dialogs ([ConsentKind.PERMISSION_DIALOG]) in this
     * plan. null = not watched (older build), so the absence of such events says nothing.
     */
    val permissionDialogsWatched: Boolean? = null,
    /** Allow-listed facts read from the audited app's installed manifest at audit start. null = not read. */
    val manifestFacts: AppManifestFacts? = null,
    /**
     * Connections seen by the full-relay capture, labelled with their consent phase. Empty when the audit ran DNS-only
     * or was saved before this existed: see [captureDepth]; empty never means "no connections".
     */
    val flows: List<com.dpdpxray.core.netcapture.FlowRecord> = emptyList(),
    /** "dns-only" or "full-relay"; null = not recorded (older audits, DNS-only). */
    val captureDepth: String? = null,
) {
    val consentChoice: ConsentEvent?
        get() = consentEvents.firstOrNull { it.kind == ConsentKind.ACCEPT || it.kind == ConsentKind.REJECT }

    val consentScreenSeen: Boolean
        get() = consentEvents.any { it.kind == ConsentKind.SCREEN_APPEARED || it.kind == ConsentKind.MANUAL_MARK } ||
            consentChoice != null

    /**
     * Whether this plan ran on data the app kept from earlier use. A recorded [freshState] decides. For older audits
     * (null) the orchestrator's own "not cleared" notes decide; without such a note the answer is false, which keeps
     * the rules those audits were scored with.
     */
    val appDataKept: Boolean
        get() = freshState?.let { !it } ?: notes.any { n -> KEPT_DATA_NOTES.any { n.contains(it, ignoreCase = true) } }

    companion object {
        /** Note text the orchestrator writes when a plan did not start from cleared data (same markers the app uses). */
        val KEPT_DATA_NOTES = listOf("App data was not cleared", "Could not clear app data")
    }
}

/** Saved results store these by name: add new checks at the end and never rename one. */
@Serializable
enum class CheckId(val code: String, val title: String) {
    C1("C1", "Tracker hosts looked up before consent"),
    C2("C2", "Tracker hosts looked up after the user refused"),
    C3("C3", "Pre-ticked consent option"),
    C4("C4", "No clear way to refuse"),
    C5("C5", "Bundled consent for several purposes"),
    C6("C6", "Notice lacks itemised data or purpose"),
    C7("C7", "No visible way to withdraw consent"),
    C8("C8", "Notice offered only in English"),
    C9("C9", "Tracking in a child-facing app"),
    C10("C10", "Device identifiers likely shared before consent"),
    NC("NC", "No consent screen seen during the capture"),
    V0("V0", "Low network visibility — result inconclusive"),
    U1("U1", "Consent history unknown — app kept its data"),
    S1("S1", "Same-company servers contacted (first-party data collection)"),

    // Checks below were added after C1–C10. Each KDoc ends with a "Legal note" for the jurisdiction layer to map; the
    // DPDP references the rules engine attaches are only those that clearly apply (see RulesEngine).

    /**
     * A tracker lookup before consent or after Reject whose queried name is not a catalogued tracker but whose
     * observed DNS reply aliased it (CNAME) to one. INFO: the lookup itself is already scored under C1 or C2.
     * Legal note: no DPDP provision on aliasing as such; relevant where a law treats circumventing user controls
     * (blockers, opt-outs) as an aggravating factor.
     */
    C11("C11", "Tracker reached through an alias name (CNAME cloaking pattern)"),

    /**
     * Tracker lookups after the app was confirmed in the background (Home pressed, app off screen). INFO: lookups
     * before consent or after Reject are already scored under C1 or C2. Legal note: purpose limitation and
     * data-minimisation rules (where a law has them) are the closest fit; DPDP has no background-specific provision.
     */
    C12("C12", "Tracker hosts looked up while the app was in the background"),

    /**
     * An SDK declared in the installed manifest whose documented data-collection switches are left at their
     * default-on setting (absent or true), per the vendor's own documentation. Legal note: DPDP s.6(1) (consent before
     * processing); also maps to consent-before-collection and privacy-by-default rules elsewhere.
     */
    C13("C13", "SDK data collection on by default in the app's manifest"),

    /**
     * Android's runtime-permission dialog naming the app appeared before any consent screen or notice in the plan.
     * Legal note: DPDP s.5(1) (notice accompanies or precedes a request for consent) is context, not a verdict;
     * Google Play's User Data policy says not to request permissions before the user has seen the disclosure.
     */
    C14("C14", "Android permission requested before any consent screen or notice"),

    /**
     * The captured consent-screen accessibility tree has no privacy-policy link or text (English, Hindi, Kannada).
     * Legal note: not a stated DPDP requirement for the consent screen; Google Play requires a privacy policy link or
     * text within the app, and several laws require a notice or policy link at the point of collection.
     */
    C15("C15", "Consent screen shows no privacy-policy link or text"),

    /**
     * Capture health shows lookups TraceVeil could not see (encrypted or TCP DNS, Private DNS on, dropped or unreadable
     * lookups). INFO, no score impact: unknown stays unknown. Legal note: none; it qualifies every other finding.
     */
    V1("V1", "Some lookups may be hidden from TraceVeil"),
}

@Serializable
data class Finding(
    val checkId: CheckId,
    val severity: Severity,
    val evidenceType: EvidenceType,
    val title: String,
    val explanation: String,
    val evidence: List<String>,
    val plans: List<AuditPlan>,
    val dpdpRefs: List<String>,
    val penaltyBand: PenaltyBand,
    val confidence: Double = 1.0,
    val entities: List<String> = emptyList(),
)

@Serializable
enum class ScoreBand(val label: String) {
    READY("Ready"),
    NEEDS_WORK("Needs work"),
    AT_RISK("At risk"),
    INCONCLUSIVE("Inconclusive"),
}

@Serializable
data class AuditResult(
    val findings: List<Finding>,
    val score: Int?,
    val band: ScoreBand,
)
