package com.dpdpxray.core.export

import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.quarantine.QuarantineRecorder
import com.dpdpxray.core.quarantine.QuarantineReports
import com.dpdpxray.core.quarantine.QuarantineStopReason
import com.dpdpxray.core.report.AuditJson
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Shared export format "traceveil.audit.v1" / "traceveil.quarantine.v1". Test data, not device observations. */
class AuditBundleTest {
    private val s = 1_000_000_000L
    private val session = AuditSession(
        id = "plan-b", targetPackage = "com.example.shop", targetLabel = "Example Shop", plan = AuditPlan.REJECT,
        startedAtEpochMs = 1_791_590_400_000, endedAtNanos = 30 * s, freshState = true,
        netEvents = listOf(
            NetEvent(tNanos = 1 * s, hostname = "api.example.com"),
            NetEvent(tNanos = 9 * s, hostname = "graph.facebook.com", cnameChain = listOf("graph.facebook.com"), answerIps = listOf("192.0.2.7")),
        ),
        consentEvents = listOf(ConsentEvent(2 * s, ConsentKind.SCREEN_APPEARED), ConsentEvent(5 * s, ConsentKind.REJECT, "Reject all")),
    )
    private val result = RulesEngine(TrackerCatalog.loadDefault()).evaluate(listOf(session))
    private val meta = buildJsonObject {
        put("id", "audit-1")
        put("targetPackage", "com.example.shop")
        put("targetLabel", "Example Shop")
        put("createdAtMs", 1_791_590_400_000)
        put("score", result.score)
        put("band", result.band.name)
        put("findingCount", result.findings.size)
        put("plans", buildJsonArray { add(JsonPrimitive(AuditPlan.REJECT.label)) })
        put("sample", false)
        put("timingProfile", "normal")
        put("addedByANewerApp", 7)
    }
    private val readings = buildJsonArray {
        add(buildJsonObject { put("sessionId", "plan-b"); put("txBytesStart", 10); put("txBytesEnd", 2_110) })
    }

    private fun bundle(readings: JsonArray? = null, note: String? = null) = AuditBundles.audit(
        app = BundleApp("com.example.shop", "Example Shop"), meta = meta, sessions = listOf(session), result = result,
        networkReadings = readings, toolVersion = "1.1.0", exportedAt = "2026-10-10T08:00:00Z", note = note,
    )

