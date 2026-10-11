package com.dpdpxray.app.cli

import com.dpdpxray.app.data.AuditBundle
import com.dpdpxray.core.audit.PhaseAssigner
import com.dpdpxray.core.model.Category
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.Phase
import com.dpdpxray.core.trackers.TrackerCatalog
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

    /** One moment read on both clocks, so a lookup's monotonic time (`tNanos`) can be dated on the phone's wall clock. */
    data class ClockAnchor(val wallMs: Long, val elapsedNanos: Long) {
        fun wallMsOf(tNanos: Long): Long = wallMs - (elapsedNanos - tNanos) / 1_000_000
    }

    /** [catalog] adds `observedTrackers` (every catalogue entity looked up, by consent phase); omitted when null.
     *  [anchor] also dates each lookup (`lookupEvents`) so the CLI can line them up with Android's data-access log. */
    fun build(requestId: String, bundle: AuditBundle, catalog: TrackerCatalog? = null, anchor: ClockAnchor? = null): String {
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
            if (catalog != null) put("observedTrackers", observedTrackers(bundle, catalog, anchor))
        }
        return pretty.encodeToString(JsonObject.serializer(), obj)
    }

    /** Hostname lookups only: an entry says the app looked the tracker up, not what data was sent. */
    private fun observedTrackers(bundle: AuditBundle, catalog: TrackerCatalog, anchor: ClockAnchor?): JsonArray {
        val hits = bundle.sessions.flatMap { s ->
            PhaseAssigner.assign(s.netEvents, s.consentEvents).mapNotNull { e ->
                val info = catalog.classify(e.hostname, e.cnameChain)
                info.entity?.let { Hit(it, info.category, e.phase, e.tNanos) }
            }
        }
        return JsonArray(hits.groupBy { it.entity }.toSortedMap().map { (entity, rows) ->
            buildJsonObject {
                put("entity", entity)
                put("category", rows.first().category.name)
                put("lookups", rows.size)
                put("phases", strings(rows.map { it.phase.name }.distinct().sorted()))
                if (anchor != null) put("lookupEvents", JsonArray(rows.sortedBy { it.tNanos }.map {
                    buildJsonObject { put("atMs", anchor.wallMsOf(it.tNanos)); put("phase", it.phase.name) }
                }))
            }
        })
    }

    private data class Hit(val entity: String, val category: Category, val phase: Phase, val tNanos: Long)

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
        // An atomic replace: File.renameTo cannot replace an existing file on every platform (it fails on Windows), and a
        // status file is rewritten many times per request.
        runCatching {
            java.nio.file.Files.move(
                tmp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure { tmp.delete(); throw IllegalStateException("could not publish $name", it) }
        return target
    }
}
