package com.dpdpxray.core.rules.science

import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.Finding
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.Severity
import com.dpdpxray.core.rules.ScoreCalculator
import com.dpdpxray.core.rules.science.ScienceReport.json
import com.dpdpxray.core.rules.science.ScienceReport.oracleScore
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive verification over every combination of the real checks, each in every severity and evidence label the rules
 * engine can give it (CheckVariants), with the production ScoreCalculator.
 *
 * The full space has 209,952 assignments of the 13 checks that carry points (ScoreSpace) times 192 combinations of the 7
 * checks that never carry points: V0 (absent/present) x U1 (absent / no trackers / with trackers) x 2^5 notes (S1, C11,
 * C12, C15, V1) = 40,310,784 finding sets.
 *
 * 1. Each of the 209,952 scored assignments: score, band, the gate and an independent integer oracle.
 * 2. Every edge of the lattice (add one finding, or confirm an inferred one / raise C14 from Info to Low): score and band
 *    never improve. Monotonicity along edges gives it for every superset by transitivity.
 * 3. The zero-weight checks. ScoreCalculator reads a finding set only through three per-finding folds (any V0 or U1 with
 *    trackers; the sum of weight x multiplier; any observed High or Critical), and a zero-weight check adds 0 to the sum and
 *    is never serious. So it is enough, and fast, to check each scored assignment with each of the 8 zero-weight variants
 *    alone, with all of them together (with and without V0 / U1 with trackers) and with one seeded random combination of
 *    the 192: 13 sets per assignment, 2,729,376 in all. Inconclusive ones must give no score; the others exactly the scored
 *    assignment's result. Set TRACEVEIL_SCORE_FULL=1 to evaluate all 40,310,784 sets instead (14 to 80 s on the team
 *    laptop; it passed twice on 10 October 2026).
 *
 * The counts and the band distribution are written to core/build/reports/score-science/exhaustive.{json,md}.
 */
class ScoreExhaustiveTest {
    private val space = ScoreSpace
    private val t get() = space.table

    @Test
    fun `dominance - each severity weight is greater than the sum of all lower weights`() {
        val w = Severity.entries.map { it.weight }  // Critical, High, Medium, Low, Info
        assertEquals(listOf(30, 15, 8, 3, 0), w)
        for (i in w.indices) assertTrue("weight ${w[i]} > ${w.drop(i + 1)}", w[i] == 0 || w[i] > w.drop(i + 1).sum())
        // Meaning: one finding of a severity costs more than one finding of each lower severity together.
        fun obs(vararg s: Severity) = ScoreCalculator.compute(s.mapIndexed { i, sev -> f(CheckId.entries[i], sev, EvidenceType.OBSERVED) }).first!!
        assertTrue(obs(Severity.CRITICAL) < obs(Severity.HIGH, Severity.MEDIUM, Severity.LOW))  // 70 < 74
        assertTrue(obs(Severity.HIGH) < obs(Severity.MEDIUM, Severity.LOW))                    // 85 < 89
        assertTrue(obs(Severity.MEDIUM) < obs(Severity.LOW))                                   // 92 < 97
        assertEquals(listOf(70, 74, 85, 89, 92, 97), listOf(obs(Severity.CRITICAL), obs(Severity.HIGH, Severity.MEDIUM, Severity.LOW),
            obs(Severity.HIGH), obs(Severity.MEDIUM, Severity.LOW), obs(Severity.MEDIUM), obs(Severity.LOW)))
        // It is not a lexicographic order: dominance holds for ONE finding of each lower severity, not for many.
        assertEquals(obs(Severity.CRITICAL), obs(Severity.HIGH, Severity.HIGH))                 // 70 = 70: two High tie one Critical
        assertTrue(obs(Severity.MEDIUM, Severity.MEDIUM, Severity.MEDIUM, Severity.MEDIUM) < obs(Severity.CRITICAL))  // 68 < 70
    }

    private fun f(id: CheckId, s: Severity, e: EvidenceType) = Variant(id, s, e).finding

