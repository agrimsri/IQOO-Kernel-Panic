package com.dpdpxray.cli

import com.dpdpxray.core.law.LawRelation
import com.dpdpxray.core.law.LensView
import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.Finding
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.rules.ScoreCalculator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Wording shared by every output format. */
object Wording {
    const val LOOKUP_CAVEAT = "A DNS lookup is an attempt to contact a host, not proof that personal data was sent."
    const val SCOPE_CAVEAT = "Findings come from DNS lookups and consent-screen review on one phone during one run. " +
        "This is an automated technical check, not legal advice or a compliance certificate."

    fun score(r: AuditResult): String =
        if (r.band == ScoreBand.INCONCLUSIVE || r.score == null) "Inconclusive (score not established)" else "${r.score}/100 · ${band(r.band, r.score)}"

    /**
     * The score band as a plain range; never a readiness or compliance label. Thresholds as in the core rules engine,
     * where an observed High or Critical finding keeps even 85+ points out of the top band.
     */
    fun band(b: ScoreBand, score: Int? = null): String = when (b) {
        ScoreBand.READY -> "85–100 band"
        ScoreBand.NEEDS_WORK -> if (score != null && score >= 85) "below the 85–100 band: a High or Critical finding was observed" else "60–84 band"
        ScoreBand.AT_RISK -> "below 60"
        ScoreBand.INCONCLUSIVE -> "inconclusive"
    }

    fun evidence(t: EvidenceType): String = t.name

    /**
     * How far AI and documentation readings (INFERRED findings) could move the result: worst case every one confirmed,
     * best case every one wrong. Computed from the findings by the core calculator; the score itself never changes.
     */
    fun robustness(r: AuditResult): String {
        val rob = ScoreCalculator.robustness(r.findings) ?: return "not established (the result is inconclusive)"
        val codes = rob.inferred.joinToString { it.code }
        return when {
            rob.movable -> "AI and documentation readings (INFERRED: $codes) can move this between ${rob.worstCase} and ${rob.bestCase} · " +
                if (rob.bandStable) "the band stays ${band(rob.band, rob.score)}"
                else "the band could change (worst case: ${band(rob.worstBand, rob.worstCase)}; best case: ${band(rob.bestBand, rob.bestCase)})"
            rob.inferred.isEmpty() -> "fixed: no INFERRED finding carries points, so AI and documentation readings cannot move this score"
            else -> "fixed at ${rob.score}: the OBSERVED findings alone give ${rob.bestCase}, so the INFERRED findings ($codes) cannot move it"
        }
    }

    const val ROBUSTNESS_MEANING = "worstCase: every INFERRED finding (AI screen review or SDK documentation) confirmed, at full weight, " +
        "and an inferred High or Critical then closes the 85-100 band like an observed one; bestCase: every INFERRED finding wrong " +
        "and dropped. Computed from the current result's findings; it never changes the score."

    fun evidenceMeaning(t: EvidenceType): String = when (t) {
        EvidenceType.OBSERVED -> "OBSERVED evidence"
        EvidenceType.INFERRED -> "INFERRED evidence: derived from SDK documentation or AI review, not directly observed"
    }

    fun firstEvidence(f: Finding): String = f.evidence.firstOrNull() ?: "(no evidence line recorded)"

    /** The lens score with the neutral band range; a reading of the findings, never a legal verdict. */
    fun lensScore(v: LensView): String =
        if (v.band == ScoreBand.INCONCLUSIVE || v.score == null) "inconclusive (score not established)" else "${v.score}/100 · ${band(v.band)}"

    fun lensCodes(v: LensView, r: LawRelation): String =
        v.findings.filter { it.relation == r }.joinToString { it.finding.checkId.code }.ifEmpty { "none" }

    const val LENS_CAVEAT = "A reading of the findings under this lens, not a legal verdict."

    fun choice(c: PlanCoverage): String = when {
        c.choice == ConsentKind.ACCEPT -> "Accept recorded"
        c.choice == ConsentKind.REJECT -> "Reject recorded"
        c.consentScreenSeen -> "consent screen seen, no choice"
        else -> "no consent screen seen"
    }

    fun fresh(c: List<PlanCoverage>): String {
        if (c.isEmpty()) return "NOT ESTABLISHED (no plans)"
        val byState = c.groupBy { it.fresh }
        if (byState.keys == setOf(FreshState.FRESH)) return "all ${c.size} plans started from an approved fresh reset"
        return c.joinToString("; ") {
            it.plan.label + ": " + when (it.fresh) {
                FreshState.FRESH -> "fresh reset"
                FreshState.KEPT -> "app kept its data"
                FreshState.NOT_RECORDED -> "NOT ESTABLISHED (not recorded)"
            }
        }
    }
}

