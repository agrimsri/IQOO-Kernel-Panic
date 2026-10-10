package com.dpdpxray.core.export

import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.quarantine.QuarantineReport
import com.dpdpxray.core.report.AuditJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/*
 * "TraceVeil audit bundle v1": one file that carries a saved audit off the phone, for TraceVeil CI and the web console.
 *
 *   { "schema": "traceveil.audit.v1", "exportedAt": ISO-8601, "app": {"package", "label"}, "meta": <AuditMeta JSON>,
 *     "sessions": [<AuditSession JSON>], "result": <AuditResult JSON>, "networkReadings": [..] or null,
 *     "tool": {"name": "TraceVeil", "version": versionName} }
 *
 * and "traceveil.quarantine.v1" = { "schema", "exportedAt", "report": <saved QuarantineReport JSON> }.
 *
 * Sessions, result and report are written by the same kotlinx serialization settings as the files saved on the phone,
 * so a bundle holds exactly the evidence the app scored. Readers ignore fields they do not know. A missing optional
 * part (meta, networkReadings) reads as unknown, never as zero. Fixtures may add a top-level "note"; it is omitted
 * when unset. "legalProfile" names the legal lens selected when the bundle was exported (a core LegalProfile name such
 * as "EU"); it is omitted when unset and never changes the evidence or the stored result.
 */

/** The audited app named at the top of a bundle. */
@Serializable
data class BundleApp(@SerialName("package") val packageName: String, val label: String)

/** The program and version that wrote a bundle. */
@Serializable
data class BundleTool(val name: String = AuditBundles.TOOL_NAME, val version: String)

@Serializable
data class AuditBundleV1(
    val schema: String = AuditBundles.AUDIT_SCHEMA,
    val exportedAt: String,
    val app: BundleApp,
    /** The app's AuditMeta JSON, kept verbatim (including fields this version does not know). Read it via [AuditBundles.metaOf]. */
    val meta: JsonObject = JsonObject(emptyMap()),
    val sessions: List<AuditSession>,
    val result: AuditResult,
    /** The app's per-plan upload readings, verbatim. null = not recorded (older audits, samples), never "no upload". */
    val networkReadings: JsonArray? = null,
    val tool: BundleTool,
    /** Free text for fixtures and samples (e.g. "synthetic"). Not written when null. */
    val note: String? = null,
    /** The legal lens selected at export (LegalProfile name). Not written when null; readers fall back to their default. */
    val legalProfile: String? = null,
)

@Serializable
data class QuarantineBundleV1(
    val schema: String = AuditBundles.QUARANTINE_SCHEMA,
    val exportedAt: String,
    val report: QuarantineReport,
    /** Free text for fixtures and samples. Not written when null. */
    val note: String? = null,
)

/**
 * Tolerant view of the app's AuditMeta JSON (same field names). Every field is optional: a missing or unreadable value
 * is null (unknown), and a band this version does not know reads as null rather than failing the whole bundle.
 */
@Serializable
data class BundleMeta(
    val id: String? = null,
    val targetPackage: String? = null,
    val targetLabel: String? = null,
    val createdAtMs: Long? = null,
    val score: Int? = null,
    val band: ScoreBand? = null,
    val findingCount: Int? = null,
    val plans: List<String> = emptyList(),
    val sample: Boolean = false,
    val timingProfile: String? = null,
    /** Why an inconclusive result has no score (Inconclusive.Reason name); absent in older exports. */
    val inconclusiveReason: String? = null,
)

/** The text is not a readable bundle of the expected kind; the message says why in plain words. */
class BundleFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

object AuditBundles {
    const val AUDIT_SCHEMA = "traceveil.audit.v1"
    const val QUARANTINE_SCHEMA = "traceveil.quarantine.v1"
    const val TOOL_NAME = "TraceVeil"

    /** The phone's own audit-file settings (pretty, defaults written, unknown keys ignored). */
    val json: Json = AuditJson.json
    private val lenientMeta = Json(json) { coerceInputValues = true }

