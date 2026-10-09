package com.dpdpxray.core.notice

import com.dpdpxray.core.model.AuditPlan

/** A tracker domain looked up after the user declined, grouped by host and the package that looked it up. */
data class NoticeContact(
    val hostname: String,
    /** Catalogue name of the service the domain belongs to; null when the event carries none. Not a confirmed recipient. */
    val entity: String?,
    /** From the Reject to the first such lookup, on the session clock. */
    val firstDelayNanos: Long,
    val lookups: Int,
    /** Set when another package (e.g. Play services) made the lookup, not the audited app. */
    val viaPackage: String? = null,
    /** Tracker domain reached through an observed alias, when that alias decided the classification. */
    val disguisedVia: String? = null,
)

/** One observed Reject and the tracker lookups that followed it in the same session. */
data class DeclinedChoice(
    val sessionId: String,
    val plan: AuditPlan,
    val rejectNanos: Long,
    /** Wall-clock time of the Reject; null when the session has no start time to anchor it. */
    val rejectEpochMs: Long?,
    /** Button text the watcher recorded, when it reported the tap. */
    val choiceLabel: String?,
    /** True when the watcher inferred the Reject from the dialog closing instead of receiving the tap. */
    val inferred: Boolean,
    val contacts: List<NoticeContact>,
)

/**
 * A draft request from the user to the app's developer. It states only what was observed, asks the developer to
 * confirm the facts and cites passages by their `passages.json` ids. TraceVeil never sends it: the user reviews it,
 * fills in their name and contact, and chooses whether to share it.
 */
data class RightsNotice(
    val appLabel: String,
    val packageName: String,
    val choices: List<DeclinedChoice>,
    val citedIds: List<String>,
    val text: String,
)
