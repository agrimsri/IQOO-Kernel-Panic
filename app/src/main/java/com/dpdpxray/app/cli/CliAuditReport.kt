package com.dpdpxray.app.cli

import com.dpdpxray.app.data.AuditBundle
import com.dpdpxray.core.model.ConsentKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** Concise, versioned JSON the CLI reads back over adb. Key order is part of the contract; bump [SCHEMA_VERSION] on change. */
object CliAuditReport {
    const val SCHEMA_VERSION = 1
    const val REPORT_FILE = "report.json"
    const val STATUS_FILE = "status.json"
    private val pretty = Json { prettyPrint = true }

    private fun strings(items: Iterable<String>): JsonElement = JsonArray(items.map { JsonPrimitive(it) })

    fun build(requestId: String, bundle: AuditBundle): String {
        val first = bundle.sessions.firstOrNull()
        val obj = buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            put("requestId", requestId)
            put("auditId", bundle.meta.id)
            put("completedAtMs", bundle.meta.createdAtMs)
            put("target", buildJsonObject { put("package", bundle.meta.targetPackage); put("label", bundle.meta.targetLabel) })
            put("device", buildJsonObject { put("model", first?.deviceModel.orEmpty()); put("androidVersion", first?.androidVersion.orEmpty()) })
            put("score", bundle.result.score?.let { JsonPrimitive(it) } ?: JsonNull)
            put("band", bundle.result.band.name)
            put("findingCount", bundle.result.findings.size)
            put("plans", strings(bundle.meta.plans))
            put("findings", JsonArray(bundle.result.findings.map { f ->
                buildJsonObject {
                    put("check", f.checkId.code)
                    put("severity", f.severity.name)
                    put("evidenceType", f.evidenceType.name)
                    put("title", f.title)
                    put("confidence", f.confidence)
                    put("plans", strings(f.plans.map { it.name }))
                    put("entities", strings(f.entities))
                }
            }))
            put("sessions", JsonArray(bundle.sessions.map { s ->
                buildJsonObject {
                    put("plan", s.plan.name)
                    put("netEvents", s.netEvents.size)
                    put("consentEvents", s.consentEvents.size)
                    put("consentChoice", s.consentChoice?.kind?.takeIf { it == ConsentKind.ACCEPT || it == ConsentKind.REJECT }?.name?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("notes", strings(s.notes))
                }
            }))
        }
        return pretty.encodeToString(JsonObject.serializer(), obj)
    }

    fun status(requestId: String, state: String, step: String? = null, auditId: String? = null, error: String? = null, now: Long = System.currentTimeMillis()): String =
        pretty.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            put("requestId", requestId)
            put("state", state)
            step?.let { put("step", it) }
            auditId?.let { put("auditId", it) }
            error?.let { put("error", it) }
            put("updatedAtMs", now)
        })
}

/** Writes `<root>/<request_id>/<name>` atomically (temp file, then rename) so the CLI never reads a half-written file. */
class CliReportStore(private val root: File) {
    fun write(requestId: String, name: String, text: String): File {
        require(CliAuditRequest.isValidId(requestId)) { "invalid request id" }
        require(name == CliAuditReport.REPORT_FILE || name == CliAuditReport.STATUS_FILE) { "invalid file name" }
        val dir = File(root, requestId).apply { mkdirs() }
        val tmp = File(dir, "$name.tmp").apply { writeText(text) }
        val target = File(dir, name)
        check(tmp.renameTo(target)) { "could not publish $name" }
        return target
    }
}
