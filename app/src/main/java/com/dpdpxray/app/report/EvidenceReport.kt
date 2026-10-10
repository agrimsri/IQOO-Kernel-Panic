package com.dpdpxray.app.report

import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.app.audit.AuditPlanCoverage
import com.dpdpxray.app.audit.AuditTimingPolicy
import com.dpdpxray.core.report.MarkdownReport

internal object EvidenceReport {
    const val LOCAL_SCOPE = "Audit analysis and report generation run on this phone. DNS capture relays the target app's DNS queries. UID byte estimates are not proof of zero uploads; this audit does not inspect encrypted application payloads."
    const val NO_GAPS = "No missing planned consent choice was detected. This does not certify app safety or complete network visibility."

    /** Coverage statements shown before any score: missing choices, fresh-state hint and capture timing (live audits only). */
    fun coverageNotes(sessions: List<AuditSession>, timingProfile: String?, sample: Boolean): List<String> =
        AuditPlanCoverage.gaps(sessions) + listOfNotNull(AuditPlanCoverage.freshStateHint(sessions)) +
            listOfNotNull(if (sample) null else AuditTimingPolicy.coverageHint(timingProfile)) +
            listOfNotNull(backgroundHint(sessions))

    /** Only for plans that recorded a confirmed background moment; older audits and Fast Preview have none. */
    fun backgroundHint(sessions: List<AuditSession>): String? {
        val plans = sessions.filter { it.backgroundStartedNanos != null }.map { it.plan.label }.distinct()
        if (plans.isEmpty()) return null
        return "Background check: after the observation window the app was sent to the background and capture continued " +
            "(${plans.joinToString()}). Lookups in that window are marked in the network timeline."
    }

    fun annotate(markdown: String, sessions: List<AuditSession>, timingProfile: String? = null, sample: Boolean = false): String {
        val sanitized = markdown.replace(MarkdownReport.PRIVACY_LINE, LOCAL_SCOPE)
        val timing = if (sample) null else AuditTimingPolicy.coverageHint(timingProfile)
        if (sanitized.startsWith("## Audit coverage\n")) {
            // Older saved reports already have a coverage section but no timing provenance.
            // Add it once at render/export time without changing their measured evidence.
            return if (timing == null || sanitized.contains(timing)) sanitized
            else sanitized.replaceFirst("## Audit coverage\n\n", "## Audit coverage\n\n$timing\n\n")
        }
        val notes = coverageNotes(sessions, timingProfile, sample)
        val coverage = if (notes.isEmpty()) NO_GAPS else notes.joinToString("\n\n")
        return "## Audit coverage\n\n$coverage\n\n$LOCAL_SCOPE\n\n---\n\n$sanitized"
    }
}
