package com.dpdpxray.core.rules.science

import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.EvidenceType.INFERRED
import com.dpdpxray.core.model.EvidenceType.OBSERVED
import com.dpdpxray.core.model.Finding
import com.dpdpxray.core.model.PenaltyBand
import com.dpdpxray.core.model.Severity
import com.dpdpxray.core.model.Severity.CRITICAL
import com.dpdpxray.core.model.Severity.HIGH
import com.dpdpxray.core.model.Severity.INFO
import com.dpdpxray.core.model.Severity.LOW
import com.dpdpxray.core.model.Severity.MEDIUM

/** One way the rules engine can report a check: its severity, its evidence label and (for U1) whether it names trackers. */
data class Variant(val check: CheckId, val severity: Severity, val evidence: EvidenceType, val withEntities: Boolean = false) {
    /** A minimal finding with exactly the fields the score reads; the text fields do not matter to the score. */
    val finding: Finding = Finding(
        checkId = check, severity = severity, evidenceType = evidence, title = check.title, explanation = "",
        evidence = emptyList(), plans = listOf(AuditPlan.ACCEPT), dpdpRefs = emptyList(), penaltyBand = PenaltyBand.NONE,
        entities = if (withEntities) listOf("Tracker") else emptyList(),
    )

    val code: String get() = "${check.code}:${severity.label}/${evidence.name.lowercase()}" + if (withEntities) "+entities" else ""
}

/**
 * Every (severity, evidence) pair the rules engine can give each check, read from RulesEngine.kt and ExtendedChecks.kt:
 *
 * - C1, NC (High) and C2 (Critical) are always OBSERVED: they rest on DNS lookups and consent events seen on the phone.
 * - C3 to C8 (screen checks) are OBSERVED when the field was confirmed from the accessibility tree, otherwise INFERRED
 *   (AI screen review or ConsentLens): `ev(field)` in RulesEngine.screenChecks.
 * - C9 (Critical) is OBSERVED when the app was declared child-facing, INFERRED when only the screen review signalled it.
 * - C10 (Medium) is always INFERRED (vendor documentation). C13 (Low) is always INFERRED (vendor documentation).
 * - C14 is OBSERVED, Low when a permission that can reach personal data was asked first, Info for notifications only.
 * - V0, U1, C11, C12, C15, V1 are OBSERVED Info; S1 is INFERRED Info. U1 makes a result inconclusive only when it names
 *   tracker entities, so it has two variants.
 *
 * CheckVariantWitnessTest drives the real engine to produce each variant (each one is reachable) and fuzzes the engine to
 * show it emits nothing outside this table, at most once per check.
 */
object CheckVariants {
    val table: Map<CheckId, List<Variant>> = linkedMapOf(
        CheckId.C1 to listOf(Variant(CheckId.C1, HIGH, OBSERVED)),
        CheckId.NC to listOf(Variant(CheckId.NC, HIGH, OBSERVED)),
        CheckId.C2 to listOf(Variant(CheckId.C2, CRITICAL, OBSERVED)),
        CheckId.C3 to both(CheckId.C3, HIGH),
        CheckId.C4 to both(CheckId.C4, HIGH),
        CheckId.C5 to both(CheckId.C5, MEDIUM),
        CheckId.C6 to both(CheckId.C6, MEDIUM),
        CheckId.C7 to both(CheckId.C7, MEDIUM),
        CheckId.C8 to both(CheckId.C8, LOW),
        CheckId.C9 to both(CheckId.C9, CRITICAL),
        CheckId.C10 to listOf(Variant(CheckId.C10, MEDIUM, INFERRED)),
        CheckId.C13 to listOf(Variant(CheckId.C13, LOW, INFERRED)),
        CheckId.C14 to listOf(Variant(CheckId.C14, INFO, OBSERVED), Variant(CheckId.C14, LOW, OBSERVED)),
        CheckId.V0 to listOf(Variant(CheckId.V0, INFO, OBSERVED)),
        CheckId.U1 to listOf(Variant(CheckId.U1, INFO, OBSERVED), Variant(CheckId.U1, INFO, OBSERVED, withEntities = true)),
        CheckId.S1 to listOf(Variant(CheckId.S1, INFO, INFERRED)),
        CheckId.C11 to listOf(Variant(CheckId.C11, INFO, OBSERVED)),
        CheckId.C12 to listOf(Variant(CheckId.C12, INFO, OBSERVED)),
        CheckId.C15 to listOf(Variant(CheckId.C15, INFO, OBSERVED)),
        CheckId.V1 to listOf(Variant(CheckId.V1, INFO, OBSERVED)),
    )

    /** Checks with at least one variant that carries points (C14 is here because its Low variant does). */
    val scored: List<CheckId> = table.filterValues { vs -> vs.any { it.severity.weight > 0 } }.keys.toList()

    /** Zero-weight notes that never make a result inconclusive. */
    val notes: List<CheckId> = listOf(CheckId.S1, CheckId.C11, CheckId.C12, CheckId.C15, CheckId.V1)

    val all: List<Variant> = table.values.flatten()

    private fun both(id: CheckId, s: Severity) = listOf(Variant(id, s, OBSERVED), Variant(id, s, INFERRED))
}
