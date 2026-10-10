package com.dpdpxray.core.rules.science

import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.rules.science.ScienceReport.json
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.random.Random

/**
 * Sensitivity analysis on the 209,952 scored assignments of ScoreSpace (every combination of the checks that carry points,
 * each in every variant the engine can give it). Nothing here changes a weight, threshold or band rule: it measures them.
 *
 * 1. Thresholds: for Ready threshold R in 75..95 and Needs-work threshold N in 50..70 (gate unchanged), how many
 *    assignments get a different band than with 85/60.
 * 2. Alternative scoring schemes on the same assignments, each compared by Kendall's tau-b with a severity-first
 *    lexicographic reference (observed Critical, High, Medium, Low counts, then inferred Critical, High, Medium, Low), and
 *    by how many lattice edges (add a finding, confirm an inferred one) make the scheme say "better".
 *
 * Results go to core/build/reports/score-science/sensitivity.{json,md}; the numbers are reported as they come out.
 */
class ScoreSensitivityTest {
    private val space = ScoreSpace
    private val t get() = space.table

    private fun band(i: Int, r: Int, n: Int): Int = when {
        t.score[i] >= r && !t.serious[i] -> 2
        t.score[i] >= n -> 1
        else -> 0
    }

    @Test
    fun `Kendall tau-b - Knight's method matches pair counting`() {
        // Hand-worked: x = 1 1 2 3, y = 1 2 2 3 -> 4 concordant, 0 discordant, 1 pair tied in x, 1 in y: 4 / sqrt(5 x 5) = 0.8.
        val hand = KendallTau.of(longArrayOf(1, 1, 2, 3), longArrayOf(1, 2, 2, 3))
        assertEquals(KendallTau.Result(0.8, 4, 0, 1, 1, 0, 6), hand)
        assertEquals(1.0, KendallTau.of(longArrayOf(1, 2, 3, 4), longArrayOf(10, 20, 30, 40)).tauB, 0.0)
        assertEquals(-1.0, KendallTau.of(longArrayOf(1, 2, 3, 4), longArrayOf(40, 30, 20, 10)).tauB, 0.0)
        val rnd = Random(ScorePropertiesTest.seed + 5)
        repeat(300) {
            val n = rnd.nextInt(2, 60)
            val x = LongArray(n) { rnd.nextLong(0, 6) }
            val y = LongArray(n) { rnd.nextLong(0, 6) }
            val fast = KendallTau.of(x, y)
            val slow = KendallTau.naive(x, y)
            assertEquals(slow.copy(tauB = 0.0), fast.copy(tauB = 0.0))
            if (!slow.tauB.isNaN()) assertEquals(slow.tauB, fast.tauB, 1e-12)
        }
    }

    @Test
    fun `threshold grid - band changes against 85 and 60`() {
        val n = space.size
        for (i in 0 until n) assertEquals(t.band[i].toInt(), band(i, 85, 60))
        val rs = (75..95).toList()
        val ns = (50..70).toList()
        val grid = rs.map { r -> ns.map { nn -> (0 until n).count { band(it, r, nn) != t.band[it].toInt() } } }
        val ready = rs.associateWith { r -> (0 until n).count { band(it, r, 60) == 2 } }
        val atRisk = ns.associateWith { nn -> (0 until n).count { band(it, 85, nn) == 0 } }
        val hist = IntArray(101).also { h -> for (i in 0 until n) h[t.score[i]]++ }
        val readyHist = IntArray(101).also { h -> for (i in 0 until n) if (!t.serious[i]) h[t.score[i]]++ }
        // The grid is additive: R only moves the Ready/Needs-work line and N only the Needs-work/At-risk line (R > N always).
        for ((a, r) in rs.withIndex()) for ((b, nn) in ns.withIndex()) {
            assertEquals(grid[a][ns.indexOf(60)] + grid[rs.indexOf(85)][b], grid[a][b])
        }
        val rStep = rs.associateWith { r -> grid[rs.indexOf(r)][ns.indexOf(60)] }
        val nStep = ns.associateWith { nn -> grid[rs.indexOf(85)][ns.indexOf(nn)] }
        report["thresholds"] = mapOf(
            "readyThresholds" to rs, "needsWorkThresholds" to ns, "changedGrid" to grid,
            "changedByReadyThreshold" to rStep, "changedByNeedsWorkThreshold" to nStep,
            "readyCountByReadyThreshold" to ready, "atRiskCountByNeedsWorkThreshold" to atRisk,
            "scoreHistogram" to hist, "gateOpenScoreHistogram" to readyHist,
        )
        assertEquals(0, grid[rs.indexOf(85)][ns.indexOf(60)])
    }

    /** One scheme: a "badness" value per assignment (higher = worse) and whether it is one of TraceVeil's own. */
    private class Scheme(val id: String, val name: String, val badness: DoubleArray)

