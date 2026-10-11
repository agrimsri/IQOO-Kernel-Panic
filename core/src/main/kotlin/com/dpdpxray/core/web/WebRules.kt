package com.dpdpxray.core.web

import com.dpdpxray.core.consent.ConsentLexicon
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.Finding
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.PenaltyBand
import com.dpdpxray.core.model.Phase
import com.dpdpxray.core.model.RejectOption
import com.dpdpxray.core.model.ScreenAudit
import com.dpdpxray.core.model.Severity
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.rules.ScoreCalculator
import com.dpdpxray.core.rules.SessionClassifier
import com.dpdpxray.core.trackers.TrackerCatalog
import java.util.Locale

/**
 * The rules for a website check, with the same check ids, severities and score as an app audit wherever a check applies,
 * and wording that says what was actually seen: requests with full addresses in TraceVeil's own browser, not DNS lookups.
 *
 * Applies: V0 (almost nothing seen), C1 (tracker requests before any choice), NC (trackers but no banner found), C2 (tracker
 * requests after Reject), the banner checks C3 to C8 (the app rules themselves, through [screenChecks]), C15 (no policy
 * named on the banner, informational), and the website-only W1 and W2 (tracking cookies stored before consent, or after
 * Reject). Does not apply: C9 (no child-facing declaration for a site), C10 (no advertising identifier in a browser), C11
 * (no DNS replies), C12 (no background step), C13 (no manifest), C14 (no Android permission dialog), U1 (every plan starts
 * from a cleared browser), S1 (no app publisher). The score is [ScoreCalculator], unchanged.
 */
