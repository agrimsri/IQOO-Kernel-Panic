package com.dpdpxray.cli

import com.dpdpxray.core.model.Finding
import com.dpdpxray.core.model.Severity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** SARIF 2.1.0 for GitHub code scanning: one result per re-evaluated finding, located in its bundle file. */
object CheckSarif {
    const val SCHEMA_URI = "https://json.schemastore.org/sarif-2.1.0.json"

    fun level(s: Severity): String = when (s) {
        Severity.CRITICAL, Severity.HIGH -> "error"
        Severity.MEDIUM -> "warning"
        Severity.LOW, Severity.INFO -> "note"
    }

    fun render(checks: List<BundleCheck>): JsonObject {
        val rules = checks.flatMap { it.current.findings }.distinctBy { it.checkId }.sortedBy { it.checkId.ordinal }
        val index = rules.mapIndexed { i, f -> f.checkId to i }.toMap()
        return buildJsonObject {
            put("\$schema", SCHEMA_URI)
            put("version", "2.1.0")
            put("runs", buildJsonArray {
                add(buildJsonObject {
                    put("tool", buildJsonObject {
                        put("driver", buildJsonObject {
                            put("name", CliInfo.NAME)
                            put("version", CliInfo.version)
                            put("rules", JsonArray(rules.map(::rule)))
                        })
                    })
                    put("results", buildJsonArray {
                        checks.forEach { c -> c.current.findings.forEach { f -> add(result(c, f, index.getValue(f.checkId))) } }
                    })
                    put("invocations", buildJsonArray {
                        add(buildJsonObject {
                            put("executionSuccessful", true)
                            put("properties", buildJsonObject {
                                put("policyPassed", checks.all { it.passed })
                                put("policyViolations", JsonArray(checks.flatMap { c -> c.violations.map { JsonPrimitive("${c.source.displayPath}: ${it.rule}: ${it.message}") } }))
                                // The hardware signature next to each bundle: verified, failed or absent (docs/SIGNED_EVIDENCE.md).
                                put("signatures", buildJsonArray {
                                    checks.forEach { c ->
                                        c.signature?.let { s ->
                                            add(buildJsonObject {
                                                put("bundle", c.source.displayPath)
                                                put("status", s.state.wire)
                                                put("file", s.path)
                                                put("text", s.summary)
                                            })
                                        }
                                    }
                                })
                            })
                        })
                    })
                })
            })
        }
    }

    private fun rule(f: Finding) = buildJsonObject {
        put("id", f.checkId.code)
        put("name", f.checkId.name)
        put("shortDescription", text(f.checkId.title))
        put("fullDescription", text("TraceVeil check ${f.checkId.code}: ${f.checkId.title}. ${Wording.LOOKUP_CAVEAT}"))
        put("help", text(
            "Open the audit in TraceVeil (or its HTML report) for the full evidence timeline and suggested fixes, then " +
                "re-capture on a phone and export the audit again. ${Wording.SCOPE_CAVEAT}",
        ))
        put("defaultConfiguration", buildJsonObject { put("level", level(f.severity)) })
        put("properties", buildJsonObject { put("tags", buildJsonArray { add(JsonPrimitive("privacy")); add(JsonPrimitive("consent")) }) })
    }

    private fun result(c: BundleCheck, f: Finding, ruleIndex: Int) = buildJsonObject {
        val sample = if (c.source.sample) "[synthetic sample] " else ""
        put("ruleId", f.checkId.code)
        put("ruleIndex", ruleIndex)
        put("level", level(f.severity))
        put("message", text(
            "$sample${f.checkId.code} ${f.title} (${f.severity.label}, ${Wording.evidenceMeaning(f.evidenceType)}) in " +
                "${c.source.bundle.app.label} (${c.source.bundle.app.packageName}). First evidence: ${Wording.firstEvidence(f)}. " +
                Wording.LOOKUP_CAVEAT,
        ))
        put("locations", buildJsonArray {
            add(buildJsonObject {
                put("physicalLocation", buildJsonObject {
                    put("artifactLocation", buildJsonObject { put("uri", c.source.uri) })
                    put("region", buildJsonObject { put("startLine", lineOf(c.source.text, f.checkId.code)) })
                })
            })
        })
        put("partialFingerprints", buildJsonObject { put("traceveilFinding/v1", "${c.source.bundle.app.packageName}/${f.checkId.code}") })
        put("properties", buildJsonObject {
            put("severity", f.severity.name)
            put("evidenceLabel", f.evidenceType.name)
            put("evidence", JsonArray(f.evidence.map(::JsonPrimitive)))
            put("entities", JsonArray(f.entities.map(::JsonPrimitive)))
            put("plans", JsonArray(f.plans.map { JsonPrimitive(it.name) }))
            c.signature?.let { put("signature", it.state.wire) }
        })
    }

    /** Line of the stored finding for [code] in the bundle text (1 when the stored result does not have it). */
    fun lineOf(text: String, code: String): Int {
        val pattern = Regex("\"checkId\"\\s*:\\s*\"${Regex.escape(code)}\"")
        val idx = text.lineSequence().indexOfFirst { pattern.containsMatchIn(it) }
        return if (idx >= 0) idx + 1 else 1
    }

    private fun text(s: String) = buildJsonObject { put("text", s) }
}