/** Human-readable report for terminals and CI logs. */
object CheckText {
    fun render(checks: List<BundleCheck>, policy: Policy, policySource: String): String = buildString {
        appendLine("TraceVeil CI check · rules re-evaluated by ${CliInfo.NAME} ${CliInfo.version}")
        appendLine("Policy: $policySource")
        for (c in checks) {
            appendLine()
            bundle(c)
        }
        appendLine()
        val failed = checks.count { !it.passed }
        val violations = checks.sumOf { it.violations.size }
        if (failed == 0) appendLine("Overall: PASS (${plural(checks.size, "bundle")} checked, no policy violations; exit 0)")
        else appendLine("Overall: FAIL ($failed of ${plural(checks.size, "bundle")} violated the policy, ${plural(violations, "violation")}; exit 1)")
        appendLine(Wording.LOOKUP_CAVEAT)
        appendLine(Wording.SCOPE_CAVEAT)
        if (policy.failOn.isEmpty() && policy.minScore == null && policy.maxHigh == null) {
            appendLine("Note: this policy fails on no finding and sets no score threshold.")
        }
    }

    private fun StringBuilder.bundle(c: BundleCheck) {
        val b = c.source.bundle
        appendLine("== ${c.source.displayPath}")
        appendLine("App:      ${b.app.label} · ${b.app.packageName}")
        appendLine("Exported: ${b.exportedAt} by ${b.tool.name} ${b.tool.version}")
        c.signature?.let { s ->
            val hint = if (s.state == SignatureStatus.State.FAILED) " · traceveil-ci verify shows every check" else ""
            appendLine("Signature: ${s.summary}${s.path?.let { " · $it" } ?: ""}$hint")
        }
        c.warnings.forEach { appendLine("Warning:  $it") }
        appendLine("Plans:    " + if (c.coverage.isEmpty()) "none" else c.coverage.joinToString(" | ") { "${it.plan.label}: ${Wording.choice(it)}" })
        appendLine("Fresh:    ${Wording.fresh(c.coverage)}")
        if (c.resultChanged) {
            appendLine("Score:    ${Wording.score(c.current)} (re-evaluated with current rules)")
            appendLine("Stored:   ${Wording.score(c.stored)} (score saved on the phone; differs from today's rules)")
            if (c.onlyStored.isNotEmpty()) appendLine("          only in the stored result: ${c.onlyStored.joinToString { it.checkId.code }}")
            if (c.onlyCurrent.isNotEmpty()) appendLine("          only after re-evaluation: ${c.onlyCurrent.joinToString { it.checkId.code }}")
        } else {
            appendLine("Score:    ${Wording.score(c.current)} (re-evaluated; same as stored in the bundle)")
        }
        appendLine("Range:    ${Wording.robustness(c.current)}")
        lens(c)
        appendLine()
        findingsTable(c.current.findings)
        appendLine()
        appendLine("Policy")
        val ruleWidth = c.outcomes.maxOf { it.rule.length }
        val statusWidth = c.outcomes.maxOf { it.status.label.length }
        c.outcomes.forEach { appendLine("  ${it.status.label.padEnd(statusWidth)}  ${it.rule.padEnd(ruleWidth)}  ${it.message}") }
        appendLine()
        if (c.passed) appendLine("Result: PASS (no policy violations)")
        else appendLine("Result: FAIL (${plural(c.violations.size, "policy violation")})")
    }

    private fun StringBuilder.lens(c: BundleCheck) {
        val v = c.lens
        val p = v.profile
        appendLine("Lens:     ${p.name} (${v.id.name}, ${c.lensSource.text}) · ${p.laws}")
        appendLine("          ${p.relationLabel(LawRelation.REQUIRED)}: ${Wording.lensCodes(v, LawRelation.REQUIRED)} · Context: ${Wording.lensCodes(v, LawRelation.CONTEXT)} · " +
            "Not applicable: ${Wording.lensCodes(v, LawRelation.NOT_APPLICABLE)}")
        appendLine("          Under this lens: ${Wording.lensScore(v)} (not-applicable findings skipped). ${Wording.LENS_CAVEAT}")
    }

    fun StringBuilder.findingsTable(findings: List<Finding>) {
        if (findings.isEmpty()) {
            appendLine("No findings in the captured evidence. That covers only what this run observed.")
            return
        }
        appendLine("CHECK  SEVERITY  EVIDENCE  FINDING")
        for (f in findings) {
            appendLine("${f.checkId.code.padEnd(5)}  ${f.severity.label.padEnd(8)}  ${Wording.evidence(f.evidenceType).padEnd(8)}  ${f.title}")
            appendLine("${" ".repeat(27)}first evidence: ${Wording.firstEvidence(f)}")
        }
    }

    fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"
}

/** Machine-readable report: schema "traceveil.ci.check.v1". */
object CheckJson {
    const val SCHEMA = "traceveil.ci.check.v1"