class WebRules(
    private val catalog: TrackerCatalog,
    private val trackers: WebTrackers = WebTrackers.default,
    /** The app rules' banner checks (RulesEngine's C3 to C8), so a website banner is judged by the same rules. */
    private val screenChecks: (ScreenAudit, List<AuditPlan>) -> List<Finding>,
) {
    private val classifier = SessionClassifier(catalog)

    private class Plan(val session: AuditSession, val events: List<NetEvent>) {
        val web: WebEvidence get() = session.web!!
        val choice get() = session.consentChoice
        /** The request behind each classified event (both are in time order and one-to-one), or null if they differ. */
        val requests: List<WebRequest>? = web.requests.takeIf { it.size == events.size }
    }

    fun evaluate(sessions: List<AuditSession>): AuditResult {
        val plans = sessions.map { Plan(it, classifier.classify(it)) }
        val findings = mutableListOf<Finding>()
        val all = plans.flatMap { it.events }
        if (all.size < RulesEngine.MIN_EVENTS && all.none { it.category.isTracking }) findings += lowVisibility(plans)
        c1(plans)?.let { findings += it }
        noBanner(plans)?.let { findings += it }
        c2(plans)?.let { findings += it }
        val screened = sessions.filter { it.screenAudit?.isConsentScreen == true }
        val screen = screened.firstNotNullOfOrNull { s -> s.screenAudit?.takeIf { it.rejectOption != RejectOption.UNKNOWN || it.preTicked != null } }
            ?: screened.firstOrNull()?.screenAudit
        if (screen != null) findings += screenChecks(screen, screened.map { it.plan }.distinct()).map { withWebEvidence(it, plans) }
        noPolicyNamed(plans)?.let { findings += it }
        w1(plans)?.let { findings += it }
        w2(plans)?.let { findings += it }
        val (score, band) = ScoreCalculator.compute(findings)
        return AuditResult(findings.sortedWith(compareBy({ it.severity.ordinal }, { it.checkId.ordinal })), score, band)
    }

    // --- Requests ------------------------------------------------------------------------------------------------------

    private fun preConsent(e: NetEvent) = e.phase == Phase.BEFORE_CONSENT || e.phase == Phase.NO_CONSENT_SEEN

    private data class Hit(val plan: Plan, val index: Int) {
        val event: NetEvent get() = plan.events[index]
        val request: WebRequest? get() = plan.requests?.getOrNull(index)
    }

    private fun hits(plans: List<Plan>, test: (NetEvent) -> Boolean): List<Hit> =
        plans.flatMap { p -> p.events.indices.filter { i -> p.events[i].category.isTracking && test(p.events[i]) }.map { Hit(p, it) } }

    private fun c1(plans: List<Plan>): Finding? {
        val hits = hits(plans, ::preConsent)
        if (hits.isEmpty()) return null
        val entities = hits.mapNotNull { it.event.entity }.distinct()
        return Finding(
            checkId = CheckId.C1, severity = Severity.HIGH, evidenceType = EvidenceType.OBSERVED, title = WEB_C1_TITLE,
            explanation = "The page asked TraceVeil's browser to contact hosts the tracker catalogue lists for ${entities.joinToString()} " +
                "before any consent choice was made. $REQUEST_MATCH Under the DPDP Act, personal data may be processed only with " +
                "consent (or a legitimate use), and consent must be given by a clear affirmative action before processing starts.",
            evidence = lines(hits),
            plans = hits.map { it.plan.session.plan }.distinct(),
            dpdpRefs = listOf("s4(1)", "s6(1)"),
            penaltyBand = PenaltyBand.RESIDUAL_50,
            entities = entities,
        )
    }

    private fun noBanner(plans: List<Plan>): Finding? {
        if (plans.any { it.session.consentScreenSeen }) return null
        val trackers = plans.flatMap { p -> p.events.filter { it.category.isTracking } }
        if (trackers.isEmpty()) return null
        return Finding(
            checkId = CheckId.NC, severity = Severity.HIGH, evidenceType = EvidenceType.OBSERVED, title = WEB_NC_TITLE,
            explanation = "No consent banner was found in the parts of the page TraceVeil can read during this check " +
                "(${plans.joinToString { it.session.plan.label + (captured(it.session)?.let { c -> ", $c" } ?: "") }}), yet the page " +
                "contacted hosts the tracker catalogue lists for ${trackers.mapNotNull { it.entity }.distinct().joinToString()}. " +
                "A banner shown later, only after scrolling, or inside a frame TraceVeil does not recognise would be missed. DPDP " +
                "requires a notice (s.5) and valid consent (s.6) before processing, and the Data Fiduciary must be able to prove both (s.6(10)).",
            evidence = plans.map { p ->
                "No consent banner found in ${p.session.plan.label}" + (captured(p.session)?.let { " during the $it watched" } ?: "") +
                    "; ${p.web.requests.size} requests recorded."
            },
            plans = plans.map { it.session.plan }.distinct(),
            dpdpRefs = listOf("s5(1)", "s6(1)", "s6(10)"),
            penaltyBand = PenaltyBand.RESIDUAL_50,
        )
    }

    private fun c2(plans: List<Plan>): Finding? {
        val hits = hits(plans) { it.phase == Phase.AFTER_REJECT }
        if (hits.isEmpty()) return null
        val entities = hits.mapNotNull { it.event.entity }.distinct()
        return Finding(
            checkId = CheckId.C2, severity = Severity.CRITICAL, evidenceType = EvidenceType.OBSERVED, title = WEB_C2_TITLE,
            explanation = "The user refused consent (${refusal(hits.first().plan)}), but the page still asked TraceVeil's browser to " +
                "contact hosts the tracker catalogue lists for ${entities.joinToString()}. $REQUEST_MATCH Consent must be free and " +
                "specific; processing based on a refused consent has no lawful ground.",
            evidence = lines(hits),
            plans = hits.map { it.plan.session.plan }.distinct(),
            dpdpRefs = listOf("s4(1)", "s6(1)"),
            penaltyBand = PenaltyBand.RESIDUAL_50,
            entities = entities,
        )
    }

    /** One line per plan and host (its first matching request), with the number of further matching requests. */
    private fun lines(hits: List<Hit>): List<String> = hits.groupBy { it.plan.session.id to it.event.hostname }.values
        .map { group -> group.minBy { it.event.tNanos } to group.size - 1 }
        .sortedBy { it.first.event.tNanos }
        .map { (hit, more) -> requestLine(hit) + if (more > 0) " (+$more more to this host)" else "" }
        .take(RulesEngine.MAX_EVIDENCE)

    private fun requestLine(h: Hit): String {
        val e = h.event
        val s = h.plan.session
        val r = h.request
        val what = r?.let { "${it.method} ${it.type.label} ${clip(it.url, 140)}" } ?: e.hostname
        val who = e.entity?.let { " ($it, catalogue match: ${e.category.name.lowercase(Locale.ROOT).replace('_', ' ')}" + (if (r?.pathEntity != null) ", by its documented path" else "") + ")" } ?: ""
        return "$what$who — ${secs(e.tNanos - s.startedAtNanos)} s after the page started loading, ${relation(h.plan, e.tNanos)} [${s.plan.label}]"
    }

    private fun relation(p: Plan, t: Long): String {
        val choice = p.choice
        return when {
            choice == null && !p.session.consentScreenSeen -> "no consent banner found in this plan"
            choice == null -> "the user had not chosen yet"
            t < choice.tNanos -> "${secs(choice.tNanos - t)} s before the consent choice"
            else -> "${secs(t - choice.tNanos)} s after the user chose ${choice.kind.name.lowercase(Locale.ROOT)}"
        }
    }

    private fun refusal(p: Plan): String {
        val c = p.choice ?: return "Reject"
        val who = if (p.web.agentTaps) "TraceVeil tapped" else "the person tapped"
        return c.nodeText?.takeIf { it.isNotBlank() }?.let { "$who \"${clip(it, 40)}\"" } ?: "$who Reject"
    }

    // --- Banner ----------------------------------------------------------------------------------------------------------

    /** The app's banner findings with what the page showed added to their evidence. */
    private fun withWebEvidence(f: Finding, plans: List<Plan>): Finding {
        val banner = plans.firstNotNullOfOrNull { it.web.banner?.takeIf { b -> b.detection != WebBannerDetection.FRAME_ONLY } }
        val extra = when (f.checkId) {
            CheckId.C3 -> banner?.let { b -> WebBanners.preTicked(b).map { "Ticked before any choice: \"${clip(it.label, 60)}\"" + if (it.secondLayer) " (in the banner's settings layer)" else "" } }.orEmpty()
            CheckId.C4 -> buildList {
                banner?.let { b ->
                    val shown = WebBanners.shown(b).filter { WebBanners.kindOf(it) != null }
                    add("Options on the banner's first screen: " + shown.joinToString { "\"${clip(it.text, 40)}\"" }.ifEmpty { "none recognised" } + ".")
                }
                plans.filter { it.session.plan == AuditPlan.REJECT }.forEach { p ->
                    val steps = p.session.consentEvents.filter { it.kind == ConsentKind.SETTINGS }.mapNotNull { it.nodeText }
                    val choice = p.choice?.takeIf { it.kind == ConsentKind.REJECT }
                    when {
                        choice != null && steps.isNotEmpty() -> add("Reject was reached only through ${steps.joinToString(" → ") { "\"${clip(it, 40)}\"" }} → \"${clip(choice.nodeText.orEmpty(), 40)}\".")
                        choice == null -> add("The Reject plan could not refuse: " + (p.session.notes.firstOrNull { it.startsWith(NO_REJECT_NOTE) } ?: "no refusal was recorded") + ".")
                        else -> {}
                    }
                }
            }
            else -> emptyList()
        }
        return if (extra.isEmpty()) f else f.copy(evidence = (f.evidence + extra).distinct().take(RulesEngine.MAX_EVIDENCE))
    }

    private fun noPolicyNamed(plans: List<Plan>): Finding? {
        val banners = plans.mapNotNull { p -> p.web.banner?.takeIf { it.detection != WebBannerDetection.FRAME_ONLY }?.let { p to it } }
        if (banners.isEmpty()) return null
        val texts = banners.map { (_, b) -> b.texts + b.controls.map { it.text } }
        if (texts.all { it.isEmpty() } || texts.any { ConsentLexicon.mentionsPrivacyPolicy(it) }) return null
        return Finding(
            checkId = CheckId.C15, severity = Severity.INFO, evidenceType = EvidenceType.OBSERVED, title = CheckId.C15.title,
            explanation = "The consent banner's text and links, as TraceVeil read them, name no privacy policy, privacy notice or " +
                "cookie policy. A link at the point of consent lets people read the full notice before they choose. DPDP does not " +
                "state this requirement for the banner itself, so this is informational and the score is unchanged. Only the " +
                "banner was read: a policy linked from its settings layer, or elsewhere on the page, is not seen.",
            evidence = banners.map { (p, b) -> "Banner${b.cmp?.let { " ($it)" } ?: ""}: ${b.texts.size} text lines and ${b.controls.size} links or buttons read; none names a policy [${p.session.plan.label}]" }.distinct(),
            plans = banners.map { it.first.session.plan }.distinct(),
            dpdpRefs = emptyList(),
            penaltyBand = PenaltyBand.NONE,
        )
    }

    // --- Cookies ---------------------------------------------------------------------------------------------------------

    private fun w1(plans: List<Plan>): Finding? {
        val hits = plans.flatMap { p ->
            val limit = p.choice?.tNanos ?: Long.MAX_VALUE
            p.web.cookies.filter { it.trackerEntity != null && it.firstSeenNanos < limit }.map { p to it }
        }
        if (hits.isEmpty()) return null
        val entities = hits.mapNotNull { it.second.trackerEntity }.distinct()
        return Finding(
            checkId = CheckId.W1, severity = Severity.INFO, evidenceType = EvidenceType.OBSERVED, title = CheckId.W1.title,
            explanation = "Before any consent choice, TraceVeil's browser already held cookies of ${entities.joinToString()}: cookies " +
                "kept for a host the tracker catalogue lists, or with a name the vendor documents for its tracking script. Each plan " +
                "started with the browser's cookies cleared, so the page set them during this visit. Storing a tracking identifier on " +
                "the device is a separate act from contacting the tracker, but the requests that set these cookies are already counted under " +
                "C1, so this note does not change the score (a tracker served from the site's own domain shows up only here). " +
                "$COOKIE_CAVEAT DPDP needs consent before processing (s.6(1)); rules on storing information on a person's device, " +
                "such as the EU ePrivacy rules, fit more closely.",
            evidence = hits.sortedBy { it.second.firstSeenNanos }.map { (p, c) -> cookieLine(p, c, before = true) }.distinct().take(RulesEngine.MAX_EVIDENCE),
            plans = hits.map { it.first.session.plan }.distinct(),
            dpdpRefs = listOf("s6(1)"),
            penaltyBand = PenaltyBand.NONE,
            entities = entities,
        )
    }

    private fun w2(plans: List<Plan>): Finding? {
        val hits = plans.flatMap { p ->
            val choice = p.choice?.takeIf { it.kind == ConsentKind.REJECT } ?: return@flatMap emptyList()
            val from = choice.tNanos - CHOICE_TOLERANCE_NANOS
            p.web.cookies.filter { c ->
                c.trackerEntity != null && ((c.absentSinceNanos != null && c.absentSinceNanos >= from) || (c.rewrittenAfterNanos != null && c.rewrittenAfterNanos >= from))
            }.map { p to it }
        }
        if (hits.isEmpty()) return null
        val entities = hits.mapNotNull { it.second.trackerEntity }.distinct()
        return Finding(
            checkId = CheckId.W2, severity = Severity.INFO, evidenceType = EvidenceType.OBSERVED, title = CheckId.W2.title,
            explanation = "After the user refused consent (${refusal(hits.first().first)}), cookies of ${entities.joinToString()} were " +
                "written to TraceVeil's browser, or written again with a new value. TraceVeil read the cookie store just before the " +
                "tap and again afterwards; a cookie missing from (or different in) a reading that started at most one second before " +
                "the tap is counted. Tracker requests after Reject are counted under C2, so this note does not change the score. $COOKIE_CAVEAT",
            evidence = hits.map { (p, c) -> cookieLine(p, c, before = false) }.distinct().take(RulesEngine.MAX_EVIDENCE),
            plans = hits.map { it.first.session.plan }.distinct(),
            dpdpRefs = listOf("s6(1)"),
            penaltyBand = PenaltyBand.NONE,
            entities = entities,
        )
    }

    private fun cookieLine(p: Plan, c: WebCookie, before: Boolean): String {
        val s = p.session
        val party = if (c.thirdParty) "third-party, kept for ${c.hosts.firstOrNull() ?: c.site}" else "first-party on ${c.site}"
        val basis = when (c.trackerBasis) {
            WebCookieBasis.TRACKER_HOST -> "a ${c.trackerEntity} host"
            WebCookieBasis.DOCUMENTED_NAME -> "named as the vendor documents for ${c.trackerEntity}"
            null -> c.trackerEntity.orEmpty()
        }
        val whenText = if (before) {
            "present ${secs(c.firstSeenNanos - s.startedAtNanos)} s after the page started loading, ${relation(p, c.firstSeenNanos)}"
        } else {
            val choice = p.choice!!
            val t = listOfNotNull(c.absentSinceNanos, c.rewrittenAfterNanos).filter { it >= choice.tNanos - CHOICE_TOLERANCE_NANOS }.min()
            (if (c.rewrittenAfterNanos != null && c.rewrittenAfterNanos >= choice.tNanos - CHOICE_TOLERANCE_NANOS) "written again" else "set") +
                " after ${secs(t - s.startedAtNanos)} s (the Reject tap was at ${secs(choice.tNanos - s.startedAtNanos)} s)" +
                (if (!c.presentAtEnd) "; removed again before the plan ended" else "")
        }
        return "${c.name} ($party; $basis; value of ${c.valueLength} characters, not stored) — $whenText [${s.plan.label}]"
    }

    private fun lowVisibility(plans: List<Plan>): Finding {
        val errors = plans.flatMap { it.web.errors }.distinct()
        return Finding(
            checkId = CheckId.V0, severity = Severity.INFO, evidenceType = EvidenceType.OBSERVED, title = WEB_V0_TITLE,
            explanation = "The page made almost no requests that TraceVeil's browser could see, so this check cannot judge the site. " +
                "The page may not have loaded (no connection, an error page, a typo in the address) or may have refused TraceVeil's " +
                "browser. Check the address and the connection, then run the check again." +
                (if (errors.isNotEmpty()) " The browser reported: ${errors.joinToString("; ")}." else ""),
            evidence = plans.map { "${it.session.plan.label}: ${it.web.requests.size} requests" },
            plans = plans.map { it.session.plan }.distinct(),
            dpdpRefs = emptyList(),
            penaltyBand = PenaltyBand.NONE,
        )
    }

    private fun captured(s: AuditSession): String? = (s.endedAtNanos - s.startedAtNanos).takeIf { it > 0 }?.let { "${secs(it)} s" }

    private fun secs(nanos: Long) = String.format(Locale.US, "%.1f", nanos / 1e9)

    private fun clip(s: String, max: Int): String = s.replace(Regex("\\s+"), " ").trim().let { if (it.length <= max) it else it.take(max - 1) + "…" }

    companion object {
        /** A cookie missing from a reading that started this long before the tap or later counts as set after the tap. */
        const val CHOICE_TOLERANCE_NANOS = 1_000_000_000L

        /** Note the runner writes when a Reject plan found no way to refuse (C4 evidence reads it). */
        const val NO_REJECT_NOTE = "No Reject option"

        const val WEB_C1_TITLE = "Tracker hosts contacted before consent"
        const val WEB_C2_TITLE = "Tracker hosts contacted after the user refused"
        const val WEB_NC_TITLE = "No consent banner seen during the check"
        const val WEB_V0_TITLE = "Almost nothing was seen — result inconclusive"

        const val REQUEST_MATCH = "Each request is a catalogue match: the page asked to contact a host the tracker catalogue lists " +
            "as a tracking endpoint (or a documented tracking path on a shared host). TraceVeil saw the full address in its own " +
            "browser, not the request body or the reply, so it is not proof of what personal data was sent."

        const val COOKIE_CAVEAT = "Cookie values are not stored or shown; only their length."
    }
}
