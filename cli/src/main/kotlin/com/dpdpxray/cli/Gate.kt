package com.dpdpxray.cli

import com.dpdpxray.core.export.AuditBundleV1
import com.dpdpxray.core.export.AuditBundles
import com.dpdpxray.core.export.BundleFormatException
import com.dpdpxray.core.export.BundleMeta
import com.dpdpxray.core.law.LegalLens
import com.dpdpxray.core.law.LegalProfile
import com.dpdpxray.core.law.LensView
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.Finding
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.ScreenAudit
import com.dpdpxray.core.model.Severity
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import java.io.File

/** A bundle file read from disk. [displayPath] is relative to the working directory when the file is inside it. */
class LoadedBundle(val displayPath: String, val text: String, val bundle: AuditBundleV1, val uri: String = displayPath) {
    val meta: BundleMeta = AuditBundles.metaOf(bundle)

    /** Synthetic data: the app's sample flag, or a fixture note. */
    val sample: Boolean get() = meta.sample || bundle.note != null

    companion object {
        /** Throws [UsageException] when the file is missing or is not a readable traceveil.audit.v1 bundle. */
        fun read(file: File, cwd: File): LoadedBundle {
            val shown = displayPath(file, cwd)
            if (!file.isFile) throw UsageException("$shown: no such file")
            val text = runCatching { file.readText(Charsets.UTF_8).removePrefix("﻿") }
                .getOrElse { throw UsageException("$shown: could not read the file (${it.message})") }
            val bundle = try {
                AuditBundles.decode(text)
            } catch (e: BundleFormatException) {
                throw UsageException("$shown: ${e.message}")
            }
            return LoadedBundle(shown, text, bundle, artifactUri(file, cwd))
        }

        /** SARIF artifact location: relative to the working directory (the repository root in CI), else a file URI. */
        fun artifactUri(file: File, cwd: File): String {
            val shown = displayPath(file, cwd)
            return if (File(shown).isAbsolute || shown.startsWith("/")) file.absoluteFile.toPath().normalize().toUri().toString() else shown
        }

        fun displayPath(file: File, cwd: File): String {
            val abs = file.absoluteFile.toPath().normalize()
            val base = cwd.absoluteFile.toPath().normalize()
            return if (abs.startsWith(base) && abs != base) base.relativize(abs).toString().replace(File.separatorChar, '/')
            else abs.toString().replace(File.separatorChar, '/')
        }
    }
}

enum class FreshState { FRESH, KEPT, NOT_RECORDED }

/** What one captured plan can and cannot show. */
data class PlanCoverage(val plan: AuditPlan, val consentScreenSeen: Boolean, val choice: ConsentKind?, val fresh: FreshState) {
    companion object {
        fun of(s: AuditSession) = PlanCoverage(
            plan = s.plan, consentScreenSeen = s.consentScreenSeen, choice = s.consentChoice?.kind,
            fresh = when (s.freshState) { true -> FreshState.FRESH; false -> FreshState.KEPT; null -> FreshState.NOT_RECORDED },
        )
    }
}

/** What a capture could and could not show, per check. Shared by `check` (failOn) and `diff` (resolved or not). */
object Coverage {
    /** The consent-screen review the rules engine uses (the first plan's screen that was judged a consent screen). */
    fun reviewedScreen(sessions: List<AuditSession>): ScreenAudit? = sessions.mapNotNull { it.screenAudit }.firstOrNull { it.isConsentScreen }

    /** Why the captured plans could not show [id] at all, or null when they could. Unknown stays unknown. */
    fun untested(id: CheckId, sessions: List<AuditSession>): String? {
        if (sessions.isEmpty()) return "no plans were captured"
        val historyUnknown = sessions.all { it.appDataKept && !it.consentScreenSeen }
        return when (id) {
            CheckId.C2 -> "no Reject choice was recorded, so behaviour after refusal was not observed"
                .takeIf { sessions.none { it.consentChoice?.kind == ConsentKind.REJECT } }
            CheckId.C1, CheckId.C10, CheckId.NC -> "every plan ran on the app's kept data without a consent screen (consent history unknown)"
                .takeIf { historyUnknown }
            CheckId.C3, CheckId.C4, CheckId.C5, CheckId.C6, CheckId.C7, CheckId.C8 -> "no consent screen was reviewed"
                .takeIf { sessions.none { it.screenAudit?.isConsentScreen == true } }
            CheckId.C9 -> "the app was not declared child-facing in this capture and no consent screen was reviewed for child-audience signals"
                .takeIf { sessions.none { it.childFacing } && reviewedScreen(sessions)?.childAudienceSignals == null }
            else -> null
        }
    }
}

enum class RuleStatus(val label: String) { PASS("PASS"), FAIL("FAIL"), NOT_ESTABLISHED("NOT ESTABLISHED"), OFF("OFF") }

data class RuleOutcome(val rule: String, val status: RuleStatus, val message: String)