    @Test
    fun `every scored assignment - bounds, oracle, band rule and gate`() {
        val n = space.size
        assertEquals(209_952, n)
        for (i in 0 until n) {
            val s = t.score[i]
            assertTrue("#$i", s in 0..100)
            assertEquals("#$i oracle", oracleScore(t.p2[i]), s)
            val expected = when {
                s >= 85 && !t.serious[i] -> 2
                s >= 60 -> 1
                else -> 0
            }
            assertEquals("#$i band", expected, t.band[i].toInt())
            if (t.serious[i]) assertTrue("#$i gate", t.band[i].toInt() != 2)
        }
        assertEquals(100, t.score[0])
        assertEquals(2, t.band[0].toInt())
    }

    @Test
    fun `every lattice edge - adding or strengthening a finding never improves score or band`() {
        var edges = 0L
        for (i in 0 until space.size) {
            for (k in space.checks.indices) {
                val d = space.digit(i, k)
                val vs = space.variants[k]
                // Add a finding: absent -> each variant.
                if (d == 0) for (v in 1..vs.size) {
                    val j = i + v * space.stride[k]
                    assertTrue("add ${vs[v - 1].code} to #$i", t.score[j] <= t.score[i] && t.band[j] <= t.band[i])
                    edges++
                }
                // Strengthen a finding: same check, weight not lower, and not from observed to inferred.
                if (d > 0) for (v in 1..vs.size) {
                    if (v == d) continue
                    val from = vs[d - 1]
                    val to = vs[v - 1]
                    val stronger = to.severity.weight >= from.severity.weight &&
                        (to.evidence == EvidenceType.OBSERVED || from.evidence == EvidenceType.INFERRED)
                    if (!stronger) continue
                    val j = i + (v - d) * space.stride[k]
                    assertTrue("${from.code} -> ${to.code} in #$i", t.score[j] <= t.score[i] && t.band[j] <= t.band[i])
                    edges++
                }
            }
        }
        report["latticeEdgesChecked"] = edges
        assertTrue(edges > 1_000_000)
    }

    @Test
    fun `every zero-weight check - inconclusive precedence and neutral notes`() {
        val v0 = CheckVariants.table.getValue(CheckId.V0).single().finding
        val u1 = CheckVariants.table.getValue(CheckId.U1)  // [no trackers, with trackers]
        val notes = CheckVariants.notes.map { CheckVariants.table.getValue(it).single().finding }
        data class Combo(val extra: List<Finding>, val inconclusive: Boolean)
        val all192 = buildList {
            for (hasV0 in listOf(false, true)) for (u in -1..1) for (mask in 0 until (1 shl notes.size)) {
                val extra = buildList {
                    if (hasV0) add(v0)
                    if (u >= 0) add(u1[u].finding)
                    notes.forEachIndexed { b, note -> if (mask and (1 shl b) != 0) add(note) }
                }
                add(Combo(extra, hasV0 || u == 1))
            }
        }
        assertEquals(192, all192.size)
        assertEquals(128, all192.count { it.inconclusive })
        val full = System.getenv("TRACEVEIL_SCORE_FULL") == "1"
        val quiet = notes + u1[0].finding
        val fixed = buildList {
            (quiet + v0 + u1[1].finding).forEach { z -> add(Combo(listOf(z), z === v0 || z === u1[1].finding)) }
            add(Combo(quiet, false))
            add(Combo(quiet + v0, true))
            add(Combo(quiet + u1[1].finding - u1[0].finding, true))
            add(Combo(quiet + v0 + u1[1].finding - u1[0].finding, true))
        }
        assertEquals(12, fixed.size)
        val rnd = kotlin.random.Random(ScorePropertiesTest.seed + 192)
        val start = System.nanoTime()
        val buf = ArrayList<Finding>(32)
        var sets = 0L
        for (i in 0 until space.size) {
            val core = space.findings(i)
            val combos = if (full) all192 else fixed + all192[rnd.nextInt(all192.size)]
            for (c in combos) {
                buf.clear(); buf.addAll(core); buf.addAll(c.extra)
                val (s, b) = ScoreCalculator.compute(buf)
                if (c.inconclusive) {
                    if (s != null || b != ScoreBand.INCONCLUSIVE) throw AssertionError("#$i + ${c.extra.map { it.checkId }}: expected inconclusive, got $s $b")
                } else if (s != t.score[i] || ScienceReport.rank(b) != t.band[i].toInt()) {
                    throw AssertionError("#$i + ${c.extra.map { it.checkId }}: $s $b differs from ${t.score[i]}")
                }
                sets++
            }
        }
        assertEquals(if (full) 40_310_784L else 2_729_376L, sets)
        report["zeroWeightMode"] = if (full) "full product (all 192 combinations per assignment)" else "factored (13 combinations per assignment)"
        report["zeroWeightSetsEvaluated"] = sets
        report["zeroWeightSeconds"] = "%.1f".format(java.util.Locale.ROOT, (System.nanoTime() - start) / 1e9)
    }

