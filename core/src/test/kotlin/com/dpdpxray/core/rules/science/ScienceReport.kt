package com.dpdpxray.core.rules.science

import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.Finding
import com.dpdpxray.core.model.ScoreBand
import java.io.File

/** Shared helpers for the scoring-science tests: an independent integer oracle, band order and report files. */
object ScienceReport {
    /** Reports land in core/build/reports/score-science (Gradle runs tests with the module directory as working directory). */
    val dir: File = File("build/reports/score-science")

    fun write(name: String, text: String): File {
        dir.mkdirs()
        return File(dir, name).apply { writeText(text, Charsets.UTF_8) }
    }

    /** Band order: At risk < Needs work < Ready. Inconclusive has no place in the order. */
    fun rank(b: ScoreBand): Int = when (b) {
        ScoreBand.AT_RISK -> 0
        ScoreBand.NEEDS_WORK -> 1
        ScoreBand.READY -> 2
        ScoreBand.INCONCLUSIVE -> error("Inconclusive has no rank")
    }

    /** Penalty in half-points: observed weight counts 2, inferred weight counts 1. Exact integer arithmetic. */
    fun penaltyHalfPoints(findings: List<Finding>): Int =
        findings.sumOf { f -> if (f.evidenceType == EvidenceType.OBSERVED) 2 * f.severity.weight else f.severity.weight }

    /**
     * The score written with integers only, independently of the production code: round(max(0, 100 - P)) with
     * P = p2 / 2 and halves rounded up is max(0, floor((201 - p2) / 2)).
     */
    fun oracleScore(p2: Int): Int = Math.floorDiv(201 - p2, 2).coerceAtLeast(0)

    fun json(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> json(k.toString()) + ":" + json(v) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { json(it) }
        is IntArray -> value.joinToString(",", "[", "]")
        is LongArray -> value.joinToString(",", "[", "]")
        else -> json(value.toString())
    }
}