    /** Current time as ISO-8601 UTC to the second, e.g. 2026-10-10T08:15:30Z. */
    fun now(clock: Clock = Clock.systemUTC()): String = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).toString()

    fun audit(
        app: BundleApp,
        meta: JsonObject,
        sessions: List<AuditSession>,
        result: AuditResult,
        networkReadings: JsonArray?,
        toolVersion: String,
        exportedAt: String = now(),
        note: String? = null,
        legalProfile: String? = null,
    ) = AuditBundleV1(
        exportedAt = exportedAt, app = app, meta = meta, sessions = sessions, result = result,
        networkReadings = networkReadings, tool = BundleTool(TOOL_NAME, toolVersion), note = note, legalProfile = legalProfile,
    )

    fun quarantine(report: QuarantineReport, exportedAt: String = now(), note: String? = null) =
        QuarantineBundleV1(exportedAt = exportedAt, report = report, note = note)

    fun encode(bundle: AuditBundleV1): String = write(json.encodeToJsonElement(AuditBundleV1.serializer(), bundle))

    fun encodeQuarantine(bundle: QuarantineBundleV1): String = write(json.encodeToJsonElement(QuarantineBundleV1.serializer(), bundle))

    /** Reads a "traceveil.audit.v1" bundle. Throws [BundleFormatException] with a plain reason when it cannot. */
    fun decode(text: String): AuditBundleV1 {
        val root = expectSchema(text, AUDIT_SCHEMA)
        return readAs("audit bundle") { json.decodeFromJsonElement(AuditBundleV1.serializer(), root) }
    }

    /** Reads a "traceveil.quarantine.v1" bundle. Throws [BundleFormatException] with a plain reason when it cannot. */
    fun decodeQuarantine(text: String): QuarantineBundleV1 {
        val root = expectSchema(text, QUARANTINE_SCHEMA)
        return readAs("quarantine bundle") { json.decodeFromJsonElement(QuarantineBundleV1.serializer(), root) }
    }

    /** The top-level "schema" string, or null when the text is not a JSON object with one. Decodes nothing else. */
    fun schemaOf(text: String): String? = runCatching { Json.parseToJsonElement(text) }.getOrNull()
        .let { it as? JsonObject }?.get("schema")
        .let { it as? JsonPrimitive }?.takeIf { it.isString }?.content

    /** Typed view of [AuditBundleV1.meta]; unknown or unreadable values are null. */
    fun metaOf(bundle: AuditBundleV1): BundleMeta =
        runCatching { lenientMeta.decodeFromJsonElement(BundleMeta.serializer(), bundle.meta) }.getOrElse { BundleMeta() }

    /** Optional "note" and "legalProfile" are dropped when null so the top level stays exactly the shared shape. */
    private fun write(element: JsonElement): String {
        val obj = element as JsonObject
        val trimmed = JsonObject(obj.filterNot { (k, v) -> k in OPTIONAL_TOP_LEVEL && v == JsonNull })
        return json.encodeToString(JsonObject.serializer(), trimmed)
    }

    private val OPTIONAL_TOP_LEVEL = setOf("note", "legalProfile")

    private fun expectSchema(text: String, expected: String): JsonObject {
        val parsed = try {
            Json.parseToJsonElement(text)
        } catch (e: IllegalArgumentException) {
            throw BundleFormatException("Not valid JSON: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}", e)
        }
        val root = parsed as? JsonObject ?: throw BundleFormatException("Expected a JSON object at the top level of the file")
        val schema = (root["schema"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw BundleFormatException("Not a TraceVeil bundle: there is no \"schema\" field (expected \"$expected\")")
        if (schema != expected) {
            val kind = when {
                schema == AUDIT_SCHEMA -> "an audit bundle ($AUDIT_SCHEMA)"
                schema == QUARANTINE_SCHEMA -> "a quarantine report ($QUARANTINE_SCHEMA)"
                schema.startsWith("traceveil.") -> "\"$schema\", a format this version of TraceVeil does not read"
                else -> "\"$schema\", not a TraceVeil format"
            }
            throw BundleFormatException("This file is $kind; expected \"$expected\"")
        }
        return root
    }

    private fun <T> readAs(what: String, block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        val reason = e.message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        throw BundleFormatException("Could not read the $what: $reason (it may be damaged, or written by a newer TraceVeil)", e)
    }
}
