package com.dpdpxray.cli

import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.Finding
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.Severity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A finding present in both releases, with what changed about it. */
data class Unchanged(val old: Finding, val new: Finding) {
    val addedEntities: List<String> get() = new.entities - old.entities.toSet()
    val removedEntities: List<String> get() = old.entities - new.entities.toSet()
    val changes: List<String>
        get() = buildList {
            if (old.severity != new.severity) add("severity ${old.severity.label} → ${new.severity.label}")
            if (old.evidenceType != new.evidenceType) add("evidence ${old.evidenceType.name} → ${new.evidenceType.name}")
            if (addedEntities.isNotEmpty()) add("now also ${addedEntities.joinToString()}")
            if (removedEntities.isNotEmpty()) add("no longer ${removedEntities.joinToString()}")
        }
}

/**
 * Release-to-release comparison of two bundles, both re-evaluated with this build's rules so the comparison is not
 * skewed by rule changes between the two exports. Findings are matched by check id.
 */
class BundleDiff(val old: LoadedBundle, val new: LoadedBundle, val oldResult: AuditResult, val newResult: AuditResult) {
    private val gone: List<Finding> = oldResult.findings.filter { o -> newResult.findings.none { it.checkId == o.checkId } }

    /** Old findings the new capture could not have observed (a plan or choice is missing): not resolved, NOT ESTABLISHED. */
    val notEstablished: List<Pair<Finding, String>> = gone.mapNotNull { f -> Coverage.untested(f.checkId, new.bundle.sessions)?.let { f to it } }

    /** Old findings the new capture could observe and did not see. */
    val resolved: List<Finding> = gone.filter { f -> notEstablished.none { it.first.checkId == f.checkId } }
    val added: List<Finding> = newResult.findings.filter { n -> oldResult.findings.none { it.checkId == n.checkId } }
    val unchanged: List<Unchanged> = newResult.findings.mapNotNull { n -> oldResult.findings.firstOrNull { it.checkId == n.checkId }?.let { Unchanged(it, n) } }

    val scoreDelta: Int? get() = if (oldResult.score != null && newResult.score != null) newResult.score!! - oldResult.score!! else null

    /** Why the new release is worse; empty means no regression. Info-level findings alone are not a regression. */
    val regressions: List<String>
        get() = buildList {
            added.filter { it.severity != Severity.INFO }.forEach { add("new finding ${it.checkId.code} ${it.title} (${it.severity.label})") }
            unchanged.filter { it.new.severity.ordinal < it.old.severity.ordinal }
                .forEach { add("${it.new.checkId.code} severity rose from ${it.old.severity.label} to ${it.new.severity.label}") }
            scoreDelta?.takeIf { it < 0 }?.let { add("score fell from ${oldResult.score} to ${newResult.score}") }
            notEstablished.forEach { (f, why) -> add("${f.checkId.code} NOT ESTABLISHED in the new capture ($why), so it cannot count as resolved") }
            if (newResult.band == ScoreBand.INCONCLUSIVE) {
                add("the new result is inconclusive (low network visibility), so it cannot show the release did not regress")
            }
        }

    val regression: Boolean get() = regressions.isNotEmpty()

    val packageMismatch: Boolean get() = old.bundle.app.packageName != new.bundle.app.packageName
}

object DiffText {
    fun render(d: BundleDiff): String = buildString {
        appendLine("TraceVeil CI diff · both bundles re-evaluated by ${CliInfo.NAME} ${CliInfo.version}")
        side("Old", d.old)
        side("New", d.new)
        if (d.packageMismatch) appendLine("Warning: the bundles are for different packages (${d.old.bundle.app.packageName} → ${d.new.bundle.app.packageName})")
        val delta = d.scoreDelta?.let { if (it >= 0) " (+$it)" else " ($it)" } ?: ""
        appendLine("Score: ${Wording.score(d.oldResult)} → ${Wording.score(d.newResult)}$delta")
        section("Resolved", d.resolved.map { row(it) })
        section("Not established in the new capture", d.notEstablished.map { (f, why) -> row(f) + "  [NOT ESTABLISHED: $why]" })
        section("New", d.added.map { row(it) })
        section("Unchanged", d.unchanged.map { u -> row(u.new) + (if (u.changes.isEmpty()) "" else "  [${u.changes.joinToString("; ")}]") })
        appendLine()
        if (d.regression) {
            appendLine("Result: REGRESSION (exit 1)")
            d.regressions.forEach { appendLine("  - $it") }
        } else {
            appendLine("Result: no regression (no new finding at Low severity or above, no severity rise, the score did not fall and every old finding was testable; exit 0)")
        }
        appendLine(Wording.LOOKUP_CAVEAT)
        appendLine("A resolved finding means it was not seen in the new capture; that covers only what the new run observed.")
    }

    private fun StringBuilder.side(name: String, b: LoadedBundle) {
        val bundle = b.bundle
        appendLine("$name: ${b.displayPath} · ${bundle.app.label} · ${bundle.app.packageName} · exported ${bundle.exportedAt}" +
            if (b.sample) " · SYNTHETIC SAMPLE" else "")
    }

    private fun row(f: Finding) = "${f.checkId.code.padEnd(5)}  ${f.severity.label.padEnd(8)}  ${f.evidenceType.name.padEnd(8)}  ${f.title}"

    private fun StringBuilder.section(title: String, rows: List<String>) {
        appendLine()
        appendLine("$title (${rows.size})")
        if (rows.isEmpty()) appendLine("  none") else rows.forEach { appendLine("  $it") }
    }
}

/** Machine-readable diff: schema "traceveil.ci.diff.v1". */
object DiffJson {
    const val SCHEMA = "traceveil.ci.diff.v1"

    fun render(d: BundleDiff): JsonObject = buildJsonObject {
        put("schema", SCHEMA)
        put("tool", buildJsonObject { put("name", CliInfo.NAME); put("version", CliInfo.version) })
        put("old", side(d.old, d.oldResult))
        put("new", side(d.new, d.newResult))
        put("scoreDelta", d.scoreDelta)
        put("resolved", JsonArray(d.resolved.map(CheckJson::finding)))
        put("notEstablished", JsonArray(d.notEstablished.map { (f, why) ->
            buildJsonObject { put("checkId", f.checkId.code); put("title", f.title); put("reason", why) }
        }))
        put("added", JsonArray(d.added.map(CheckJson::finding)))
        put("unchanged", JsonArray(d.unchanged.map { u ->
            buildJsonObject {
                put("checkId", u.new.checkId.code)
                put("title", u.new.title)
                put("changes", JsonArray(u.changes.map(::JsonPrimitive)))
            }
        }))
        put("regression", d.regression)
        put("regressions", JsonArray(d.regressions.map(::JsonPrimitive)))
        put("exitCode", if (d.regression) Exit.VIOLATION else Exit.PASS)
        put("caveat", Wording.LOOKUP_CAVEAT)
    }

    private fun side(b: LoadedBundle, r: AuditResult) = buildJsonObject {
        put("path", b.displayPath)
        put("app", buildJsonObject { put("package", b.bundle.app.packageName); put("label", b.bundle.app.label) })
        put("exportedAt", b.bundle.exportedAt)
        put("sample", b.sample)
        put("score", r.score)
        put("band", r.band.name)
        put("bandText", Wording.band(r.band))
        put("storedScore", b.bundle.result.score)
        put("checks", JsonArray(r.findings.map { JsonPrimitive(it.checkId.code) }))
    }
}