    private fun schemes(): List<Scheme> {
        val n = space.size
        val totalWeight = space.variants.sumOf { vs -> vs.maxOf { it.severity.weight } }.toDouble()
        val score = DoubleArray(n) { -t.score[it].toDouble() }
        val bandRank = DoubleArray(n) { -t.band[it].toDouble() }
        val penalty = DoubleArray(n) { t.p2[it] / 2.0 }
        val lighthouse = DoubleArray(n) { -(100.0 * (1 - (t.p2[it] / 2.0) / totalWeight)) }
        val mean = DoubleArray(n)
        val max = DoubleArray(n)
        val count = DoubleArray(n)
        val product = DoubleArray(n)
        for (i in 0 until n) {
            val effective = space.findings(i).map { f -> f.severity.weight * if (f.evidenceType == EvidenceType.INFERRED) 0.5 else 1.0 }.filter { it > 0 }
            mean[i] = if (effective.isEmpty()) 0.0 else effective.sum() / effective.size
            max[i] = effective.maxOrNull() ?: 0.0
            count[i] = effective.size.toDouble()
            // Factors in a fixed (sorted) order, so equal multisets give bit-identical products.
            product[i] = 1.0 - effective.sorted().fold(1.0) { acc, w -> acc * (1 - w / 100.0) }
        }
        return listOf(
            Scheme("traceveil", "TraceVeil score (100 minus penalty, clamped at 0, rounded)", score),
            Scheme("traceveilBand", "TraceVeil band only (Ready / Needs work / At risk)", bandRank),
            Scheme("penalty", "Unclamped penalty (TraceVeil before clamping and rounding)", penalty),
            Scheme("weightedAverage", "Weighted average over all checks, Lighthouse-style: 100 x (1 - penalty / ${totalWeight.toInt()})", lighthouse),
            Scheme("meanSeverity", "Mean severity of the findings present (average of weight x multiplier)", mean),
            Scheme("maxSeverity", "Highest single severity only (weight x multiplier)", max),
            Scheme("count", "Simple count of findings that carry points", count),
            Scheme("multiplicative", "Multiplicative: 100 x product of (1 - weight x multiplier / 100)", product),
        )
    }

    @Test
    fun `alternative schemes - Kendall tau-b against the severity-first reference and monotonicity`() {
        val n = space.size
        val ref = LongArray(n) { space.lexKey(it) }
        val out = mutableListOf<Map<String, Any?>>()
        for (sc in schemes()) {
            val y = KendallTau.ranks(sc.badness)
            val k = KendallTau.of(ref, y)
            var edges = 0L
            var better = 0L
            for (i in 0 until n) for (kk in space.checks.indices) {
                val d = space.digit(i, kk)
                val vs = space.variants[kk]
                if (d == 0) for (v in 1..vs.size) {
                    edges++
                    if (y[i + v * space.stride[kk]] < y[i]) better++
                } else if (vs[d - 1].evidence == EvidenceType.INFERRED) {
                    val o = vs.indexOfFirst { it.evidence == EvidenceType.OBSERVED && it.severity == vs[d - 1].severity }
                    if (o >= 0) {
                        edges++
                        if (y[i + (o + 1 - d) * space.stride[kk]] < y[i]) better++
                    }
                }
            }
            out += mapOf(
                "id" to sc.id, "name" to sc.name, "tauB" to String.format(Locale.ROOT, "%.4f", k.tauB),
                "concordant" to k.concordant, "discordant" to k.discordant, "tiedInScheme" to k.tiedY,
                "tiedFraction" to String.format(Locale.ROOT, "%.4f", k.tiedY.toDouble() / k.pairs),
                "distinctValues" to (y.maxOrNull()!! + 1), "edges" to edges, "edgesWhereWorseLooksBetter" to better,
            )
            if (sc.id != "meanSeverity") assertEquals("${sc.id} must be monotone", 0L, better)
        }
        report["referencePairs"] = n.toLong() * (n - 1) / 2
        report["referenceDistinctKeys"] = ref.distinct().size
        report["schemes"] = out
        // The mean severity is not monotone: adding a Low finding to a set with a High lowers the average.
        assertTrue((out.first { it["id"] == "meanSeverity" }["edgesWhereWorseLooksBetter"] as Long) > 0)
    }

    companion object {
        private val report = sortedMapOf<String, Any?>()

        @JvmStatic
        @AfterClass
        fun writeReport() {
            if (report.isEmpty()) return
            ScienceReport.write("sensitivity.json", json(report))
            ScienceReport.write("sensitivity.md", buildString {
                appendLine("# Score sensitivity")
                @Suppress("UNCHECKED_CAST")
                (report["thresholds"] as? Map<String, Any?>)?.let { th ->
                    appendLine()
                    appendLine("Assignments whose band changes when only the Ready threshold moves (N = 60): ${th["changedByReadyThreshold"]}")
                    appendLine("Assignments whose band changes when only the Needs-work threshold moves (R = 85): ${th["changedByNeedsWorkThreshold"]}")
                }
                @Suppress("UNCHECKED_CAST")
                (report["schemes"] as? List<Map<String, Any?>>)?.let { schemes ->
                    appendLine()
                    appendLine("| Scheme | Kendall tau-b | Distinct values | Tied pairs | Edges where worse looks better |")
                    appendLine("|---|---:|---:|---:|---:|")
                    schemes.forEach { appendLine("| ${it["name"]} | ${it["tauB"]} | ${it["distinctValues"]} | ${it["tiedFraction"]} | ${it["edgesWhereWorseLooksBetter"]} / ${it["edges"]} |") }
                }
            })
        }
    }
}
