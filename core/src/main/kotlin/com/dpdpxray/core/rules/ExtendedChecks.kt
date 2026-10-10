package com.dpdpxray.core.rules

import com.dpdpxray.core.consent.ConsentLexicon
import com.dpdpxray.core.manifest.FlagState
import com.dpdpxray.core.manifest.ManifestFlag
import com.dpdpxray.core.manifest.SdkCollectionStatus
import com.dpdpxray.core.manifest.SdkManifestCatalog
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.CaptureHealth
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.Finding
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.PenaltyBand
import com.dpdpxray.core.model.Phase
import com.dpdpxray.core.model.Severity
import com.dpdpxray.core.quarantine.QuarantineUiRules
import com.dpdpxray.core.screen.UiTreeLimits
import com.dpdpxray.core.trackers.AppOwner
import com.dpdpxray.core.trackers.TrackerCatalog
import java.util.Locale

/** One plan as the rules engine sees it: the session, its classified lookups, its publisher and consent-history state. */
internal data class PlanEvidence(
    val session: AuditSession,
    val events: List<NetEvent>,
    val owner: AppOwner?,
    /** Kept data and no consent screen in this plan (see RulesEngine): consent may have been given before the audit. */
    val historyUnknown: Boolean,
)

/**
 * Checks added after C1–C10: C11–C15 and V1. Each rests only on evidence the phone records (DNS replies, the
 * background step, the installed manifest, Android's permission dialogs, the consent screen's accessibility tree,
 * capture health). When that evidence was not recorded a check stays silent; coverage tools report it as NOT
 * ESTABLISHED, never as passed.
 *
 * Scoring rule: one DNS lookup is scored at most once (by C1 or C2), so the lookup-based additions C11 and C12 are INFO.
 * C13 and C14 add facts no other check counts and are LOW. C15 has no clear DPDP basis and V1 qualifies the evidence,
 * so both are INFO. No new check shows a penalty band.
 */
