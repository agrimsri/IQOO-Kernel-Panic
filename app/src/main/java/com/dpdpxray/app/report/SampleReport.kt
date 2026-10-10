package com.dpdpxray.app.report

import com.dpdpxray.core.model.AuditSession

/** Applies only to in-app generated samples, never to a live audit report. */
internal object SampleReport {
    fun annotate(markdown: String, sessions: List<AuditSession>): String = buildString {
        appendLine("**Synthetic sample evidence — not a live audit.**")
        appendLine()
        sessions.flatMap { it.notes }.distinct().forEach { appendLine("- $it") }
        appendLine()
        append(markdown)
    }
}
