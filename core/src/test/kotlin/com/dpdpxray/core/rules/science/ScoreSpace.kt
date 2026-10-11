package com.dpdpxray.core.rules.science

import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.Finding
import com.dpdpxray.core.model.Severity
import com.dpdpxray.core.rules.ScoreCalculator

/**
 * Every assignment of the checks that carry points: each of the 14 scored checks (CheckVariants.scored) is absent or in
 * one of the variants the engine can give it. An assignment is a mixed-radix number: digit k is 0 when check k is absent,
 * otherwise 1 + the index of its variant. 2^3 (C1, NC, C2) x 3^7 (C3-C9: absent, observed, inferred) x 2^2 (C10, C13)
 * x 3 (C14: absent, Info, Low) x 3 (C16: absent, observed, inferred) = 629,856 assignments.
 *
 * The space is a superset of what the engine can reach (it also contains pairs the engine never emits together, such as
 * NC with C2), so an invariant proved on all of it holds on every real audit.
 */
object ScoreSpace {
    val checks: List<CheckId> = CheckVariants.scored
    val variants: List<List<Variant>> = checks.map { CheckVariants.table.getValue(it) }
    val radix: IntArray = IntArray(checks.size) { variants[it].size + 1 }
    val stride: IntArray = IntArray(checks.size).also { s -> var acc = 1; for (k in s.indices) { s[k] = acc; acc *= radix[k] } }
    val size: Int = radix.fold(1) { a, r -> a * r }

    fun digit(index: Int, k: Int): Int = (index / stride[k]) % radix[k]

    fun variant(index: Int, k: Int): Variant? = digit(index, k).let { d -> if (d == 0) null else variants[k][d - 1] }

    fun findings(index: Int, into: MutableList<Finding> = ArrayList(checks.size)): MutableList<Finding> {
        into.clear()
        for (k in checks.indices) variant(index, k)?.let { into += it.finding }
        return into
    }

    /** Pre-computed facts for every assignment, all from the production calculator (score, band) or plain counting. */
    class Table(
        val score: IntArray,
        /** Band rank: 0 At risk, 1 Needs work, 2 Ready. */
        val band: ByteArray,
        /** An observed High or Critical is present (the Ready gate is closed). */
        val serious: BooleanArray,
        /** Penalty in half-points. */
        val p2: IntArray,
    )

    val table: Table by lazy {
        val score = IntArray(size)
        val band = ByteArray(size)
        val serious = BooleanArray(size)
        val p2 = IntArray(size)
        val buf = ArrayList<Finding>(checks.size)
        for (i in 0 until size) {
            val fs = findings(i, buf)
            val (s, b) = ScoreCalculator.compute(fs)
            score[i] = s ?: error("assignment $i is inconclusive")
            band[i] = ScienceReport.rank(b).toByte()
            serious[i] = fs.any { it.evidenceType == EvidenceType.OBSERVED && it.severity.weight >= Severity.HIGH.weight }
            p2[i] = ScienceReport.penaltyHalfPoints(fs)
        }
        Table(score, band, serious, p2)
    }

    /**
     * Severity-first lexicographic key used as the reference ordering in the sensitivity analysis: counts of observed
     * Critical, High, Medium, Low, then inferred Critical, High, Medium, Low. Each count is below 10, so the decimal
     * digits compare lexicographically. Higher key = more serious.
     */
    fun lexKey(index: Int): Long {
        val counts = IntArray(8)
        for (k in checks.indices) {
            val v = variant(index, k) ?: continue
            val sev = when (v.severity) { Severity.CRITICAL -> 0; Severity.HIGH -> 1; Severity.MEDIUM -> 2; Severity.LOW -> 3; Severity.INFO -> continue }
            counts[sev + if (v.evidence == EvidenceType.OBSERVED) 0 else 4]++
        }
        return counts.fold(0L) { acc, c -> acc * 10 + c }
    }
}
