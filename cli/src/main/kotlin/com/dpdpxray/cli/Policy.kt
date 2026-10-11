package com.dpdpxray.cli

import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.CheckId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Release-gate rules, read from policy.json. Every key is optional and falls back to the default shown here:
 *
 * - failOn ["C2", "C9"]: fail when the re-evaluated result contains any of these checks (the two Critical checks).
 * - allowNotEstablished false: a failOn check the captured plans could not test (NOT ESTABLISHED) fails the gate;
 *   set true to report it without failing.
 * - minScore null: no score threshold unless set (0–100).
 * - maxHigh null: no limit unless set; counts findings at High severity or above (High + Critical).
 * - requireFreshState true: every plan must have started from an approved fresh reset; "not recorded" does not count.
 * - allowInconclusive false: an inconclusive result (low network visibility) cannot pass.
 * - requirePlans ["ACCEPT", "REJECT", "SILENT"]: these plans must be captured, and an Accept or Reject plan counts only
 *   when that choice was recorded (with no consent screen, or no recorded choice, behaviour after it was not tested).
 * - requireSignature false: the hardware signature next to a bundle (<bundle>.sig.json, traceveil.signature.v1) is
 *   always reported; set true to fail a bundle whose signature is missing or does not verify.
 * - requireReady false: set true to require the top (85–100) band. A result whose consent history is unknown is a
 *   range: it passes only when even its worst end is in the top band, and fails when its best band is lower.
 *
 * Unknown keys are an error, so a typo cannot silently weaken the gate. Keys starting with "_" or "$" are comments.
 */
data class Policy(
    val failOn: List<CheckId> = DEFAULT_FAIL_ON,
    val minScore: Int? = null,
    val maxHigh: Int? = null,
    val requireFreshState: Boolean = true,
    val allowInconclusive: Boolean = false,
    val requirePlans: List<AuditPlan> = AuditPlan.entries.toList(),
    val allowNotEstablished: Boolean = false,
    val requireSignature: Boolean = false,
    val requireReady: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("failOn", JsonArray(failOn.map { JsonPrimitive(it.code) }))
        put("minScore", minScore)
        put("maxHigh", maxHigh)
        put("requireFreshState", requireFreshState)
        put("allowInconclusive", allowInconclusive)
        put("allowNotEstablished", allowNotEstablished)
        put("requirePlans", JsonArray(requirePlans.map { JsonPrimitive(it.name) }))
        put("requireSignature", requireSignature)
        put("requireReady", requireReady)
    }

    companion object {
        val DEFAULT_FAIL_ON = listOf(CheckId.C2, CheckId.C9)
        val KEYS = listOf("failOn", "minScore", "maxHigh", "requireFreshState", "allowInconclusive", "allowNotEstablished", "requirePlans", "requireSignature", "requireReady")

        /** Throws [UsageException] with a plain reason when the policy cannot be used. */
        fun parse(text: String): Policy {
            val root = runCatching { Json.parseToJsonElement(text.removePrefix("﻿")) }
                .getOrElse { throw UsageException("policy is not valid JSON: ${it.message?.lineSequence()?.firstOrNull().orEmpty()}") }
                as? JsonObject ?: throw UsageException("policy must be a JSON object, e.g. {\"failOn\": [\"C2\"], \"minScore\": 85}")
            for (key in root.keys) {
                if (key.startsWith("_") || key.startsWith("$")) continue
                if (key !in KEYS) throw UsageException("unknown policy key \"$key\" (known keys: ${KEYS.joinToString()})")
            }
            var p = Policy()
            root["failOn"]?.let { p = p.copy(failOn = strings(it, "failOn").map(::checkId).distinct()) }
            root["minScore"]?.let { p = p.copy(minScore = optionalInt(it, "minScore", 0..100)) }
            root["maxHigh"]?.let { p = p.copy(maxHigh = optionalInt(it, "maxHigh", 0..Int.MAX_VALUE)) }
            root["requireFreshState"]?.let { p = p.copy(requireFreshState = bool(it, "requireFreshState")) }
            root["allowInconclusive"]?.let { p = p.copy(allowInconclusive = bool(it, "allowInconclusive")) }
            root["allowNotEstablished"]?.let { p = p.copy(allowNotEstablished = bool(it, "allowNotEstablished")) }
            root["requirePlans"]?.let { p = p.copy(requirePlans = strings(it, "requirePlans").map(::plan).distinct()) }
            root["requireSignature"]?.let { p = p.copy(requireSignature = bool(it, "requireSignature")) }
            root["requireReady"]?.let { p = p.copy(requireReady = bool(it, "requireReady")) }
            return p
        }

        private fun strings(e: JsonElement, key: String): List<String> {
            val arr = e as? JsonArray ?: throw UsageException("$key must be a list of strings")
            return arr.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw UsageException("$key must be a list of strings") }
        }

        private fun checkId(code: String): CheckId = CheckId.entries.firstOrNull { it.code.equals(code.trim(), ignoreCase = true) }
            ?: throw UsageException("failOn: unknown check \"$code\" (known: ${CheckId.entries.joinToString { it.code }})")

        private fun plan(name: String): AuditPlan = AuditPlan.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
            ?: throw UsageException("requirePlans: unknown plan \"$name\" (known: ${AuditPlan.entries.joinToString { it.name }})")

        private fun optionalInt(e: JsonElement, key: String, range: IntRange): Int? {
            if (e == JsonNull) return null
            val v = (e as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: throw UsageException("$key must be a whole number or null")
            if (v !in range) throw UsageException("$key must be between ${range.first} and ${range.last}, not $v")
            return v
        }

        private fun bool(e: JsonElement, key: String): Boolean =
            (e as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw UsageException("$key must be true or false")
    }
}

/** Bad command line, unreadable input or an unusable policy: exit code 2. */
class UsageException(message: String) : Exception(message)
