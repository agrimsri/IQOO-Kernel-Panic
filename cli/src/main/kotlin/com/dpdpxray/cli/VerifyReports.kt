package com.dpdpxray.cli

import com.dpdpxray.core.evidence.CheckStatus
import com.dpdpxray.core.evidence.EvidenceReport
import com.dpdpxray.core.evidence.EvidenceVerifier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One `traceveil-ci verify` result. [report] is null when no signature file was found ([looked] lists where it looked):
 * an unsigned bundle is a failed verification, not a usage error.
 */
class VerifyOutcome(val bundlePath: String, val signaturePath: String?, val looked: List<String>, val report: EvidenceReport?) {
    val verified: Boolean get() = report?.verified == true
    val status: String get() = when {
        report == null -> "absent"
        report.verified -> "verified"
        else -> "failed"
    }
}

object VerifyText {
    private fun mark(s: CheckStatus) = when (s) {
        CheckStatus.PASS -> "✓"
        CheckStatus.FAIL -> "✗"
        CheckStatus.WARN -> "!"
        CheckStatus.INFO -> "i"
    }

    fun render(o: VerifyOutcome): String = buildString {
        appendLine("TraceVeil evidence check · ${CliInfo.NAME} ${CliInfo.version} · offline")
        appendLine("File:           ${o.bundlePath}")
        val r = o.report
        if (r == null) {
            appendLine()
            appendLine("  ✗ Signature file: none found next to the file (looked for ${o.looked.joinToString(" and ")}). Pass --sig <file> if it has another name.")
            appendLine()
            appendLine("Result: NOT VERIFIED (no signature; exit 1)")
            return@buildString
        }
        appendLine("Signature file: ${o.signaturePath}")
        appendLine()
        for (c in r.checks) appendLine("  ${mark(c.status)} ${c.title}: ${c.detail}")
        appendLine()
        val kd = r.keyDescription
        if (r.verified && kd != null) {
            val app = kd.applicationId?.packages?.joinToString(", ") { it.name } ?: "an unnamed app"
            appendLine(
                "Result: VERIFIED (exit 0). These exact bytes were signed by a key generated inside a phone's " +
                    "${kd.attestationSecurityLevel.label}; the attestation says $app created the key; the chain ends at ${r.root?.name}.",
            )
        } else {
            val failed = r.checks.filter { it.status == CheckStatus.FAIL }
            appendLine("Result: NOT VERIFIED (${failed.size} check${if (failed.size == 1) "" else "s"} failed: ${failed.joinToString(", ") { it.title }}; exit 1)")
        }
        appendLine()
        appendLine("Limits:")
        r.limits.forEach { appendLine("  - $it") }
    }
}

object VerifyJson {
    const val SCHEMA = "traceveil.verify.v1"

    fun render(o: VerifyOutcome): JsonObject {
        val r = o.report
        val kd = r?.keyDescription
        val app = kd?.applicationId
        return JsonObject(
            linkedMapOf(
                "schema" to JsonPrimitive(SCHEMA),
                "tool" to JsonPrimitive("${CliInfo.NAME} ${CliInfo.version}"),
                "file" to JsonPrimitive(o.bundlePath),
                "signatureFile" to (o.signaturePath?.let { JsonPrimitive(it) } ?: JsonNull),
                "looked" to JsonArray(o.looked.map { JsonPrimitive(it) }),
                "verified" to JsonPrimitive(o.verified),
                "status" to JsonPrimitive(o.status),
                "securityLevel" to (kd?.let { JsonPrimitive(it.attestationSecurityLevel.wire) } ?: JsonNull),
                "keyOrigin" to (kd?.hardwareEnforced?.origin?.let { JsonPrimitive(it.name) } ?: JsonNull),
                "root" to (r?.root?.let { JsonObject(mapOf("name" to JsonPrimitive(it.name), "publicKeySha256" to JsonPrimitive(it.publicKeySha256))) } ?: JsonNull),
                "app" to (
                    app?.let {
                        JsonObject(
                            mapOf(
                                "packages" to JsonArray(it.packages.map { p -> JsonObject(mapOf("name" to JsonPrimitive(p.name), "version" to JsonPrimitive(p.version))) }),
                                "signatureDigestsSha256" to JsonArray(it.signatureDigestsHex.map { d -> JsonPrimitive(d) }),
                            ),
                        )
                    } ?: JsonNull
                    ),
                "checks" to JsonArray(
                    r?.checks.orEmpty().map { c ->
                        JsonObject(mapOf("id" to JsonPrimitive(c.id), "status" to JsonPrimitive(c.status.name), "title" to JsonPrimitive(c.title), "detail" to JsonPrimitive(c.detail)))
                    },
                ),
                "limits" to JsonArray((r?.limits ?: EvidenceVerifier.LIMITS).map { JsonPrimitive(it) }),
            ),
        )
    }
}
