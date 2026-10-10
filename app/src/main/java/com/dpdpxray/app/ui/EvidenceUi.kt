package com.dpdpxray.app.ui

import com.dpdpxray.core.dpdp.DpdpCorpus
import com.dpdpxray.core.audit.PhaseAssigner
import com.dpdpxray.core.model.*
import com.dpdpxray.core.notice.RightsNotice
import com.dpdpxray.core.notice.RightsNoticeBuilder
import com.dpdpxray.core.rules.SessionClassifier
import com.dpdpxray.core.trackers.TrackerCatalog
import java.time.ZoneId

internal class EvidenceUi(private val catalog: TrackerCatalog, private val corpus: DpdpCorpus, private val zone: ZoneId = ZoneId.systemDefault()) {
    fun classify(event: NetEvent): NetEvent {
        val info = catalog.classify(event.hostname, event.cnameChain)
        // Preserve recorded attribution, but retain an observed alias even for an already classified event.
        return if (event.entity != null) event.copy(disguisedVia = event.disguisedVia ?: info.disguisedVia)
        else event.copy(entity = info.entity, category = info.category, disguisedVia = event.disguisedVia ?: info.disguisedVia)
    }

    /** Same labels as the rules engine: a catalogued tracker owned by the audited app's own publisher is first-party. */
    fun timeline(session: AuditSession): List<NetEvent> {
        val context = SessionClassifier(catalog)
        val owner = context.ownerOf(session)
        return PhaseAssigner.assign(session.netEvents, session.consentEvents).map(::classify)
            .map { e -> if (owner != null && e.category.isTracking && context.companyOf(e) == owner.company) e.copy(category = Category.FIRST_PARTY_SAME_COMPANY) else e }
            .sortedBy { it.tNanos }
    }

    fun aliasLabel(event: NetEvent): String? = observedAliasLabel(event)

    fun aliasesFor(finding: Finding, sessions: List<AuditSession>): List<String> = sessions
        .filter { it.plan in finding.plans }
        .flatMap(::timeline)
        .filter { it.entity in finding.entities }
        .filter { when (finding.checkId) {
            CheckId.C1, CheckId.C10 -> it.phase == Phase.BEFORE_CONSENT || it.phase == Phase.NO_CONSENT_SEEN
            CheckId.C2 -> it.phase == Phase.AFTER_REJECT
            else -> true
        } }
        .mapNotNull(::aliasLabel).distinct()

    fun notice(check: CheckId, result: AuditResult, sessions: List<AuditSession>): RightsNotice? =
        if (check == CheckId.C2) RightsNoticeBuilder(catalog, corpus, zone).build(result, sessions) else null
}

internal fun observedAliasLabel(event: NetEvent): String? = event.disguisedVia?.takeIf { it.isNotBlank() }
    ?.let { "${event.hostname} → $it (alias observed)" }