/** Where the legal lens of a check came from: the --profile option, the bundle's own legalProfile, or the default. */
enum class LensSource(val text: String) { OPTION("from --profile"), BUNDLE("from the bundle"), DEFAULT("default: the bundle names no lens") }

/**
 * One bundle checked against a policy: both the result stored in the bundle and the re-evaluated one. [lens] reads the
 * re-evaluated findings under a legal lens; it never changes the policy outcome, which stays on the consent-first result.
 */
class BundleCheck(
    val source: LoadedBundle,
    val stored: AuditResult,
    val current: AuditResult,
    val coverage: List<PlanCoverage>,
    val outcomes: List<RuleOutcome>,
    val warnings: List<String>,
    val lens: LensView,
    val lensSource: LensSource,
) {
    val passed: Boolean get() = outcomes.none { it.status == RuleStatus.FAIL }
    val violations: List<RuleOutcome> get() = outcomes.filter { it.status == RuleStatus.FAIL }

    /** True when today's rules give a different score, band or set of checks than the phone stored. */
    val resultChanged: Boolean
        get() = stored.score != current.score || stored.band != current.band || signature(stored) != signature(current)

    val onlyStored: List<Finding> get() = stored.findings.filter { s -> current.findings.none { it.checkId == s.checkId } }
    val onlyCurrent: List<Finding> get() = current.findings.filter { c -> stored.findings.none { it.checkId == c.checkId } }

    private fun signature(r: AuditResult) = r.findings.map { Triple(it.checkId, it.severity, it.evidenceType) }.toSet()
}

