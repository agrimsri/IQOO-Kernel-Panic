package com.dpdpxray.core.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest

/*
 * "TraceVeil backup v1": every saved check in one file the person keeps wherever they choose (offline; TraceVeil never
 * uploads it).
 *
 *   { "schema": "traceveil.backup.v1", "createdAt": ISO-8601, "appVersion": versionName, "auditCount": n,
 *     "about": "...", "audits": [ <traceveil.audit.v1 bundle>, ... ], "sha256": hex }
 *
 * "sha256" is the SHA-256 of the "audits" array written as compact JSON (UTF-8, no spaces, keys in file order), so
 * re-indenting the file keeps it valid while any change to an audit, or a dropped or added audit, fails the check. It
 * detects damage and accidental edits; it is not a signature (anyone can recompute it). Each item is exactly an audit
 * export, so one can be cut out and opened in the web console or TraceVeil CI on its own.
 */

/** The file is not a usable backup; the message says why in plain words and that nothing was restored. */
class BackupFormatException(message: String) : IllegalArgumentException(message)

/** One audit in a backup: [bundle] when it can be restored, otherwise [problem]. [id] is its audit id when readable. */
data class BackupEntry(val id: String?, val bundle: AuditBundleV1?, val problem: String?)

data class BackupContents(val createdAt: String?, val appVersion: String?, val entries: List<BackupEntry>)

/** What a restore will do: audits to add, ids skipped because they are already on the phone, and unreadable items. */
data class RestorePlan(val toRestore: List<AuditBundleV1>, val alreadyHere: List<String>, val unreadable: Int)

/** Result of writing a backup. */
data class BackupWritten(val count: Int, val sha256: String)

object AuditBackups {
    const val SCHEMA = "traceveil.backup.v1"

    /** Larger files are refused before they are read: a backup is held in memory while it is checked. */
    const val MAX_BYTES: Long = 50L * 1024 * 1024

    const val MIME = "application/json"

    const val ABOUT = "Checks saved by TraceVeil on the phone that made this file. Each item in \"audits\" is a " +
        "traceveil.audit.v1 audit export; \"sha256\" is the SHA-256 of the \"audits\" array as compact JSON, to detect " +
        "damage. Restore it in TraceVeil: Reports, Restore from a backup."

