package com.dpdpxray.core.export

import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** "Back up my checks" / "Restore from a backup": the traceveil.backup.v1 envelope, its integrity hash and the merge. */
class AuditBackupsTest {
    private val engine = RulesEngine(TrackerCatalog.loadDefault())
    private val S = 1_000_000_000L

    private fun audit(id: String, host: String = "graph.facebook.com", label: String = "Demo \"app\" ✓"): AuditBundleV1 {
        val sessions = listOf(
            AuditSession(
                id = "$id-s", targetPackage = "com.example.demo", targetLabel = label, plan = AuditPlan.SILENT,
                endedAtNanos = 20 * S, netEvents = listOf(NetEvent(S / 2, host), NetEvent(S, "api.example.test")),
            ),
        )
        val result = engine.evaluate(sessions)
        val meta = buildJsonObject {
            put("id", id); put("targetPackage", "com.example.demo"); put("targetLabel", label)
            put("createdAtMs", 1_791_590_400_000L); put("score", result.score); put("band", result.band.name)
            put("findingCount", result.findings.size)
        }
        return AuditBundles.audit(BundleApp("com.example.demo", label), meta, sessions, result, null, "1.2.0", exportedAt = "2026-10-10T08:00:00Z")
    }

    private fun backup(vararg audits: AuditBundleV1): String = AuditBackups.encode(audits.toList(), "2026-10-10T09:00:00Z", "1.2.0")

    private fun rejects(text: String, vararg words: String) {
        try {
            AuditBackups.read(text)
            fail("accepted: ${text.take(80)}")
        } catch (e: BackupFormatException) {
            words.forEach { assertTrue("\"${e.message}\" lacks \"$it\"", e.message!!.contains(it)) }
        }
    }

    @Test
    fun `round trip keeps every audit exactly`() {
        val a = audit("audit-1")
        val b = audit("audit-2", host = "app-measurement.com")
        val text = backup(a, b)
        val read = AuditBackups.read(text)
        assertEquals("2026-10-10T09:00:00Z", read.createdAt)
        assertEquals("1.2.0", read.appVersion)
        assertEquals(listOf("audit-1", "audit-2"), read.entries.map { it.id })
        assertEquals(listOf(a, b), read.entries.map { it.bundle })
        assertTrue(read.entries.all { it.problem == null })
    }

    @Test
    fun `the envelope has the documented fields and each audit can be extracted as a normal export`() {
        val root = Json.parseToJsonElement(backup(audit("audit-1"))).jsonObject
        assertEquals(AuditBackups.SCHEMA, root["schema"]!!.jsonPrimitive.content)
        assertEquals("traceveil.backup.v1", AuditBackups.SCHEMA)
        listOf("createdAt", "appVersion", "audits", "sha256").forEach { assertTrue(it, it in root) }
        assertTrue(Regex("[0-9a-f]{64}").matches(root["sha256"]!!.jsonPrimitive.content))
        // The web console and TraceVeil CI read single traceveil.audit.v1 bundles: one extracted item is exactly that.
        val one = root["audits"]!!.jsonArray[0].toString()
        assertEquals("audit-1", AuditBundles.metaOf(AuditBundles.decode(one)).id)
    }

