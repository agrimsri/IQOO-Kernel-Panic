package com.dpdpxray.core.rules.science

import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.Finding
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.Severity
import com.dpdpxray.core.rules.ScoreCalculator
import com.dpdpxray.core.rules.science.ScienceReport.oracleScore
import com.dpdpxray.core.rules.science.ScienceReport.penaltyHalfPoints
import com.dpdpxray.core.rules.science.ScienceReport.rank
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Property-based tests of ScoreCalculator with a seeded kotlin.random.Random (no extra library). Every property runs on
 * [SETS] random finding sets from each of two generators:
 *
 * - realistic: each check of the real catalogue (CheckVariants) at most once, in a severity and evidence label the rules
 *   engine can actually give it;
 * - free: 0 to 25 findings with any check, any severity and any evidence label, duplicates allowed (stress beyond what the
 *   engine emits, including penalties far above 100).
 *
 * The seed is printed; set TRACEVEIL_SCORE_SEED to rerun with another seed or to reproduce a failure. Every assertion
 * message carries the seed, the set number and the set itself.
 */
class ScorePropertiesTest {
    companion object {
        const val DEFAULT_SEED = 20_261_010L
        const val SETS = 12_500
        val seed: Long = System.getenv("TRACEVEIL_SCORE_SEED")?.toLongOrNull() ?: DEFAULT_SEED
        private val checked = sortedMapOf<String, Long>()

        init {
            println("ScorePropertiesTest seed=$seed (set TRACEVEIL_SCORE_SEED=$seed to reproduce; $SETS sets per generator per property)")
        }

        @JvmStatic
        @AfterClass
        fun writeReport() {
            ScienceReport.write(
                "properties.json",
                ScienceReport.json(mapOf("seed" to seed, "setsPerGenerator" to SETS, "generators" to listOf("realistic", "free"), "checks" to checked)),
            )
        }

        @Synchronized private fun count(property: String, n: Long) { checked[property] = (checked[property] ?: 0L) + n }
    }

    private val weighted = Severity.entries.filter { it.weight > 0 }
    private val conclusiveChecks = CheckId.entries.filter { it != CheckId.V0 && it != CheckId.U1 }
    private val v0 = Variant(CheckId.V0, Severity.INFO, EvidenceType.OBSERVED).finding
    private val u1Trackers = Variant(CheckId.U1, Severity.INFO, EvidenceType.OBSERVED, withEntities = true).finding
    private val u1Quiet = Variant(CheckId.U1, Severity.INFO, EvidenceType.OBSERVED).finding

    private fun f(id: CheckId, s: Severity, e: EvidenceType) = Variant(id, s, e).finding

    /** Each check at most once, in a variant the engine can give; never V0 or U1 with trackers (those are inconclusive). */
    private fun realistic(rnd: Random): List<Finding> {
        val p = rnd.nextDouble(0.05, 0.7)
        return CheckVariants.table.flatMap { (id, variants) ->
            val allowed = variants.filterNot { id == CheckId.V0 || it.withEntities }
            if (allowed.isEmpty() || rnd.nextDouble() >= p) emptyList() else listOf(allowed.random(rnd).finding)
        }.shuffled(rnd)
    }

    private fun freeFinding(rnd: Random) =
        f(conclusiveChecks.random(rnd), Severity.entries.random(rnd), if (rnd.nextBoolean()) EvidenceType.OBSERVED else EvidenceType.INFERRED)

    private fun free(rnd: Random): List<Finding> = List(rnd.nextInt(0, 26)) { freeFinding(rnd) }

    /** A finding to add to [set]: for realistic sets a variant of a check not yet present, otherwise any finding. */
    private fun addable(set: List<Finding>, realistic: Boolean, rnd: Random): Finding? = if (realistic) {
        val present = set.map { it.checkId }.toSet()
        CheckVariants.all.filter { it.check !in present && it.check != CheckId.V0 && !it.withEntities }.randomOrNull(rnd)?.finding
    } else freeFinding(rnd)

    private fun codes(set: List<Finding>) = set.joinToString(prefix = "[", postfix = "]") {
        "${it.checkId.code}:${it.severity.label}/${it.evidenceType.name.lowercase()}" + if (it.entities.isNotEmpty()) "+entities" else ""
    }

    /** Runs [body] on SETS realistic and SETS free sets with a generator seeded from the run seed and [salt]. */
    private fun forEachSet(property: String, salt: Int, body: (set: List<Finding>, realistic: Boolean, rnd: Random, where: String) -> Unit) {
        for (realistic in listOf(true, false)) {
            val rnd = Random(seed * 31 + salt * 2 + if (realistic) 0 else 1)
            repeat(SETS) { i ->
                val set = if (realistic) realistic(rnd) else free(rnd)
                body(set, realistic, rnd, "seed=$seed ${if (realistic) "realistic" else "free"} set#$i ${codes(set)}")
            }
        }
        count(property, 2L * SETS)
    }