/** Re-evaluates bundles with this build's rules engine and default tracker catalogue, then applies a policy. */
class Gate(
    private val engine: RulesEngine = RulesEngine(TrackerCatalog.loadDefault()),
    private val legal: LegalLens = LegalLens.default,
) {

    fun reevaluate(bundle: AuditBundleV1): AuditResult = engine.evaluate(bundle.sessions)

    /** [profile] (from --profile) wins; else the bundle's legalProfile; else the global baseline. */
    fun check(source: LoadedBundle, policy: Policy, profile: LegalProfile? = null): BundleCheck {
        val b = source.bundle
        val current = reevaluate(b)
        val named = LegalProfile.parse(b.legalProfile)
        val (lensId, lensSource) = when {
            profile != null -> profile to LensSource.OPTION
            named != null -> named to LensSource.BUNDLE
            else -> LegalProfile.DEFAULT to LensSource.DEFAULT
        }
        val coverage = b.sessions.map(PlanCoverage::of)
        val outcomes = buildList {
            add(inconclusive(current, policy))
            policy.failOn.forEach { add(failOn(it, current, b.sessions, policy)) }
            add(minScore(current, policy))
            add(maxHigh(current, policy))
            add(freshState(coverage, policy))
            add(requirePlans(coverage, policy))
        }
        val warnings = warnings(source) + listOfNotNull(
            b.legalProfile?.takeIf { named == null }?.let { "the bundle names legal lens \"$it\", which this version does not know; using ${lensId.name}" },
        )
        return BundleCheck(source, b.result, current, coverage, outcomes, warnings, legal.view(current, lensId), lensSource)
    }

    private fun inconclusive(r: AuditResult, p: Policy): RuleOutcome = when {
        r.band != ScoreBand.INCONCLUSIVE -> RuleOutcome("allowInconclusive", RuleStatus.PASS, "result is conclusive")
        p.allowInconclusive -> RuleOutcome("allowInconclusive", RuleStatus.PASS, "result is inconclusive (low network visibility); the policy accepts that")
        else -> RuleOutcome(
            "allowInconclusive", RuleStatus.FAIL,
            "result is inconclusive (V0: almost no lookups were captured), so this capture cannot pass; re-run it",
        )
    }

    /**
     * A finding fails the rule. A check the captured plans could not test is NOT ESTABLISHED, and that fails too unless
     * the policy sets allowNotEstablished: a gate never passes a check by the absence of evidence it could not collect.
     */
    private fun failOn(id: CheckId, r: AuditResult, sessions: List<AuditSession>, p: Policy): RuleOutcome {
        val rule = "failOn ${id.code}"
        r.findings.firstOrNull { it.checkId == id }?.let { f ->
            return RuleOutcome(rule, RuleStatus.FAIL, "${id.code} ${f.title} (${f.severity.label}, ${f.evidenceType.name} evidence)")
        }
        Coverage.untested(id, sessions)?.let { why ->
            val text = "${id.code} NOT ESTABLISHED: $why"
            return if (p.allowNotEstablished) RuleOutcome(rule, RuleStatus.NOT_ESTABLISHED, "$text (reported, not failed: allowNotEstablished is true)")
            else RuleOutcome(rule, RuleStatus.FAIL, "$text; a failOn check that could not be tested fails unless allowNotEstablished is true")
        }
        val context = if (id == CheckId.C9) {
            if (sessions.any { it.childFacing }) " (the app was declared child-facing in this capture)"
            else " (not declared child-facing; the consent-screen review found ${if (Coverage.reviewedScreen(sessions)?.childAudienceSignals == true) "" else "no "}child-audience signals)"
        } else ""
        return RuleOutcome(rule, RuleStatus.PASS, "no ${id.code} finding in the captured evidence$context")
    }

    private fun minScore(r: AuditResult, p: Policy): RuleOutcome {
        val min = p.minScore ?: return RuleOutcome("minScore", RuleStatus.OFF, "not set")
        val score = r.score ?: return RuleOutcome("minScore", RuleStatus.NOT_ESTABLISHED, "score not established (inconclusive result)")
        return if (score >= min) RuleOutcome("minScore", RuleStatus.PASS, "score $score is at least $min")
        else RuleOutcome("minScore", RuleStatus.FAIL, "score $score is below the minimum $min")
    }

    private fun maxHigh(r: AuditResult, p: Policy): RuleOutcome {
        val max = p.maxHigh ?: return RuleOutcome("maxHigh", RuleStatus.OFF, "not set")
        val high = r.findings.filter { it.severity.ordinal <= Severity.HIGH.ordinal }
        val list = if (high.isEmpty()) "" else " (${high.joinToString { it.checkId.code }})"
        return if (high.size <= max) RuleOutcome("maxHigh", RuleStatus.PASS, "${high.size} at High or Critical severity$list; at most $max allowed")
        else RuleOutcome("maxHigh", RuleStatus.FAIL, "${high.size} findings at High or Critical severity$list; the policy allows $max")
    }

    private fun freshState(c: List<PlanCoverage>, p: Policy): RuleOutcome {
        if (!p.requireFreshState) return RuleOutcome("requireFreshState", RuleStatus.OFF, "not required")
        if (c.isEmpty()) return RuleOutcome("requireFreshState", RuleStatus.FAIL, "no plans were captured")
        val problems = c.mapNotNull {
            when (it.fresh) {
                FreshState.FRESH -> null
                FreshState.KEPT -> "${it.plan.label} ran on the app's kept data (no approved fresh reset)"
                FreshState.NOT_RECORDED -> "${it.plan.label}: fresh state NOT ESTABLISHED (not recorded in this audit)"
            }
        }
        return if (problems.isEmpty()) RuleOutcome("requireFreshState", RuleStatus.PASS, "all ${c.size} plans started from an approved fresh reset")
        else RuleOutcome("requireFreshState", RuleStatus.FAIL, problems.joinToString("; "))
    }

    private fun requirePlans(c: List<PlanCoverage>, p: Policy): RuleOutcome {
        if (p.requirePlans.isEmpty()) return RuleOutcome("requirePlans", RuleStatus.OFF, "no plans required")
        val problems = mutableListOf<String>()
        for (plan in p.requirePlans) {
            val runs = c.filter { it.plan == plan }
            if (runs.isEmpty()) { problems += "${plan.label} was not captured"; continue }
            val expected = when (plan) { AuditPlan.ACCEPT -> ConsentKind.ACCEPT; AuditPlan.REJECT -> ConsentKind.REJECT; AuditPlan.SILENT -> null }
            // An Accept or Reject plan covers its choice only when that choice was recorded; no consent screen is no choice.
            if (expected != null && runs.none { it.choice == expected }) {
                val word = expected.name.lowercase().replaceFirstChar { it.uppercase() }
                problems += if (runs.none { it.consentScreenSeen }) {
                    "${plan.label}: no consent screen appeared, so no $word choice was recorded and behaviour after it was not observed"
                } else {
                    "${plan.label}: a consent screen appeared but no $word choice was recorded, so behaviour after it was not observed"
                }
            }
        }
        val names = p.requirePlans.joinToString { it.label }
        return if (problems.isEmpty()) RuleOutcome("requirePlans", RuleStatus.PASS, "captured: $names")
        else RuleOutcome("requirePlans", RuleStatus.FAIL, problems.joinToString("; "))
    }

    private fun warnings(source: LoadedBundle): List<String> = buildList {
        val b = source.bundle
        if (source.sample) {
            val note = b.note ?: "the bundle's meta marks it as sample data, not a device observation"
            add(if (note.startsWith("SYNTHETIC SAMPLE", ignoreCase = true)) note else "SYNTHETIC SAMPLE: $note")
        }
        if (b.sessions.isEmpty()) add("the bundle has no capture sessions")
        val pkgs = b.sessions.map { it.targetPackage }.distinct()
        if (pkgs.isNotEmpty() && pkgs != listOf(b.app.packageName)) add("app.package ${b.app.packageName} does not match the sessions' package ${pkgs.joinToString()}")
        if (source.meta.timingProfile == "fast_preview") add("Fast Preview capture: a short first look, not a full consent audit")
    }
}