    @Test
    fun `robustness interval on every scored assignment - sandwich, stable band iff both ends agree, midpoint`() {
        var movable = 0
        var bandCouldChange = 0
        var readyCouldLeave = 0
        var readyWithInferred = 0
        val width = IntArray(101)
        for (i in 0 until space.size) {
            val fs = space.findings(i)
            val r = ScoreCalculator.robustness(fs)!!
            val s = t.score[i]
            assertTrue("#$i", r.score == s && ScienceReport.rank(r.band) == t.band[i].toInt())
            assertTrue("#$i sandwich", r.worstCase <= s && s <= r.bestCase)
            assertTrue("#$i bands", ScienceReport.rank(r.worstBand) <= t.band[i] && t.band[i] <= ScienceReport.rank(r.bestBand))
            assertEquals("#$i stable", r.worstBand == r.bestBand, r.bandStable)
            if (r.worstCase > 0) assertEquals("#$i midpoint", (r.worstCase + r.bestCase + 1) / 2, s)
            // Both ends are whole numbers: observed and fully counted weights are integers, so no rounding happens there.
            val pObserved = fs.filter { it.evidenceType == EvidenceType.OBSERVED }.sumOf { it.severity.weight }
            val pInferred = fs.filter { it.evidenceType == EvidenceType.INFERRED }.sumOf { it.severity.weight }
            assertEquals("#$i best", maxOf(0, 100 - pObserved), r.bestCase)
            assertEquals("#$i worst", maxOf(0, 100 - pObserved - pInferred), r.worstCase)
            if (r.movable) movable++
            if (!r.bandStable) bandCouldChange++
            if (r.band == ScoreBand.READY && r.inferred.isNotEmpty()) readyWithInferred++
            if (r.band == ScoreBand.READY && r.worstBand != ScoreBand.READY) readyCouldLeave++
            width[r.bestCase - r.worstCase]++
        }
        report["robustness"] = mapOf(
            "movable" to movable, "bandCouldChange" to bandCouldChange, "readyWithInferred" to readyWithInferred,
            "readyCouldLeaveReady" to readyCouldLeave, "widthHistogram" to width,
        )
    }

    @Test
    fun `corollary - a legal lens score is never below the headline score and its band never worse`() {
        val lens = com.dpdpxray.core.law.LegalLens.default
        var views = 0L
        for (i in 0 until space.size) {
            val result = com.dpdpxray.core.model.AuditResult(space.findings(i), t.score[i], ScoreBand.entries.first { ScienceReport.rank(it) == t.band[i].toInt() && it != ScoreBand.INCONCLUSIVE })
            for (p in lens.profiles) {
                val v = lens.view(result, p)
                assertTrue("#$i $p", v.score!! >= t.score[i] && ScienceReport.rank(v.band) >= t.band[i])
                views++
            }
        }
        report["lensViewsChecked"] = views
    }