    private fun score(set: List<Finding>) = ScoreCalculator.compute(set)

    @Test
    fun `P1 bounds - every conclusive score is an integer from 0 to 100 with one of the three bands`() =
        forEachSet("P1 bounds", 1) { set, _, _, where ->
            val (s, b) = score(set)
            assertTrue(where, s != null && s in 0..100)
            assertTrue(where, b in setOf(ScoreBand.READY, ScoreBand.NEEDS_WORK, ScoreBand.AT_RISK))
        }

    @Test
    fun `P2 determinism - the same findings always give the same result`() =
        forEachSet("P2 determinism", 2) { set, _, _, where ->
            val first = score(set)
            assertEquals(where, first, score(set))
            assertEquals(where, first, score(set.map { it.copy() }))
            assertEquals(where, first, score(ArrayList(set)))
        }

    @Test
    fun `P3 permutation invariance - the order of findings never matters`() =
        forEachSet("P3 permutation", 3) { set, _, rnd, where ->
            val base = score(set)
            assertEquals(where, base, score(set.reversed()))
            repeat(3) { assertEquals(where, base, score(set.shuffled(rnd))) }
        }

    @Test
    fun `P4 monotonicity - adding a finding never raises the score and removing one never lowers it`() =
        forEachSet("P4 monotonicity", 4) { set, realistic, rnd, where ->
            val (s, _) = score(set)
            addable(set, realistic, rnd)?.let { extra ->
                val (s2, _) = score(set + extra)
                assertTrue("$where + ${codes(listOf(extra))}: $s -> $s2", s2!! <= s!!)
            }
            if (set.isNotEmpty()) {
                val smaller = set.toMutableList().apply { removeAt(rnd.nextInt(size)) }
                assertTrue("$where minus one: ${score(smaller).first} < $s", score(smaller).first!! >= s!!)
            }
        }

    @Test
    fun `P5 band monotonicity - a superset never gets a better band and a subset never a worse one`() =
        forEachSet("P5 band monotonicity", 5) { set, realistic, rnd, where ->
            val (_, b) = score(set)
            addable(set, realistic, rnd)?.let { extra ->
                assertTrue("$where + ${codes(listOf(extra))}", rank(score(set + extra).second) <= rank(b))
            }
            if (set.isNotEmpty()) {
                val smaller = set.toMutableList().apply { removeAt(rnd.nextInt(size)) }
                assertTrue("$where minus one", rank(score(smaller).second) >= rank(b))
            }
        }

    @Test
    fun `P6 evidence monotonicity - confirming an inferred finding never improves score or band`() =
        forEachSet("P6 evidence monotonicity", 6) { set, _, rnd, where ->
            val inferred = set.indices.filter { set[it].evidenceType == EvidenceType.INFERRED }
            if (inferred.isEmpty()) return@forEachSet
            val i = inferred.random(rnd)
            val confirmed = set.toMutableList().apply { this[i] = this[i].copy(evidenceType = EvidenceType.OBSERVED) }
            val (s, b) = score(set)
            val (s2, b2) = score(confirmed)
            assertTrue("$where confirm #$i", s2!! <= s!! && rank(b2) <= rank(b))
        }

    @Test
    fun `P7 gate - an observed High or Critical finding is never Ready`() =
        forEachSet("P7 gate", 7) { set, _, rnd, where ->
            val serious = f(conclusiveChecks.random(rnd), listOf(Severity.HIGH, Severity.CRITICAL).random(rnd), EvidenceType.OBSERVED)
            val withSerious = (set + serious).shuffled(rnd)
            assertNotEquals("$where + ${codes(listOf(serious))}", ScoreBand.READY, score(withSerious).second)
            if (set.any { it.evidenceType == EvidenceType.OBSERVED && it.severity.weight >= Severity.HIGH.weight }) {
                assertNotEquals(where, ScoreBand.READY, score(set).second)
            }
        }

    @Test
    fun `P8 inferred findings never block Ready on their own but always cost points`() =
        forEachSet("P8 inferred never decides alone", 8) { set, _, rnd, where ->
            val (s, b) = score(set)
            val seriousSeen = set.any { it.evidenceType == EvidenceType.OBSERVED && it.severity.weight >= Severity.HIGH.weight }
            if (!seriousSeen) assertEquals(where, s!! >= 85, b == ScoreBand.READY)
            // Every weight is at least 3, so an inferred finding costs at least 1.5 points: the score drops unless it is already 0.
            val extra = f(conclusiveChecks.random(rnd), weighted.random(rnd), EvidenceType.INFERRED)
            val (s2, _) = score(set + extra)
            if (s!! > 0) assertTrue("$where + ${codes(listOf(extra))}: $s -> $s2", s2!! < s)
        }