    @Test
    fun `the hash covers the audits, not the file layout`() {
        val text = backup(audit("audit-1"), audit("audit-2"))
        val reformatted = Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(text))
        assertEquals(2, AuditBackups.read(reformatted).entries.size)
    }

    @Test
    fun `a changed audit or a changed hash is rejected`() {
        val text = backup(audit("audit-1"), audit("audit-2"))
        assertTrue(text.contains("graph.facebook.com"))
        rejects(text.replaceFirst("graph.facebook.com", "graph.facebook.con"), "integrity", "Nothing was restored")
        val root = Json.parseToJsonElement(text).jsonObject
        val wrongHash = JsonObject(root + ("sha256" to JsonPrimitive("0".repeat(64)))).toString()
        rejects(wrongHash, "integrity")
        val noHash = JsonObject(root - "sha256").toString()
        rejects(noHash, "integrity hash")
        // Dropping one audit from the list also breaks the hash.
        val fewer = JsonObject(root + ("audits" to JsonArray(root["audits"]!!.jsonArray.drop(1)))).toString()
        rejects(fewer, "integrity")
    }

    @Test
    fun `wrong files are rejected with a plain reason`() {
        rejects("not json at all", "not a TraceVeil backup")
        rejects("[1, 2, 3]", "not a TraceVeil backup")
        rejects("""{"schema":"something.else"}""", "not a TraceVeil backup")
        val single = AuditBundles.encode(audit("audit-1"))
        rejects(single, "one exported check", AuditBundles.AUDIT_SCHEMA)
        rejects("""{"schema":"traceveil.backup.v2","audits":[],"sha256":"x"}""", "newer")
        rejects("""{"schema":"traceveil.backup.v1","sha256":"${"0".repeat(64)}"}""", "no list of checks")
    }

    @Test
    fun `an empty backup is valid and says it holds nothing`() {
        val read = AuditBackups.read(backup())
        assertTrue(read.entries.isEmpty())
        val plan = AuditBackups.plan(read, existingIds = setOf("audit-9"))
        assertTrue(plan.toRestore.isEmpty())
        assertEquals("This backup holds no checks.", AuditBackups.summary(plan))
    }

    @Test
    fun `restore skips audits that already exist, by audit id`() {
        val read = AuditBackups.read(backup(audit("audit-1"), audit("audit-2"), audit("audit-3")))
        val plan = AuditBackups.plan(read, existingIds = setOf("audit-2"))
        assertEquals(listOf("audit-1", "audit-3"), plan.toRestore.map { AuditBundles.metaOf(it).id })
        assertEquals(listOf("audit-2"), plan.alreadyHere)
        assertEquals(0, plan.unreadable)
        assertEquals("Restored 2 checks, skipped 1 already here.", AuditBackups.summary(plan))
        assertEquals("Restored 1 check, skipped 2 already here.", AuditBackups.summary(AuditBackups.plan(read, setOf("audit-2", "audit-3"))))
    }

    @Test
    fun `an audit that cannot be read or has an unsafe id is never restored`() {
        val good = audit("audit-1")
        val unsafe = audit("../../shared_prefs/evil")
        val goodJson = AuditBackups.auditElement(good)
        val unsafeJson = AuditBackups.auditElement(unsafe)
        val notAnAudit = buildJsonObject { put("schema", "traceveil.quarantine.v1") }
        val audits = JsonArray(listOf(goodJson, unsafeJson, notAnAudit))
        val text = buildJsonObject {
            put("schema", AuditBackups.SCHEMA); put("createdAt", "2026-10-10T09:00:00Z"); put("appVersion", "1.2.0")
            put("audits", audits); put("sha256", AuditBackups.hashOf(audits))
        }.toString()
        val read = AuditBackups.read(text)
        assertEquals(3, read.entries.size)
        assertNotNull(read.entries[0].bundle)
        assertNull(read.entries[1].bundle)
        assertNull(read.entries[2].bundle)
        val plan = AuditBackups.plan(read, emptySet())
        assertEquals(listOf(good), plan.toRestore)
        assertEquals(2, plan.unreadable)
        assertEquals("Restored 1 check, skipped 0 already here, 2 could not be read.", AuditBackups.summary(plan))
    }

    @Test
    fun `a file over the size limit is refused before it is read`() {
        assertNull(AuditBackups.sizeProblem(1024))
        assertNull(AuditBackups.sizeProblem(null))
        assertNotNull(AuditBackups.sizeProblem(AuditBackups.MAX_BYTES + 1))
        assertTrue(AuditBackups.sizeProblem(AuditBackups.MAX_BYTES + 1)!!.contains("50 MB"))
        assertEquals(50L * 1024 * 1024, AuditBackups.MAX_BYTES)
    }

    @Test
    fun `the suggested file name carries the date`() {
        assertEquals("traceveil-backup-2026-10-10.json", AuditBackups.fileName("2026-10-10T09:00:00Z"))
    }
}