    @Test
    fun `band distribution and gate effect are written to the report`() {
        val n = space.size
        val count = IntArray(3)
        val min = IntArray(3) { 101 }
        val max = IntArray(3) { -1 }
        val hist = IntArray(101)
        var gateMoved = 0
        var clampedAtZero = 0
        var halfPoint = 0
        var roundedIntoReady = 0
        var roundedIntoNeedsWork = 0
        for (i in 0 until n) {
            val b = t.band[i].toInt()
            val s = t.score[i]
            count[b]++
            min[b] = minOf(min[b], s); max[b] = maxOf(max[b], s)
            hist[s]++
            if (s >= 85 && t.serious[i]) gateMoved++
            if (t.p2[i] >= 200) clampedAtZero++
            if (t.p2[i] % 2 == 1 && t.p2[i] < 200) halfPoint++
            // Bands are decided on the shown (rounded) score: 84.5 shows as 85 and 59.5 as 60.
            if (t.p2[i] == 31 && !t.serious[i]) roundedIntoReady++
            if (t.p2[i] == 81) roundedIntoNeedsWork++
        }
        // The gate only matters when exactly one observed High is the sole finding that carries points: 100 - 15 = 85.
        val gateCases = (0 until n).filter { t.score[it] >= 85 && t.serious[it] }
        assertTrue(gateCases.all { t.score[it] == 85 })
        assertEquals(8, gateMoved)  // 4 checks can be an observed High (C1, NC, C3, C4) x C14 absent or Info
        val names = listOf("AT_RISK", "NEEDS_WORK", "READY")
        report["scoredAssignments"] = n
        report["bands"] = names.indices.associate { names[it] to mapOf("count" to count[it], "minScore" to min[it], "maxScore" to max[it]) }
        report["gateMovedReadyToNeedsWork"] = gateMoved
        report["gateMovedExamples"] = gateCases.map { i -> space.findings(i).joinToString(" ") { "${it.checkId.code}:${it.severity.label}/${it.evidenceType.name.lowercase()}" } }
        report["clampedAtZero"] = clampedAtZero
        report["halfPointRoundedUp"] = halfPoint
        report["roundedFrom84_5IntoReady"] = roundedIntoReady
        report["roundedFrom59_5IntoNeedsWork"] = roundedIntoNeedsWork
        report["distinctScores"] = hist.count { it > 0 }
        report["scoreHistogram"] = hist
        report["checks"] = space.checks.indices.map { k -> mapOf("check" to space.checks[k].code, "variants" to space.variants[k].map { it.code }) }
        report["zeroWeightCombinations"] = 192
        report["fullSpace"] = n.toLong() * 192
    }

    companion object {
        private val report = sortedMapOf<String, Any?>()

        /** Written after all tests, so the counts of every test above are in it. */
        @JvmStatic
        @AfterClass
        fun writeReport() {
            if (report["bands"] == null) return
            ScienceReport.write("exhaustive.json", json(report))
            @Suppress("UNCHECKED_CAST")
            val bands = report["bands"] as Map<String, Map<String, Int>>
            ScienceReport.write(
                "exhaustive.md",
                buildString {
                    appendLine("# Exhaustive score verification")
                    appendLine()
                    appendLine("Scored assignments (13 checks, every engine variant): ${report["scoredAssignments"]}")
                    appendLine("Full space with the 192 zero-weight combinations: ${report["fullSpace"]}")
                    appendLine()
                    appendLine("| Band | Assignments | Min score | Max score |")
                    appendLine("|---|---:|---:|---:|")
                    for ((name, v) in bands) appendLine("| $name | ${v["count"]} | ${v["minScore"]} | ${v["maxScore"]} |")
                    appendLine()
                    appendLine("Gate moved from Ready to Needs work: ${report["gateMovedReadyToNeedsWork"]} (${report["gateMovedExamples"]})")
                    appendLine("Lattice edges checked: ${report["latticeEdgesChecked"]}; zero-weight check: ${report["zeroWeightSetsEvaluated"]} sets, ${report["zeroWeightMode"]}, ${report["zeroWeightSeconds"]} s")
                    (report["robustness"] as? Map<*, *>)?.let { r ->
                        appendLine("Robustness: movable ${r["movable"]}; band could change ${r["bandCouldChange"]}; Ready with an inferred finding ${r["readyWithInferred"]}, of which could leave Ready ${r["readyCouldLeaveReady"]}")
                    }
                    appendLine("Clamped at 0: ${report["clampedAtZero"]}; half-point scores rounded up: ${report["halfPointRoundedUp"]} (84.5 -> 85 Ready: ${report["roundedFrom84_5IntoReady"]}; 59.5 -> 60 Needs work: ${report["roundedFrom59_5IntoNeedsWork"]}); distinct scores: ${report["distinctScores"]}")
                    appendLine("Legal lens views checked (score never below the headline): ${report["lensViewsChecked"]}")
                },
            )
        }
    }
}
