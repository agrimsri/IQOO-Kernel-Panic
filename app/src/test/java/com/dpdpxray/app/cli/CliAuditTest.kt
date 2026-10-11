package com.dpdpxray.app.cli

import com.dpdpxray.app.data.AuditBundle
import com.dpdpxray.app.data.AuditMeta
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.Finding
import com.dpdpxray.core.model.PenaltyBand
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.Severity
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.Category
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.trackers.TrackerCatalog
import com.dpdpxray.core.trackers.TrackerEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CliAuditTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun ok(r: CliAuditRequest.Parsed) = (r as CliAuditRequest.Parsed.Ok).request
    private fun bad(r: CliAuditRequest.Parsed) = (r as CliAuditRequest.Parsed.Rejected).reason

    @Test fun `valid request uses safe defaults`() {
        val r = ok(CliAuditRequest.parse("cli-20261010-ab12", "com.leakyshop.demo", null, null, null))
        assertEquals(listOf(AuditPlan.SILENT, AuditPlan.REJECT, AuditPlan.ACCEPT), r.plans)
        assertFalse(r.autoDrive)
        assertFalse(r.resetAppData)
    }

    @Test fun `plans autoDrive and reset are parsed only when explicit`() {
        val r = ok(CliAuditRequest.parse("a1", "com.x.y", "Reject, silent", "true", "true"))
        assertEquals(listOf(AuditPlan.REJECT, AuditPlan.SILENT), r.plans)
        assertTrue(r.autoDrive); assertTrue(r.resetAppData)
        assertFalse(ok(CliAuditRequest.parse("a1", "com.x.y", null, "yes", "1")).resetAppData)
    }

    @Test fun `path-shaped or empty request ids are rejected`() {
        listOf(null, "", "../x", "a/b", "a b", "a".repeat(65)).forEach {
            assertTrue("$it", CliAuditRequest.parse(it, "com.x.y", null, null, null) is CliAuditRequest.Parsed.Rejected)
        }
    }

    @Test fun `bad package and plans are rejected with a reason`() {
        assertTrue(bad(CliAuditRequest.parse("a1", "nodots", null, null, null)).contains("package"))
        assertTrue(bad(CliAuditRequest.parse("a1", null, null, null, null)).contains("package"))
        assertTrue(bad(CliAuditRequest.parse("a1", "com.x.y", "silent,bogus", null, null)).contains("bogus"))
        assertTrue(bad(CliAuditRequest.parse("a1", "com.x.y", "silent,silent", null, null)).contains("duplicate"))
    }

    private fun bundle(score: Int?): AuditBundle {
        val finding = Finding(CheckId.C1, Severity.HIGH, EvidenceType.OBSERVED, "Trackers before consent", "why", listOf("host.example"),
            listOf(AuditPlan.SILENT), emptyList(), PenaltyBand.RESIDUAL_50, 0.9, listOf("Tracker Co"))
        val session = AuditSession("s1", "com.x.y", "X", AuditPlan.REJECT, deviceModel = "vivo I2501", androidVersion = "16",
            consentEvents = listOf(ConsentEvent(1, ConsentKind.REJECT, "Reject")), notes = listOf("note"))
        val meta = AuditMeta("audit-1", "com.x.y", "X", 1000L, score, ScoreBand.AT_RISK, 1, listOf("Plan B · Reject"))
        return AuditBundle(meta, listOf(session), AuditResult(listOf(finding), score, ScoreBand.AT_RISK), File("."))
    }

    @Test fun `report is stable json with the documented keys in order`() {
        val obj = Json.parseToJsonElement(CliAuditReport.build("r1", bundle(21))).jsonObject
        assertEquals(
            listOf("schemaVersion", "requestId", "auditId", "completedAtMs", "target", "device", "score", "band", "findingCount", "plans", "findings", "sessions"),
            obj.keys.toList(),
        )
        assertEquals(1, obj["schemaVersion"]!!.jsonPrimitive.int)
        assertEquals("r1", obj["requestId"]!!.jsonPrimitive.content)
        assertEquals(21, obj["score"]!!.jsonPrimitive.int)
        val f = obj["findings"]!!.jsonArray.single().jsonObject
        assertEquals("C1", f["check"]!!.jsonPrimitive.content)
        assertEquals("HIGH", f["severity"]!!.jsonPrimitive.content)
        assertEquals("REJECT", obj["sessions"]!!.jsonArray.single().jsonObject["consentChoice"]!!.jsonPrimitive.content)
    }

    @Test fun `observed trackers list every catalogue entity looked up, by phase, only when a catalogue is given`() {
        val catalog = TrackerCatalog(listOf(TrackerEntry("AppsFlyer", Category.ATTRIBUTION, listOf("appsflyer.com"))))
        val b = bundle(21).let { b ->
            b.copy(sessions = b.sessions.map { s ->
                s.copy(netEvents = listOf(NetEvent(0, "t.appsflyer.com"), NetEvent(5, "api.x.example"), NetEvent(6, "launches.appsflyer.com")))
            })
        }
        assertFalse("observedTrackers" in Json.parseToJsonElement(CliAuditReport.build("r1", b)).jsonObject)
        val obj = Json.parseToJsonElement(CliAuditReport.build("r1", b, catalog)).jsonObject
        assertEquals("observedTrackers", obj.keys.last())
        val t = obj["observedTrackers"]!!.jsonArray.single().jsonObject
        assertEquals("AppsFlyer", t["entity"]!!.jsonPrimitive.content)
        assertEquals("ATTRIBUTION", t["category"]!!.jsonPrimitive.content)
        assertEquals(2, t["lookups"]!!.jsonPrimitive.int)
        assertEquals(listOf("AFTER_REJECT", "BEFORE_CONSENT"), t["phases"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun `unknown score stays null and is never turned into zero`() {
        val obj = Json.parseToJsonElement(CliAuditReport.build("r1", bundle(null))).jsonObject
        assertEquals(JsonNull, obj["score"])
    }

    @Test fun `status json carries state and error only when given`() {
        val ok = Json.parseToJsonElement(CliAuditReport.status("r1", "running", step = "Observing", now = 5)).jsonObject
        assertEquals("running", ok["state"]!!.jsonPrimitive.content)
        assertFalse("error" in ok)
        val failed = Json.parseToJsonElement(CliAuditReport.status("r1", "failed", error = "VPN permission missing", now = 6)).jsonObject
        assertEquals("VPN permission missing", failed["error"]!!.jsonPrimitive.content)
    }

    @Test fun `store writes atomically under reports slash request id`() {
        val store = CliReportStore(tmp.root)
        store.write("r1", "report.json", "{\"a\":1}")
        assertEquals("{\"a\":1}", File(tmp.root, "r1/report.json").readText())
        assertTrue(File(tmp.root, "r1").listFiles()!!.none { it.name.endsWith(".tmp") })
        store.write("r1", "report.json", "{\"a\":2}")
        assertEquals("{\"a\":2}", File(tmp.root, "r1/report.json").readText())
    }

    @Test fun `store refuses ids and names that escape the reports directory`() {
        val store = CliReportStore(tmp.root)
        listOf("../x", "a/b", "").forEach { id ->
            assertTrue(runCatching { store.write(id, "report.json", "{}") }.isFailure)
        }
        assertTrue(runCatching { store.write("r1", "../report.json", "{}") }.isFailure)
    }

    private fun manifest(path: String): String =
        (File(path).takeIf { it.exists() } ?: File("app/$path")).readText()

    @Test fun `release manifest has no cli trigger and debug manifest guards it with DUMP`() {
        val main = manifest("src/main/AndroidManifest.xml")
        assertFalse(main.contains("DebugAuditReceiver"))
        assertFalse(main.contains("RUN_AUDIT"))
        val debug = manifest("src/debug/AndroidManifest.xml")
        assertTrue(debug.contains("DebugAuditReceiver"))
        assertTrue(debug.contains("android.permission.DUMP"))
        assertTrue(debug.contains(CliAuditRequest.ACTION))
    }

    @Test fun `receiver class exists only in the debug source set`() {
        val rel = "java/com/dpdpxray/app/cli/DebugAuditReceiver.kt"
        val base = if (File("src").isDirectory) "src" else "app/src"
        assertTrue(File("$base/debug/$rel").exists())
        assertFalse(File("$base/main/$rel").exists())
    }
}
