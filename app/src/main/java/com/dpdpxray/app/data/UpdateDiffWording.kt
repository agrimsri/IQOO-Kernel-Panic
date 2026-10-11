package com.dpdpxray.app.data

import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.update.AppVersion
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The "Before / after" card when the app was updated between two checks. Everything it says comes from comparing the
 * two saved audits: "Fixed in this update" means "recorded by the old check, not by the new one", which the caveat says.
 */
object UpdateDiffWording {
    const val FIXED = "Fixed in this update"
    const val NEW = "New in this update"
    const val STILL = "Still there"
    const val CAVEAT = "\"Fixed\" means not seen in this check of the new version. Capture coverage can differ, so it is not proof " +
        "that it was fixed or that the app is safe."

    fun title(u: VersionUpdate) = "Update ${AppVersion.change(u.from, u.to)}"

    /** "8 → 51 (+43)"; empty when a score is missing (an available comparison always has both). */
    fun score(c: AuditComparisonResult.Available): String {
        val before = c.previousScore ?: return ""
        val after = c.currentScore ?: return ""
        val d = c.scoreDelta
        return "$before → $after (${if (d >= 0) "+$d" else "$d"})"
    }

    fun summary(u: VersionUpdate, c: AuditComparisonResult.Available) = listOf(title(u), score(c)).filter { it.isNotEmpty() }.joinToString(" · ")

    /** One line per check, in check order: "C2 · Tracker hosts looked up after the user refused"; "None" when empty. */
    fun lines(checks: Set<CheckId>): List<String> =
        if (checks.isEmpty()) listOf("None") else checks.sortedBy { it.ordinal }.map { "${it.code} · ${it.title}" }

    /** One line near the top of the result, pointing to the full comparison in the "Before / after" card. */
    fun banner(u: VersionUpdate, c: AuditComparisonResult?): String {
        val scores = (c as? AuditComparisonResult.Available)?.let { score(it) }?.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""
        return "This check is of version ${u.to.label()}. Your previous check was of version ${u.from.label()}$scores. See Before / after below."
    }

    fun honest(u: VersionUpdate, previousTimeMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "Compared with your check of version ${u.from.label()} on ${date(previousTimeMs, zone)}. Both checks are saved; nothing was overwritten."

    /** "Share the update report": the same facts as the card, as Markdown. */
    fun markdown(appLabel: String, u: VersionUpdate, c: AuditComparisonResult.Available, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        appendLine("# $appLabel: ${title(u)}")
        appendLine()
        appendLine("Score: ${score(c)}")
        appendLine()
        listOf(FIXED to c.noLongerRecorded, NEW to c.newlyRecorded, STILL to c.stillRecorded).forEach { (heading, checks) ->
            appendLine("## $heading")
            lines(checks).forEach { appendLine("- $it") }
            appendLine()
        }
        appendLine(honest(u, c.previousTimeMs, zone))
        appendLine()
        appendLine(CAVEAT)
        appendLine("Made with TraceVeil on this phone from two saved checks.")
    }

    private fun date(ms: Long, zone: ZoneId): String =
        DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).format(Instant.ofEpochMilli(ms).atZone(zone))
}