internal class ExtendedChecks(
    private val catalog: TrackerCatalog,
    private val sdkCatalog: SdkManifestCatalog,
    /** The rules engine's evidence line for one lookup; a non-null relation replaces its consent-relation text. */
    private val lookupLine: (AuditSession, NetEvent, String?) -> String,
) {
    fun evaluate(plans: List<PlanEvidence>): List<Finding> = listOfNotNull(
        aliasedTracker(plans),
        backgroundLookups(plans),
        manifestDefaults(plans),
        permissionBeforeNotice(plans),
        noPolicyLink(plans),
        hiddenLookups(plans),
    )

    private fun preConsent(e: NetEvent) = e.phase == Phase.BEFORE_CONSENT || e.phase == Phase.NO_CONSENT_SEEN

    // --- C11 -------------------------------------------------------------------------------------------------------

    /** Tracker lookups before consent or after Reject that were classified only through an observed CNAME alias. */
    private fun aliasedTracker(plans: List<PlanEvidence>): Finding? {
        val hits = plans.filterNot { it.historyUnknown }.flatMap { p ->
            p.events.filter { e -> e.category.isTracking && e.disguisedVia != null && (preConsent(e) || e.phase == Phase.AFTER_REJECT) }
                .map { p to it }
        }
        if (hits.isEmpty()) return null
        val entities = hits.mapNotNull { it.second.entity }.distinct()
        return Finding(
            checkId = CheckId.C11, severity = Severity.INFO, evidenceType = EvidenceType.OBSERVED, title = CheckId.C11.title,
            explanation = "These lookups asked for a name that did not itself match a tracker in the catalogue, but the DNS " +
                "reply TraceVeil read aliased it (CNAME) to a host the catalogue lists for ${entities.joinToString()}. " +
                "Blocklists and reviews that look only at the names an app asks for would miss them; the pattern is known as " +
                "CNAME cloaking. TraceVeil cannot tell why the alias was set up. Each lookup is already counted under C1 " +
                "(before consent) or C2 (after Reject), so this note does not change the score. ${RulesEngine.CATALOGUE_MATCH}",
            evidence = hits.map { (p, e) -> lookupLine(p.session, e, null) }.distinct().take(RulesEngine.MAX_EVIDENCE),
            plans = hits.map { it.first.session.plan }.distinct(),
            dpdpRefs = emptyList(),
            penaltyBand = PenaltyBand.NONE,
            entities = entities,
        )
    }

    // --- C12 -------------------------------------------------------------------------------------------------------

    /** Tracker lookups after the app was confirmed off screen by the background step. */
    private fun backgroundLookups(plans: List<PlanEvidence>): Finding? {
        val hits = plans.flatMap { p ->
            val bg = p.session.backgroundStartedNanos ?: return@flatMap emptyList()
            p.events.filter { it.category.isTracking && it.tNanos >= bg }.map { p to it }
        }
        if (hits.isEmpty()) return null
        val entities = hits.mapNotNull { it.second.entity }.distinct()
        val windows = hits.map { it.first }.distinctBy { it.session.id }.mapNotNull { p ->
            val bg = p.session.backgroundStartedNanos ?: return@mapNotNull null
            (p.session.endedAtNanos - bg).takeIf { it > 0 }?.let { "${p.session.plan.label}: watched ${secs(it)} s in the background" }
        }
        return Finding(
            checkId = CheckId.C12, severity = Severity.INFO, evidenceType = EvidenceType.OBSERVED, title = CheckId.C12.title,
            explanation = "After the plan's observation window TraceVeil pressed Home, confirmed the app had left the screen, " +
                "and kept capturing. While the app was in the background it looked up hosts the tracker catalogue lists for " +
                "${entities.joinToString()}. Background work can be legitimate (for example syncing or delivering " +
                "notifications), and TraceVeil cannot see what was sent. Lookups made before consent or after Reject are " +
                "already counted under C1 or C2, so this note does not change the score. ${RulesEngine.CATALOGUE_MATCH}",
            evidence = (hits.map { (p, e) ->
                val bg = p.session.backgroundStartedNanos ?: e.tNanos
                lookupLine(p.session, e, "${secs(e.tNanos - bg)} s after the app went to the background, ${consentRelation(p.session, e)}")
            }.distinct().take(RulesEngine.MAX_EVIDENCE - windows.size) + windows),
            plans = hits.map { it.first.session.plan }.distinct(),
            dpdpRefs = emptyList(),
            penaltyBand = PenaltyBand.NONE,
            entities = entities,
        )
    }

    // --- C13 -------------------------------------------------------------------------------------------------------

    /** SDKs bundled in the installed manifest whose documented collection switches are left on. */
    private fun manifestDefaults(plans: List<PlanEvidence>): Finding? {
        val source = plans.firstOrNull { it.session.manifestFacts != null } ?: return null
        val facts = source.session.manifestFacts ?: return null
        val owner = source.owner
        val on = sdkCatalog.evaluate(facts).filter { it.onByDefault == true }
            // Same company as the app's publisher: first-party collection (as S1), not a third-party SDK default.
            .filterNot { st -> owner != null && catalog.entryFor(st.entry.entity)?.company == owner.company }
        if (on.isEmpty()) return null
        val version = listOfNotNull(facts.versionName?.let { "version $it" }, facts.versionCode?.let { "code $it" })
            .joinToString(", ").let { if (it.isEmpty()) "" else " ($it)" }
        val evidence = on.map { manifestLine(it, version) } + on.mapNotNull { st -> firstLookupOf(st.entry.entity, plans) }
        return Finding(
            checkId = CheckId.C13, severity = Severity.LOW, evidenceType = EvidenceType.INFERRED, title = CheckId.C13.title,
            explanation = "The app's installed manifest bundles ${on.joinToString { it.entry.entity }} and leaves their " +
                "documented data-collection switches at the default, which is on. Per each vendor's documentation this then " +
                "runs automatically from app launch instead of waiting for a consent choice (" +
                on.joinToString("; ") { "${it.entry.entity}: ${it.entry.collection.replaceFirstChar { c -> c.lowercase() }}" } +
                "), and the vendors document these switches for waiting until the user consents. The manifest facts were " +
                "read on this phone (observed); what the SDK does with them comes from vendor documentation (inferred). A call " +
                "in the app's own code could change the setting after launch, which TraceVeil cannot see. Payloads were not inspected.",
            evidence = evidence.take(RulesEngine.MAX_EVIDENCE),
            plans = plans.filter { it.session.manifestFacts != null }.map { it.session.plan }.distinct(),
            dpdpRefs = listOf("s6(1)"),
            penaltyBand = PenaltyBand.NONE,
            confidence = 0.8,
            entities = on.map { it.entry.entity }.distinct(),
        )
    }

    private fun manifestLine(st: SdkCollectionStatus, version: String): String {
        fun flag(f: ManifestFlag, s: FlagState) = when (s) {
            FlagState.DEFAULT -> "${f.key} ${f.absentMeans}"
            FlagState.ON -> "${f.key} declared to keep it on"
            FlagState.OFF -> "${f.key} declared off"
            FlagState.UNREADABLE -> "${f.key} declared with an unreadable value"
        }
        val ids = st.identifiers.filter { it.second != FlagState.OFF }.map { (f, s) -> flag(f, s) }
        return "${st.entry.entity}: bundled (installed manifest declares ${st.evidencedBy.take(2).joinToString()}" +
            "${if (st.evidencedBy.size > 2) " and ${st.evidencedBy.size - 2} more" else ""}); ${st.entry.collection}: " +
            (st.gates.map { (f, s) -> flag(f, s) } + ids).joinToString("; ") +
            " — read from the installed app$version at audit start; vendor docs: ${st.entry.source}"
    }

    /** The first lookup of [entity]'s hosts in this audit (first plan that has one), as context for a manifest fact. */
    private fun firstLookupOf(entity: String, plans: List<PlanEvidence>): String? {
        val (p, e) = plans.asSequence()
            .mapNotNull { p -> p.events.filter { it.entity == entity }.minByOrNull { it.tNanos }?.let { p to it } }
            .firstOrNull() ?: return null
        return "Its hosts were also looked up in this audit: ${lookupLine(p.session, e, null)}"
    }

    // --- C14 -------------------------------------------------------------------------------------------------------

    private data class DialogHit(val plan: PlanEvidence, val dialog: ConsentEvent, val noticeAt: Long?)

    /** Android's permission dialog naming the app before any consent screen or notice of the plan. */
    private fun permissionBeforeNotice(plans: List<PlanEvidence>): Finding? {
        val hits = plans.filter { it.session.permissionDialogsWatched == true }.flatMap { p ->
            val s = p.session
            val noticeAt = s.consentEvents.filter { it.kind in NOTICE_KINDS }.minOfOrNull { it.tNanos }
            s.consentEvents.filter { d ->
                d.kind == ConsentKind.PERMISSION_DIALOG && d.nodeText != null &&
                    QuarantineUiRules.namesTarget(d.nodeText, s.targetLabel, s.targetPackage) &&
                    // With kept data and no screen at all, a notice may have been shown before the audit (as U1).
                    (if (noticeAt != null) d.tNanos < noticeAt else !p.historyUnknown)
            }.map { DialogHit(p, it, noticeAt) }
        }
        if (hits.isEmpty()) return null
        val personal = hits.filterNot { isNotificationRequest(it.dialog.nodeText.orEmpty()) }
        val app = hits.first().plan.session.targetLabel
        return Finding(
            checkId = CheckId.C14,
            severity = if (personal.isEmpty()) Severity.INFO else Severity.LOW,
            evidenceType = EvidenceType.OBSERVED, title = CheckId.C14.title,
            explanation = "Android asked the user to give $app a permission before any consent screen or privacy notice " +
                "appeared in the plan. " +
                (if (personal.isEmpty()) "Only the notification permission was requested, which grants no access to personal data, so this is informational. "
                else "Granting a permission can let the app access personal data (for example location, contacts, camera, " +
                    "microphone or files) before it has explained why. DPDP s.5(1) requires a notice to accompany or precede a " +
                    "request for consent; whether Android's prompt is such a request is a legal question, so this is context, " +
                    "not a verdict. Google Play's User Data policy also says not to request permissions before the user has " +
                    "seen the in-app disclosure. ") +
                "Only Android's own permission dialogs are recognised (AOSP and Google permission controller); some phone " +
                "brands' dialogs may be missed. A notice shown elsewhere, or one that does not look like a consent screen, is not seen.",
            evidence = hits.map(::dialogLine).distinct().take(RulesEngine.MAX_EVIDENCE),
            plans = hits.map { it.plan.session.plan }.distinct(),
            dpdpRefs = if (personal.isEmpty()) emptyList() else listOf("s5(1)"),
            penaltyBand = PenaltyBand.NONE,
        )
    }

    private fun dialogLine(h: DialogHit): String {
        val s = h.plan.session
        val text = h.dialog.nodeText.orEmpty()
        val kind = if (isNotificationRequest(text)) " (notification permission: grants no access to personal data)" else ""
        val rel = if (h.noticeAt != null) "${secs(h.noticeAt - h.dialog.tNanos)} s before the consent screen appeared"
        else "and no consent screen or notice was seen in this capture" + (captured(s)?.let { " ($it)" } ?: "")
        return "Android permission dialog \"$text\"$kind — shown ${secs(h.dialog.tNanos - s.startedAtNanos)} s after launch, $rel [${s.plan.label}]"
    }

    // --- C15 -------------------------------------------------------------------------------------------------------

    /** Every captured consent-screen tree is complete and none names a privacy policy or notice. */
    private fun noPolicyLink(plans: List<PlanEvidence>): Finding? {
        val screens = plans.flatMap { p -> p.session.consentEvents.filter { it.kind in SCREEN_KINDS && it.tree != null }.map { p to it } }
        if (screens.isEmpty()) return null
        // A tree at the copy limit may have been cut off, so "no link in it" is not established.
        if (screens.any { (_, ev) -> UiTreeLimits.possiblyTruncated(ev.tree!!) }) return null
        val labels = screens.map { (_, ev) -> ev.tree!!.flatten().map { it.label }.filter { it.isNotBlank() } }
        if (labels.all { it.isEmpty() }) return null
        if (labels.any { ConsentLexicon.mentionsPrivacyPolicy(it) }) return null
        return Finding(
            checkId = CheckId.C15, severity = Severity.INFO, evidenceType = EvidenceType.OBSERVED, title = CheckId.C15.title,
            explanation = "The consent screen's accessibility tree, as captured, names no privacy policy or privacy notice " +
                "(English, Hindi or Kannada wording, or a privacy link address). A link at the point of consent lets people " +
                "read the full notice before they choose, and Google Play's User Data policy requires a privacy policy link or " +
                "text within the app. DPDP does not state this requirement for the consent screen itself, so this is " +
                "informational and the score is unchanged. Only the captured screen was read: a link on a screen opened from it " +
                "(such as \"Manage\"), an icon without a text label, or a policy shown elsewhere in the app is not seen.",
            evidence = screens.zip(labels).map { (pe, l) ->
                val (p, ev) = pe
                val shown = l.take(3).joinToString(", ") { "\"${clip(it)}\"" } + if (l.size > 3) ", …" else ""
                "Consent screen captured ${secs(ev.tNanos - p.session.startedAtNanos)} s after launch: ${l.size} text " +
                    "elements read ($shown); none names a privacy policy or notice [${p.session.plan.label}]"
            }.distinct().take(RulesEngine.MAX_EVIDENCE),
            plans = screens.map { it.first.session.plan }.distinct(),
            dpdpRefs = emptyList(),
            penaltyBand = PenaltyBand.NONE,
        )
    }

    // --- V1 --------------------------------------------------------------------------------------------------------

    /** Capture-health signs that some lookups were not visible. */
    private fun hiddenLookups(plans: List<PlanEvidence>): Finding? {
        val lines = plans.flatMap { p ->
            val s = p.session
            val h = s.captureHealth
            val plan = s.plan.label
            buildList {
                if (s.privateDnsActive == true) {
                    add("$plan: Private DNS was on; Android can send lookups encrypted to the Private DNS server, outside TraceVeil's capture.")
                }
                h?.hiddenDnsPackets?.takeIf { it > 0 }?.let {
                    add("$plan: $it packets used DNS over TLS (port 853) or DNS over TCP, which TraceVeil cannot read; the names in them are not in this evidence.")
                }
                h?.eventsDropped?.takeIf { it > 0 }?.let {
                    add("$plan: $it lookups were dropped because the event buffer was full" + (h.eventBufferCapacity?.let { c -> " ($c events)" } ?: "") + ".")
                }
                unreadableDns(h)?.takeIf { it > 0 }?.let { add("$plan: $it DNS packets could not be read as questions.") }
            }.map { s.plan to it }
        }
        if (lines.isEmpty()) return null
        return Finding(
            checkId = CheckId.V1, severity = Severity.INFO, evidenceType = EvidenceType.OBSERVED, title = CheckId.V1.title,
            explanation = "Capture health shows that some lookups were not visible to TraceVeil. Findings cover only the " +
                "lookups that were captured; the hidden ones are unknown, not absent, so a check without a finding here is " +
                "not a clean result for them. Turning Private DNS off and re-running reduces this. Lookups an app resolves " +
                "itself over HTTPS (DNS over HTTPS) leave no trace in this capture at all and cannot be counted.",
            evidence = lines.map { it.second }.distinct().take(RulesEngine.MAX_EVIDENCE),
            plans = lines.map { it.first }.distinct(),
            dpdpRefs = emptyList(),
            penaltyBand = PenaltyBand.NONE,
        )
    }

    /** Plain-DNS packets that could not be read; null when the build did not break unparsed packets down. */
    private fun unreadableDns(h: CaptureHealth?): Long? {
        val total = h?.malformedPackets ?: return null
        val hidden = h.hiddenDnsPackets ?: return null
        val nonDns = h.nonDnsPackets ?: return null
        return (total - hidden - nonDns).takeIf { it >= 0 }
    }

    // --- shared ----------------------------------------------------------------------------------------------------

    private fun consentRelation(s: AuditSession, e: NetEvent): String {
        val choice = s.consentChoice
        return when {
            choice == null && e.phase == Phase.NO_CONSENT_SEEN -> "no consent screen seen in this capture"
            choice == null -> "the user had not chosen yet"
            e.tNanos < choice.tNanos -> "${secs(choice.tNanos - e.tNanos)} s before the consent choice"
            else -> "${secs(e.tNanos - choice.tNanos)} s after the user chose ${choice.kind.name.lowercase()}"
        }
    }

    private fun captured(s: AuditSession): String? = (s.endedAtNanos - s.startedAtNanos).takeIf { it > 0 }?.let { "${secs(it)} s" }

    private fun secs(nanos: Long) = String.format(Locale.US, "%.1f", nanos / 1e9)

    private fun clip(s: String) = s.replace(Regex("\\s+"), " ").trim().let { if (it.length <= 60) it else it.take(59) + "…" }

    companion object {
        private val SCREEN_KINDS = setOf(ConsentKind.SCREEN_APPEARED, ConsentKind.MANUAL_MARK)

        /** A consent screen, a hand-marked consent moment or a recorded choice: the notice was on screen by then. */
        private val NOTICE_KINDS = SCREEN_KINDS + setOf(ConsentKind.ACCEPT, ConsentKind.REJECT)

        private val NOTIFICATION_CUES = listOf("notification", "सूचनाएं भेजने", "सूचनाएँ भेजने", "नोटिफ़िकेशन", "नोटिफिकेशन", "ಅಧಿಸೂಚನೆ")

        /** Android's notification permission (POST_NOTIFICATIONS) dialog, from its visible text. */
        fun isNotificationRequest(text: String): Boolean = text.lowercase().let { t -> NOTIFICATION_CUES.any { it in t } }
    }
}
