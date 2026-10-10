package com.dpdpxray.core.automation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.temporal.ChronoUnit

@Serializable
enum class RunState {
    @SerialName("running") RUNNING,
    @SerialName("done") DONE,
    @SerialName("failed") FAILED,
}

/**
 * automation/<runId>/status.json, schema "traceveil.automation.status.v1". The phone rewrites it atomically: at once
 * when a request is accepted or refused, on each audit step, and when the audit is saved (done) or stops (failed).
 *
 * "done" means the audit finished and was saved, not that the app passed: the laptop decides PASS or FAIL from
 * bundle.json with its policy. [score] is null when the result is inconclusive (unknown, never zero). [band] is the
 * core ScoreBand name. Times are ISO-8601 UTC to the second. The pairing token is never written here.
 */
@Serializable
data class AutomationStatus(
    val schema: String = AutomationStatuses.SCHEMA,
    val runId: String,
    val state: RunState,
    /** Why the run failed or was refused, in plain words. */
    val reason: String? = null,
    /** [RefusalCode] name, for scripts. */
    val reasonCode: String? = null,
    /** What to change on the phone (or in the command) before running again. */
    val fix: String? = null,
    @SerialName("package") val packageName: String? = null,
    val mode: String? = null,
    val autoTap: Boolean? = null,
    val childFacing: Boolean? = null,
    /** Each plan starts from cleared app data (allowlisted test app, Shizuku ready). */
    val resetAppData: Boolean? = null,
    /** The audit step shown on the phone's live screen. */
    val step: String? = null,
    val auditId: String? = null,
    val score: Int? = null,
    val band: String? = null,
    val findingCount: Int? = null,
    /** [BundleSignatures] HMAC of bundle.json, set with "done". */
    val bundleSignature: String? = null,
    val startedAt: String,
    val updatedAt: String,
    val finishedAt: String? = null,
    val appVersion: String,
)

object AutomationStatuses {
    const val SCHEMA = "traceveil.automation.status.v1"

    /** Every field is written (null included) so a reader always sees the same keys; unknown keys are ignored. */
    val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    }

    fun timestamp(epochMs: Long): String = Instant.ofEpochMilli(epochMs).truncatedTo(ChronoUnit.SECONDS).toString()

    fun running(request: RunRequest, resetAppData: Boolean, startedMs: Long, appVersion: String, step: String = "Starting"): AutomationStatus {
        val at = timestamp(startedMs)
        return AutomationStatus(
            runId = request.runId, state = RunState.RUNNING, packageName = request.packageName, mode = request.mode.wire,
            autoTap = request.autoTap, childFacing = request.childFacing, resetAppData = resetAppData, step = step,
            startedAt = at, updatedAt = at, appVersion = appVersion,
        )
    }

    fun step(status: AutomationStatus, step: String, nowMs: Long) = status.copy(step = step, updatedAt = timestamp(nowMs))

    fun done(
        status: AutomationStatus, auditId: String, score: Int?, band: String, findingCount: Int, nowMs: Long,
        bundleSignature: String? = null,
    ): AutomationStatus {
        val at = timestamp(nowMs)
        return status.copy(
            state = RunState.DONE, reason = null, reasonCode = null, fix = null, step = "Finished and saved",
            auditId = auditId, score = score, band = band, findingCount = findingCount, bundleSignature = bundleSignature,
            updatedAt = at, finishedAt = at,
        )
    }

    fun failed(status: AutomationStatus, code: RefusalCode, reason: String, fix: String, nowMs: Long): AutomationStatus {
        val at = timestamp(nowMs)
        return status.copy(state = RunState.FAILED, reason = reason, reasonCode = code.name, fix = fix, updatedAt = at, finishedAt = at)
    }

    /** A refused request. Only validated values are echoed: an invalid package or mode is left out, never copied. */
    fun refused(refusal: RunDecision.Refused, input: RunRequestInput, nowMs: Long, appVersion: String): AutomationStatus {
        val runId = requireNotNull(refusal.runId) { "a refusal without a run id has nowhere to be written" }
        val at = timestamp(nowMs)
        return AutomationStatus(
            runId = runId, state = RunState.FAILED, reason = refusal.reason, reasonCode = refusal.code.name, fix = refusal.fix,
            packageName = input.packageName?.takeIf(AutomationContract::isValidPackage),
            mode = AutomationMode.parse(input.mode)?.wire,
            startedAt = at, updatedAt = at, finishedAt = at, appVersion = appVersion,
        )
    }

    fun encode(status: AutomationStatus): String = json.encodeToString(AutomationStatus.serializer(), status)

    /** Throws [IllegalArgumentException] with a plain reason when [text] is not a readable status of this schema. */
    fun decode(text: String): AutomationStatus {
        val parsed = try {
            json.parseToJsonElement(text.removePrefix("﻿"))
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("status.json is not valid JSON", e)
        }
        val root = parsed as? JsonObject ?: throw IllegalArgumentException("status.json is not a JSON object")
        val schema = (root["schema"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (schema != SCHEMA) throw IllegalArgumentException("status.json has schema \"${schema ?: "(none)"}\", expected \"$SCHEMA\"")
        return try {
            json.decodeFromJsonElement(AutomationStatus.serializer(), root)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("status.json could not be read: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}", e)
        }
    }
}