    @Test
    fun `P9 exact half weight - the score matches an integer oracle and two inferred findings cost one observed`() =
        forEachSet("P9 exact half weight", 9) { set, _, rnd, where ->
            assertEquals(where, oracleScore(penaltyHalfPoints(set)), score(set).first)
            val sev = weighted.random(rnd)
            val (a, c) = conclusiveChecks.shuffled(rnd).take(2)
            val twoInferred = set + f(a, sev, EvidenceType.INFERRED) + f(c, sev, EvidenceType.INFERRED)
            val oneObserved = set + f(a, sev, EvidenceType.OBSERVED)
            assertEquals("$where + 2 x inferred ${sev.label}", score(oneObserved).first, score(twoInferred).first)
        }

    @Test
    fun `P10 rounding - half points round up, so 92_5 gives 93 and 7_5 gives 8`() {
        // Kotlin's Double.roundToInt() is Math.round: floor(x + 0.5), so every .5 goes up (towards +infinity).
        for (k in 0..99) assertEquals(k + 1, (k + 0.5).roundToInt())
        // 100 - 7.5 = 92.5 -> 93 (one inferred High).
        assertEquals(93 to ScoreBand.READY, score(listOf(f(CheckId.C3, Severity.HIGH, EvidenceType.INFERRED))))
        // LeakyShop with the AI screen reading: 100 - 75 - 17.5 = 7.5 -> 8.
        val leaky = listOf(
            f(CheckId.C1, Severity.HIGH, EvidenceType.OBSERVED), f(CheckId.C2, Severity.CRITICAL, EvidenceType.OBSERVED),
            f(CheckId.C3, Severity.HIGH, EvidenceType.OBSERVED), f(CheckId.C4, Severity.HIGH, EvidenceType.OBSERVED),
            f(CheckId.C5, Severity.MEDIUM, EvidenceType.INFERRED), f(CheckId.C6, Severity.MEDIUM, EvidenceType.INFERRED),
            f(CheckId.C7, Severity.MEDIUM, EvidenceType.INFERRED), f(CheckId.C8, Severity.LOW, EvidenceType.INFERRED),
            f(CheckId.C10, Severity.MEDIUM, EvidenceType.INFERRED),
        )
        assertEquals(185, penaltyHalfPoints(leaky))
        assertEquals(8 to ScoreBand.AT_RISK, score(leaky))
        // Only a half-point can arise (weights are integers, inferred halves them), and it always rounds up.
        forEachSet("P10 rounding", 10) { set, _, _, where ->
            val p2 = penaltyHalfPoints(set)
            if (p2 % 2 == 1 && p2 < 200) assertEquals(where, (201 - p2) / 2, score(set).first)
        }
    }

    @Test
    fun `P11 inconclusive precedence - V0 or U1 with trackers always gives no score, whatever else is present`() =
        forEachSet("P11 inconclusive precedence", 11) { set, _, rnd, where ->
            for (trigger in listOf(v0, u1Trackers)) {
                val with = (set + trigger).shuffled(rnd)
                assertEquals("$where + ${trigger.checkId}", null to ScoreBand.INCONCLUSIVE, score(with))
                assertNull(where, ScoreCalculator.robustness(with))
            }
            // U1 that names no tracker is only a note: it never removes the score.
            assertEquals(where, score(set), score((set + u1Quiet).shuffled(rnd)))
        }

    @Test
    fun `P12 empty findings score 100 Ready`() {
        assertEquals(100 to ScoreBand.READY, score(emptyList()))
        assertEquals(100 to ScoreBand.READY, score(CheckVariants.notes.map { CheckVariants.table.getValue(it).first().finding }))
        count("P12 empty", 2)
    }

    @Test
    fun `P13 robustness - worst le score le best, bands in the same order, score is the rounded-up midpoint`() =
        forEachSet("P13 robustness sandwich", 13) { set, _, _, where ->
            val (s, b) = score(set)
            val r = ScoreCalculator.robustness(set)!!
            assertEquals(where, s to b, r.score to r.band)
            assertTrue(where, r.worstCase <= s!! && s <= r.bestCase)
            assertTrue(where, rank(r.worstBand) <= rank(b) && rank(b) <= rank(r.bestBand))
            assertEquals(where, r.worstBand == r.bestBand, r.bandStable)
            assertEquals(where, r.bestCase > r.worstCase, r.movable)
            if (r.worstCase > 0) assertEquals(where, (r.worstCase + r.bestCase + 1) / 2, s)
            if (r.inferred.isEmpty()) assertTrue(where, r.worstCase == s && r.bestCase == s && r.bandStable)
        }
}