    private fun tree(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `audit bundle round-trips exactly`() {
        val b = bundle(readings)
        assertEquals(b, AuditBundles.decode(AuditBundles.encode(b)))
        val noted = bundle(note = "synthetic")
        assertEquals(noted, AuditBundles.decode(AuditBundles.encode(noted)))
    }

    @Test
    fun `top level has exactly the shared v1 shape`() {
        val obj = tree(AuditBundles.encode(bundle()))
        assertEquals(listOf("schema", "exportedAt", "app", "meta", "sessions", "result", "networkReadings", "tool"), obj.keys.toList())
        assertEquals("traceveil.audit.v1", obj["schema"]!!.jsonPrimitive.content)
        assertEquals("2026-10-10T08:00:00Z", obj["exportedAt"]!!.jsonPrimitive.content)
        assertEquals(listOf("package", "label"), obj["app"]!!.jsonObject.keys.toList())
        assertEquals("com.example.shop", obj["app"]!!.jsonObject["package"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, obj["networkReadings"])
        assertEquals(buildJsonObject { put("name", "TraceVeil"); put("version", "1.1.0") }, obj["tool"])
    }

    @Test
    fun `sessions and result are written exactly as the saved audit files`() {
        val obj = tree(AuditBundles.encode(bundle()))
        assertEquals(Json.parseToJsonElement(AuditJson.encodeSessions(listOf(session))), obj["sessions"])
        assertEquals(Json.parseToJsonElement(AuditJson.encodeResult(result)), obj["result"])
    }

    @Test
    fun `meta and network readings are carried verbatim`() {
        val obj = tree(AuditBundles.encode(bundle(readings)))
        assertEquals(meta, obj["meta"])
        assertEquals(readings, obj["networkReadings"])
        assertEquals(7, AuditBundles.decode(AuditBundles.encode(bundle())).meta["addedByANewerApp"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `sdk attribution is optional, written only when set, and round-trips`() {
        assertFalse("sdkAttribution" in tree(AuditBundles.encode(bundle())))
        val groups = com.dpdpxray.core.attribution.SdkAttribution(TrackerCatalog.loadDefault(), com.dpdpxray.core.fixes.FixCatalog.loadDefault())
            .groups(result, listOf(session))
        val attributed = bundle().copy(sdkAttribution = SdkAttributionExport(groups = groups))
        val obj = tree(AuditBundles.encode(attributed))
        assertEquals(
            listOf("schema", "exportedAt", "app", "meta", "sessions", "result", "networkReadings", "tool", "sdkAttribution"),
            obj.keys.toList(),
        )
        assertEquals("Meta (Facebook SDK)", obj["sdkAttribution"]!!.jsonObject["groups"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(attributed, AuditBundles.decode(AuditBundles.encode(attributed)))
        // A reader that predates the field still gets the same evidence.
        assertEquals(bundle(), AuditBundles.decode(AuditBundles.encode(attributed)).copy(sdkAttribution = null))
    }

    @Test
    fun `note is written only when set`() {
        assertFalse("note" in tree(AuditBundles.encode(bundle())))
        assertEquals("synthetic", tree(AuditBundles.encode(bundle(note = "synthetic")))["note"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unknown fields at any level are ignored when reading`() {
        val obj = tree(AuditBundles.encode(bundle()))
        val firstSession = JsonObject(obj["sessions"]!!.jsonArray[0].jsonObject + ("futureSessionField" to JsonPrimitive(true)))
        val widened = JsonObject(
            obj + mapOf(
                "addedLater" to JsonPrimitive(42),
                "sessions" to JsonArray(listOf(firstSession)),
                "result" to JsonObject(obj["result"]!!.jsonObject + ("futureResultField" to JsonPrimitive("x"))),
                "tool" to JsonObject(obj["tool"]!!.jsonObject + ("build" to JsonPrimitive("abc123"))),
            ),
        )
        assertEquals(bundle(), AuditBundles.decode(widened.toString()))
    }

    @Test
    fun `missing optional parts read as unknown, never as zero`() {
        val obj = tree(AuditBundles.encode(bundle(readings)))
        val minimal = JsonObject(obj - "networkReadings" - "meta")
        val decoded = AuditBundles.decode(minimal.toString())
        assertNull(decoded.networkReadings)
        assertTrue(decoded.meta.isEmpty())
        assertNull(AuditBundles.metaOf(decoded).score)
    }

    @Test
    fun `wrong, missing or foreign schema is a clear format error`() {
        val obj = tree(AuditBundles.encode(bundle()))
        fun expectError(text: String, fragment: String) {
            try {
                AuditBundles.decode(text)
                fail("expected a format error for: ${text.take(60)}")
            } catch (e: BundleFormatException) {
                assertTrue("'${e.message}' should mention '$fragment'", e.message!!.contains(fragment, ignoreCase = true))
            }
        }
        expectError(JsonObject(obj - "schema").toString(), "schema")
        expectError(JsonObject(obj + ("schema" to JsonPrimitive("traceveil.audit.v2"))).toString(), "traceveil.audit.v2")
        expectError(JsonObject(obj + ("schema" to JsonPrimitive("traceveil.quarantine.v1"))).toString(), "quarantine")
        expectError(JsonObject(obj - "sessions").toString(), "sessions")
        expectError("{broken", "JSON")
        expectError("[1, 2]", "object")
    }

    @Test
    fun `schemaOf reads the schema without decoding the rest`() {
        assertEquals("traceveil.audit.v1", AuditBundles.schemaOf(AuditBundles.encode(bundle())))
        assertEquals("anything", AuditBundles.schemaOf("""{"schema":"anything","sessions":7}"""))
        assertNull(AuditBundles.schemaOf("""{"sessions":[]}"""))
        assertNull(AuditBundles.schemaOf("not json"))
        assertNull(AuditBundles.schemaOf("""{"schema":{"nested":true}}"""))
    }

    @Test
    fun `meta view reads the app's AuditMeta fields and tolerates unknown values`() {
        val view = AuditBundles.metaOf(bundle())
        assertEquals("audit-1", view.id)
        assertEquals(result.score, view.score)
        assertEquals(result.band, view.band)
        assertEquals(listOf(AuditPlan.REJECT.label), view.plans)
        assertEquals(false, view.sample)
        assertEquals("normal", view.timingProfile)
        val odd = bundle().copy(meta = JsonObject(meta + ("band" to JsonPrimitive("SOMETHING_NEW")) + ("score" to JsonNull)))
        val oddView = AuditBundles.metaOf(odd)
        assertNull("an unknown band stays unknown", oddView.band)
        assertNull(oddView.score)
        assertEquals("audit-1", oddView.id)
        assertEquals(ScoreBand.AT_RISK, ScoreBand.valueOf("AT_RISK"))
    }

    @Test
    fun `exportedAt is ISO-8601 UTC to the second`() {
        val clock = Clock.fixed(Instant.parse("2026-10-10T08:15:30.123Z"), ZoneOffset.UTC)
        assertEquals("2026-10-10T08:15:30Z", AuditBundles.now(clock))
    }

    private fun quarantineReport() = QuarantineRecorder(setOf("10.111.0.53")).let { r ->
        r.recordTargetOnScreen(1_200_000_000)
        r.recordDns(1_500_000_000, "panel.example.com", 1, "10.111.0.53")
        QuarantineReports.build(
            id = "quarantine-1", targetPackage = "com.traceveil.riskdemo", targetLabel = "Risk Demo",
            startedAtEpochMs = 1_791_600_000_000, startedAtNanos = 1_000_000_000, endedAtNanos = 61_000_000_000,
            plannedDurationMs = 60_000, stopReason = QuarantineStopReason.TIMER, resolverAddresses = setOf("10.111.0.53"),
            observations = r.snapshot(),
        )
    }

    @Test
    fun `quarantine bundle round-trips and keeps the saved report JSON`() {
        val report = quarantineReport()
        val b = AuditBundles.quarantine(report, exportedAt = "2026-10-10T09:00:00Z")
        val text = AuditBundles.encodeQuarantine(b)
        assertEquals(b, AuditBundles.decodeQuarantine(text))
        val obj = tree(text)
        assertEquals(listOf("schema", "exportedAt", "report"), obj.keys.toList())
        assertEquals("traceveil.quarantine.v1", obj["schema"]!!.jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement(QuarantineReports.encode(report)), obj["report"])
        val widened = JsonObject(obj + ("addedLater" to JsonPrimitive(1)) + ("report" to JsonObject(obj["report"]!!.jsonObject + ("x" to JsonPrimitive(2)))))
        assertEquals(b, AuditBundles.decodeQuarantine(widened.toString()))
    }

    @Test
    fun `quarantine decoder rejects an audit bundle and vice versa`() {
        try {
            AuditBundles.decodeQuarantine(AuditBundles.encode(bundle()))
            fail("audit bundle must not decode as quarantine")
        } catch (e: BundleFormatException) {
            assertTrue(e.message!!.contains("traceveil.audit.v1"))
        }
    }
}
