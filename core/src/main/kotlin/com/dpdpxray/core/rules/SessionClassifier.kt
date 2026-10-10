package com.dpdpxray.core.rules

import com.dpdpxray.core.audit.PhaseAssigner
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.Category
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.trackers.AppOwner
import com.dpdpxray.core.trackers.AppOwners
import com.dpdpxray.core.trackers.TrackerCatalog

/**
 * Classifies one session's lookups in context: consent phase, catalogue category (a stored entity wins, with its current
 * catalogue category), and
 * whether a catalogued tracker belongs to the same company that publishes the audited app. Same-company lookups keep
 * their entity but get [Category.FIRST_PARTY_SAME_COMPANY], so no consent check counts them as third-party tracking.
 * The rules engine uses this; screens can use it to show the same labels.
 */
class SessionClassifier(private val catalog: TrackerCatalog, private val owners: AppOwners = AppOwners.default) {

    /** The audited app's publisher, or null when no cited record covers its package. */
    fun ownerOf(session: AuditSession): AppOwner? = owners.ownerOf(session.targetPackage)

    /** Phase-assigned, classified lookups of [session], in time order. */
    fun classify(session: AuditSession): List<NetEvent> {
        val owner = ownerOf(session)
        return PhaseAssigner.assign(session.netEvents, session.consentEvents).map { classify(it, owner) }
    }

    fun classify(event: NetEvent, owner: AppOwner?): NetEvent {
        val stored = event.entity?.let { catalog.entryFor(it) }
        val e = when {
            // A stored catalogue entity takes the catalogue's current category, so corrections (facebook.com is now a
            // company service, not a tracker) also apply to audits recorded before them.
            stored != null -> if (stored.category == event.category) event else event.copy(category = stored.category)
            event.entity != null -> event
            else -> catalog.classify(event.hostname, event.cnameChain)
                .let { event.copy(entity = it.entity, category = it.category, disguisedVia = event.disguisedVia ?: it.disguisedVia) }
        }
        return if (owner != null && e.category.isTracking && companyOf(e) == owner.company) e.copy(category = Category.FIRST_PARTY_SAME_COMPANY) else e
    }

    /**
     * The company group behind an event: its stored or catalogue entity's group, otherwise the group the catalogue gives
     * the host (older audits stored entity names that are no longer catalogue names).
     */
    fun companyOf(event: NetEvent): String? =
        event.entity?.let { catalog.entryFor(it)?.company } ?: catalog.classify(event.hostname, event.cnameChain).company

    /** The catalogue's own category for an event's entity, before any same-company context. */
    fun catalogueCategory(event: NetEvent): Category =
        event.entity?.let { catalog.entryFor(it)?.category } ?: catalog.classify(event.hostname, event.cnameChain).category

    companion object {
        fun sameCompanyLabel(company: String) = "Same company as the app ($company)"
    }
}