    /** Audit ids become folder names on the phone, so only plain names are accepted ("audit-1791595268053"). */
    private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,99}")

    private val compact = Json { prettyPrint = false }

    /** "traceveil-backup-2026-10-10.json" for a createdAt of "2026-10-10T09:00:00Z". */
    fun fileName(createdAt: String): String = "traceveil-backup-${createdAt.take(10).filter { it.isDigit() || it == '-' }}.json"

    /** A plain refusal when [bytes] is over [MAX_BYTES]; null when it is within the limit or not reported. */
    fun sizeProblem(bytes: Long?): String? =
        if (bytes != null && bytes > MAX_BYTES) "This file is larger than 50 MB, so TraceVeil did not open it. Nothing was restored." else null

    /** One audit exactly as an audit export writes it (optional fields left out when unset), as a JSON tree. */
    fun auditElement(bundle: AuditBundleV1): JsonElement = AuditBundles.element(bundle)

    /** SHA-256 (lower-case hex) of [audits] as compact JSON. */
    fun hashOf(audits: JsonArray): String = sha256Hex(compact.encodeToString(JsonElement.serializer(), audits).toByteArray(Charsets.UTF_8))

    /**
     * Streams a backup of [audits] into [out], one audit in memory at a time, and returns the count and hash. Each audit
     * sits on its own line; the hash is computed over the compact form while writing.
     */
    fun write(out: OutputStream, audits: Iterator<AuditBundleV1>, createdAt: String, appVersion: String): BackupWritten {
        val digest = MessageDigest.getInstance("SHA-256")
        val w = out.bufferedWriter(Charsets.UTF_8)
        fun str(s: String) = compact.encodeToString(JsonElement.serializer(), JsonPrimitive(s))
        w.write("{\n")
        w.write("  \"schema\": ${str(SCHEMA)},\n")
        w.write("  \"createdAt\": ${str(createdAt)},\n")
        w.write("  \"appVersion\": ${str(appVersion)},\n")
        w.write("  \"about\": ${str(ABOUT)},\n")
        w.write("  \"audits\": [")
        digest.update('['.code.toByte())
        var count = 0
        for (bundle in audits) {
            val text = compact.encodeToString(JsonElement.serializer(), auditElement(bundle))
            if (count > 0) {
                w.write(",")
                digest.update(','.code.toByte())
            }
            w.write("\n    ")
            w.write(text)
            digest.update(text.toByteArray(Charsets.UTF_8))
            count++
        }
        digest.update(']'.code.toByte())
        val hash = hex(digest.digest())
        w.write(if (count > 0) "\n  ],\n" else "],\n")
        w.write("  \"auditCount\": $count,\n")
        w.write("  \"sha256\": ${str(hash)}\n")
        w.write("}\n")
        w.flush()
        return BackupWritten(count, hash)
    }

    /** The whole backup as text (tests and small callers). */
    fun encode(audits: List<AuditBundleV1>, createdAt: String, appVersion: String): String {
        val out = ByteArrayOutputStream()
        write(out, audits.iterator(), createdAt, appVersion)
        return out.toString(Charsets.UTF_8.name())
    }

    /**
     * Reads and checks a backup: the schema, the integrity hash over its audits, then each audit on its own. A wrong,
     * damaged or changed file throws [BackupFormatException]; an individual audit that cannot be restored is kept with
     * its [BackupEntry.problem] so the restore can say how many were left out.
     */
    fun read(text: String): BackupContents {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: throw BackupFormatException("This file is not a TraceVeil backup (it is not readable JSON). Nothing was restored.")
        val schema = (root["schema"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        when {
            schema == SCHEMA -> Unit
            schema == AuditBundles.AUDIT_SCHEMA -> throw BackupFormatException(
                "This file is one exported check (${AuditBundles.AUDIT_SCHEMA}), not a TraceVeil backup. Nothing was restored.",
            )
            schema == AuditBundles.QUARANTINE_SCHEMA -> throw BackupFormatException(
                "This file is a quarantine report (${AuditBundles.QUARANTINE_SCHEMA}), not a TraceVeil backup. Nothing was restored.",
            )
            schema != null && schema.startsWith("traceveil.backup.") -> throw BackupFormatException(
                "This backup (\"$schema\") was made by a newer TraceVeil. Update TraceVeil to restore it. Nothing was restored.",
            )
            else -> throw BackupFormatException("This file is not a TraceVeil backup. Nothing was restored.")
        }
        val audits = root["audits"] as? JsonArray
            ?: throw BackupFormatException("This backup has no list of checks, so it may be damaged. Nothing was restored.")
        val expected = (root["sha256"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase()
            ?: throw BackupFormatException("This backup has no integrity hash, so it cannot be checked. Nothing was restored.")
        if (!MessageDigest.isEqual(expected.toByteArray(), hashOf(audits).toByteArray())) {
            throw BackupFormatException(
                "This backup failed its integrity check: it was changed or damaged after TraceVeil made it. Nothing was restored.",
            )
        }
        return BackupContents(
            createdAt = (root["createdAt"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            appVersion = (root["appVersion"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            entries = audits.map(::entry),
        )
    }

    private fun entry(element: JsonElement): BackupEntry {
        val obj = element as? JsonObject ?: return BackupEntry(null, null, "Not an audit")
        val schema = (obj["schema"] as? JsonPrimitive)?.content
        if (schema != AuditBundles.AUDIT_SCHEMA) return BackupEntry(null, null, "Not a ${AuditBundles.AUDIT_SCHEMA} audit")
        val bundle = runCatching { AuditBundles.decode(element) }.getOrElse {
            return BackupEntry(null, null, it.message ?: "Could not read the audit")
        }
        val id = AuditBundles.metaOf(bundle).id
        if (id == null || !SAFE_ID.matches(id)) return BackupEntry(id, null, "The audit has no usable id")
        return BackupEntry(id, bundle, null)
    }

    /** Audits to add, skipping ids already on the phone (and repeats inside the file), first one wins. */
    fun plan(contents: BackupContents, existingIds: Set<String>): RestorePlan {
        val seen = existingIds.toMutableSet()
        val restore = mutableListOf<AuditBundleV1>()
        val already = mutableListOf<String>()
        var unreadable = 0
        contents.entries.forEach { e ->
            val bundle = e.bundle
            val id = e.id
            when {
                bundle == null || id == null -> unreadable++
                id in seen -> already += id
                else -> { seen += id; restore += bundle }
            }
        }
        return RestorePlan(restore, already, unreadable)
    }

    /** "Restored 2 checks, skipped 1 already here." (plus how many could not be read), or that the backup is empty. */
    fun summary(plan: RestorePlan, restored: Int = plan.toRestore.size): String {
        if (plan.toRestore.isEmpty() && plan.alreadyHere.isEmpty() && plan.unreadable == 0) return "This backup holds no checks."
        val checks = if (restored == 1) "1 check" else "$restored checks"
        val unread = if (plan.unreadable > 0) ", ${plan.unreadable} could not be read" else ""
        return "Restored $checks, skipped ${plan.alreadyHere.size} already here$unread."
    }

    private fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
