package com.dpdpxray.core.netcapture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The only thing a language model ever sees from the network capture: a short list of already-redacted candidate
 * findings. No packet captures, request bodies, cookies, tokens or full logs. The model may reword the explanation,
 * rank candidates, and say a low-severity inferred candidate looks ordinary; it can never add a finding, change a
 * destination, number or confidence, or remove a rule-proven tracking finding.
 */
object NetModelBrief {
    const val SYSTEM = "You are a careful privacy analyst. You are given a short list of network findings from an app audit, already " +
        "redacted. Facts marked as observed are true; do not add facts. Never say personal data was stolen or sent unless the evidence " +
        "line says so. Reply with JSON only."

    private const val MAX_CANDIDATES = 12
    private val OVERCLAIMS = listOf("stole", "steal", "exfiltrat", "illegal", "violat", "breach", "leaked", "hack")

    fun prompt(candidates: List<NetFinding>, limitations: List<String>): String = buildString {
        appendLine("Audit limitations: " + limitations.joinToString("; ").ifBlank { "none" })
        appendLine("Candidates (id | kind | destination | entity | category | phase | bytes up/down | confidence | limitation | evidence):")
        for (f in candidates.take(MAX_CANDIDATES)) {
            appendLine(
                listOf(
                    f.id, f.kind.name, f.destination, f.entity ?: "unknown", f.category.name, f.phase.name, "${f.bytesUp}/${f.bytesDown}",
                    f.confidence.name, f.limitation, f.evidence.joinToString(" / "),
                ).joinToString(" | ") { Redaction.value(it).text },
            )
        }
        appendLine()
        appendLine(
            "Return {\"findings\":[{\"id\":<candidate id>,\"verdict\":\"suspicious\"|\"expected\"|\"unsure\",\"priority\":1-5 (5 = most urgent)," +
                "\"why\":<one or two plain sentences on why this matters to the user, no accusations>}]}",
        )
    }

    /** Applies a model reply to [findings]. Anything unparseable or unknown is ignored; the rule output stands. */
    fun apply(findings: List<NetFinding>, reply: String?): List<NetFinding> {
        val verdicts = parse(reply) ?: return findings
        val byId = findings.associateBy { it.id }
        val out = findings.map { f ->
            val v = verdicts[f.id] ?: return@map f
            val canDemote = f.kind in SOFT_KINDS && f.severity == RiskLevel.LOW
            if (v.verdict == "expected" && canDemote) return@map null
            val why = v.why?.takeIf { sane(it) }
            f.copy(whyItMatters = why ?: f.whyItMatters, source = if (why != null) FindingSource.MODEL_ASSISTED else f.source)
        }.filterNotNull()
        val priority = verdicts.mapValues { it.value.priority }
        return out.sortedWith(
            compareByDescending<NetFinding> { it.severity.ordinal }.thenByDescending { priority[it.id] ?: 0 }.thenByDescending { it.confidence.ordinal },
        ).also { check(it.all { f -> f.id in byId }) }
    }

    private val SOFT_KINDS = setOf(NetFindingKind.UNKNOWN_DESTINATION_UPLOAD, NetFindingKind.UNLOOKED_UP_DESTINATION)

    private class Verdict(val verdict: String, val priority: Int, val why: String?)

    private fun sane(s: String) = s.length in 10..320 && OVERCLAIMS.none { s.contains(it, ignoreCase = true) }

    private fun parse(reply: String?): Map<String, Verdict>? {
        if (reply.isNullOrBlank()) return null
        val start = reply.indexOf('{'); val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val arr: JsonArray = Json.parseToJsonElement(reply.substring(start, end + 1)).jsonObject["findings"]?.jsonArray ?: return null
            arr.mapNotNull { e ->
                val o: JsonObject = e.jsonObject
                val id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                id to Verdict(
                    o["verdict"]?.jsonPrimitive?.content?.lowercase() ?: "unsure",
                    runCatching { o["priority"]?.jsonPrimitive?.int }.getOrNull()?.coerceIn(1, 5) ?: 3,
                    o["why"]?.jsonPrimitive?.content,
                )
            }.toMap()
        } catch (_: Exception) {
            null
        }
    }
}