    fun render(checks: List<BundleCheck>, policy: Policy, policySource: String): JsonObject = buildJsonObject {
        put("schema", SCHEMA)
        put("tool", buildJsonObject { put("name", CliInfo.NAME); put("version", CliInfo.version) })
        put("policySource", policySource)
        put("policy", policy.toJson())
        put("passed", checks.all { it.passed })
        put("exitCode", if (checks.all { it.passed }) Exit.PASS else Exit.VIOLATION)
        put("violations", checks.sumOf { it.violations.size })
        put("bundles", JsonArray(checks.map(::bundle)))
        put("caveat", "${Wording.LOOKUP_CAVEAT} ${Wording.SCOPE_CAVEAT}")
    }

    private fun bundle(c: BundleCheck): JsonObject = buildJsonObject {
        val b = c.source.bundle
        put("path", c.source.displayPath)
        put("app", buildJsonObject { put("package", b.app.packageName); put("label", b.app.label) })
        put("exportedAt", b.exportedAt)
        put("exportedBy", buildJsonObject { put("name", b.tool.name); put("version", b.tool.version) })
        put("sample", c.source.sample)
        put("note", b.note)
        put("storedResult", result(c.stored))
        put("currentResult", result(c.current))
        put("robustness", robustness(c.current))
        put("resultChanged", c.resultChanged)
        put("plans", buildJsonArray {
            c.coverage.forEach { p ->
                add(buildJsonObject {
                    put("plan", p.plan.name)
                    put("label", p.plan.label)
                    put("consentScreenSeen", p.consentScreenSeen)
                    put("choice", p.choice?.name)
                    put("freshState", when (p.fresh) { FreshState.FRESH -> true; FreshState.KEPT -> false; FreshState.NOT_RECORDED -> null })
                })
            }
        })
        put("findings", JsonArray(c.current.findings.map(::finding)))
        put("rules", buildJsonArray {
            c.outcomes.forEach { o -> add(buildJsonObject { put("rule", o.rule); put("status", o.status.name); put("message", o.message) }) }
        })
        put("legalLens", lens(c))
        put("signature", c.signature?.toJson() ?: JsonNull)
        put("warnings", JsonArray(c.warnings.map(::JsonPrimitive)))
        put("passed", c.passed)
    }

    private fun lens(c: BundleCheck) = buildJsonObject {
        val v = c.lens
        put("profile", v.id.name)
        put("name", v.profile.name)
        put("laws", v.profile.laws)
        put("kind", v.profile.kind.name)
        put("source", c.lensSource.name.lowercase())
        put("score", v.score)
        put("band", v.band.name)
        put("bandText", Wording.band(v.band, v.score))
        put("notApplicable", JsonArray(v.notApplicable.map { JsonPrimitive(it.finding.checkId.code) }))
        put("relations", buildJsonArray {
            v.findings.forEach { lf ->
                add(buildJsonObject {
                    put("checkId", lf.finding.checkId.code)
                    put("relation", lf.relation.name)
                    put("mapped", lf.mapped)
                    put("note", lf.mapping.note)
                    put("refs", JsonArray(lf.mapping.refs.map(::JsonPrimitive)))
                })
            }
        })
        put("caveat", Wording.LENS_CAVEAT)
    }

    /** The robustness interval of [r] (null when inconclusive); see Wording.robustness. */
    private fun robustness(r: AuditResult): JsonElement {
        val rob = ScoreCalculator.robustness(r.findings) ?: return JsonNull
        return buildJsonObject {
            put("score", rob.score)
            put("worstCase", rob.worstCase)
            put("bestCase", rob.bestCase)
            put("band", rob.band.name)
            put("worstBand", rob.worstBand.name)
            put("bestBand", rob.bestBand.name)
            put("bandStable", rob.bandStable)
            put("movable", rob.movable)
            put("inferred", JsonArray(rob.inferred.map { JsonPrimitive(it.code) }))
            put("text", Wording.robustness(r))
            put("meaning", Wording.ROBUSTNESS_MEANING)
        }
    }

    private fun result(r: AuditResult) = buildJsonObject {
        put("score", r.score)
        put("band", r.band.name)
        put("bandText", Wording.band(r.band, r.score))
        put("checks", JsonArray(r.findings.map { JsonPrimitive(it.checkId.code) }))
    }

    fun finding(f: Finding) = buildJsonObject {
        put("checkId", f.checkId.code)
        put("title", f.title)
        put("severity", f.severity.name)
        put("evidenceLabel", f.evidenceType.name)
        put("firstEvidence", Wording.firstEvidence(f))
        put("evidence", JsonArray(f.evidence.map(::JsonPrimitive)))
        put("plans", JsonArray(f.plans.map { JsonPrimitive(it.name) }))
        put("entities", JsonArray(f.entities.map(::JsonPrimitive)))
        put("dpdpRefs", JsonArray(f.dpdpRefs.map(::JsonPrimitive)))
        put("explanation", f.explanation)
    }
}
