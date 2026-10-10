package com.dpdpxray.app.report

import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.app.audit.AuditPlanCoverage
import com.dpdpxray.core.report.MarkdownReport

internal object EvidenceReport {
    fun annotate(markdown: String, sessions: List<AuditSession>): String {
        val localScope = "Audit analysis and report generation run on this phone. DNS capture relays the target app's DNS queries. UID byte estimates are not proof of zero uploads; this audit does not inspect encrypted application payloads."
        val sanitized = markdown.replace(MarkdownReport.PRIVACY_LINE, localScope)
        if (sanitized.startsWith("## Audit coverage\n")) return sanitized
        val notes = AuditPlanCoverage.gaps(sessions) + listOfNotNull(AuditPlanCoverage.freshStateHint(sessions))
        val coverage = if (notes.isEmpty()) "No missing planned consent choice was detected. This does not certify app safety or complete network visibility."
            else notes.joinToString("\n\n")
        return "## Audit coverage\n\n$coverage\n\n$localScope\n\n---\n\n$sanitized"
    }
}
