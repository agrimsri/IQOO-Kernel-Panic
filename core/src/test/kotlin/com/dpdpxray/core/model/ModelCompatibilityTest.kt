package com.dpdpxray.core.model

import com.dpdpxray.core.report.AuditJson
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.rules.ScoreCalculator
import com.dpdpxray.core.trackers.TrackerCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A0 contract: the new evidence fields are additive and old saved audits still open. */
class ModelCompatibilityTest {
    private val s = 1_000_000_000L

    /** Written by the pre-A0 encoder (AuditJson at 1154a9b), so it has none of the new keys. */
    private val legacyJson = javaClass.getResource("/compat/sessions-v0-baseline.json")!!.readText()

    @Test
    fun `sessions saved before A0 still decode with neutral defaults`() {
        val session = AuditJson.decodeSessions(legacyJson).single()
        assertEquals("audit-legacy-1", session.id)
        assertEquals(3, session.netEvents.size)
        assertEquals("com.google.android.gms", session.netEvents[1].viaPackage)
        assertEquals(ConsentKind.REJECT, session.consentChoice?.kind)
        session.netEvents.forEach {
            assertTrue(it.cnameChain.isEmpty())
            assertTrue(it.answerIps.isEmpty())
            assertNull(it.disguisedVia)
        }
        assertNull("old audits have no health record, not a healthy one", session.captureHealth)
    }

    @Test
    fun `legacy fixture really predates the new keys`() {
        val raw = Json.parseToJsonElement(legacyJson).jsonArray.single().jsonObject
        assertFalse(raw.containsKey("captureHealth"))
        assertFalse(raw["netEvents"]!!.jsonArray.first().jsonObject.containsKey("answerIps"))
    }

    @Test
    fun `legacy audit keeps its findings and score after being re-saved`() {
        val engine = RulesEngine(TrackerCatalog.loadDefault())
        val decoded = AuditJson.decodeSessions(legacyJson)
        val before = engine.evaluate(decoded)
        assertTrue(before.findings.any { it.checkId == CheckId.C2 && it.evidence.single().startsWith("graph.facebook.com") })

        val resaved = AuditJson.decodeSessions(AuditJson.encodeSessions(decoded))
        assertEquals(decoded, resaved)
        assertEquals(before, engine.evaluate(resaved))
    }

    @Test
    fun `new evidence fields round-trip`() {
        val health = CaptureHealth(
            captureStartedNanos = 0, captureEndedNanos = 30 * s,
            dnsQueriesParsed = 41, malformedPackets = 0, dnsResponsesRelayed = 40, dnsRelayFailures = 1, malformedResponses = 0,
            eventsRecorded = 41, eventsDropped = 0, eventBufferCapacity = 5_000,
            firstLookupNanos = 1 * s, lastLookupNanos = 29 * s,
            dnsUpstreamPayloadBytesSent = 1_312, dnsUpstreamDatagramsSent = 42,
            dnsUpstreamPayloadBytesReceived = 4_100, dnsUpstreamDatagramsReceived = 40,
            notes = listOf("sample"),
        )
        val session = AuditSession(
            id = "a", targetPackage = "com.leakyshop.demo", targetLabel = "LeakyShop", plan = AuditPlan.REJECT,
            netEvents = listOf(
                NetEvent(
                    1 * s, "metrics.shop.test",
                    cnameChain = listOf("metrics.shop.test", "tracker.example"),
                    answerIps = listOf("192.0.2.10", "2001:db8::10"),
                    disguisedVia = "tracker.example",
                ),
            ),
            captureHealth = health,
        )
        val json = AuditJson.encodeSessions(listOf(session))
        assertEquals(listOf(session), AuditJson.decodeSessions(json))
        assertTrue(json.contains("\"captureHealth\""))
        assertTrue(json.contains("\"disguisedVia\": \"tracker.example\""))
    }

    @Test
    fun `unmeasured counters stay unknown instead of becoming zero`() {
        val health = Json.decodeFromString(CaptureHealth.serializer(), "{}")
        assertEquals(CaptureHealth.SCHEMA_VERSION, health.schemaVersion)
        assertNull(health.dnsQueriesParsed)
        assertNull(health.malformedPackets)
        assertNull(health.eventsDropped)
        assertNull(health.dnsUpstreamPayloadBytesSent)
        assertNull(health.firstLookupNanos)

        val roundTripped = AuditJson.json.decodeFromString(CaptureHealth.serializer(), AuditJson.json.encodeToString(CaptureHealth.serializer(), health))
        assertNull(roundTripped.dnsUpstreamPayloadBytesSent)
        assertEquals(health, roundTripped)
    }

    @Test
    fun `sessions saved before G1 have no fresh-state record and keep their findings`() {
        val raw = Json.parseToJsonElement(legacyJson).jsonArray.single().jsonObject
        assertFalse(raw.containsKey("freshState"))
        val session = AuditJson.decodeSessions(legacyJson).single()
        assertNull("not recorded, not 'fresh' and not 'kept'", session.freshState)
        assertFalse("no 'not cleared' note, so the original rules apply", session.appDataKept)
    }

    @Test
    fun `fresh state round-trips with and without a value`() {
        val base = AuditSession(id = "a", targetPackage = "com.instagram.android", targetLabel = "Instagram", plan = AuditPlan.SILENT)
        for (fresh in listOf(true, false, null)) {
            val session = base.copy(freshState = fresh)
            val json = AuditJson.encodeSessions(listOf(session))
            assertEquals(listOf(session), AuditJson.decodeSessions(json))
        }
        val withoutKey = Json.parseToJsonElement(AuditJson.encodeSessions(listOf(base))).jsonArray.single().jsonObject
            .let { obj -> kotlinx.serialization.json.JsonObject(obj - "freshState") }.toString()
        assertNull(AuditJson.decodeSessions("[$withoutKey]").single().freshState)
    }

    @Test
    fun `new categories and checks are additive and old names still decode`() {
        val old = listOf("ANALYTICS", "ADVERTISING", "ATTRIBUTION", "SOCIAL", "CRASH_REPORTING", "ENGAGEMENT", "FIRST_PARTY", "UNKNOWN")
        old.forEach { assertEquals(it, Category.valueOf(it).name) }
        assertEquals(old, Category.entries.take(old.size).map { it.name })
        assertFalse(Category.CONTENT_DELIVERY.isTracking)
        assertFalse(Category.FIRST_PARTY_SAME_COMPANY.isTracking)
        val oldChecks = listOf("C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8", "C9", "C10", "NC", "V0")
        assertEquals(oldChecks, CheckId.entries.take(oldChecks.size).map { it.name })
        val e = NetEvent(1, "static.xx.fbcdn.net", category = Category.CONTENT_DELIVERY)
        assertEquals(e, AuditJson.json.decodeFromString(NetEvent.serializer(), AuditJson.json.encodeToString(NetEvent.serializer(), e)))
    }

    @Test
    fun `positional NetEvent construction is unchanged`() {
        val e = NetEvent(1, "a.example", 28, "X", Category.ANALYTICS, Phase.AFTER_REJECT, "com.google.android.gms")
        assertEquals(28, e.queryType)
        assertEquals("com.google.android.gms", e.viaPackage)
        assertTrue(e.cnameChain.isEmpty() && e.answerIps.isEmpty() && e.disguisedVia == null)
    }

    @Test
    fun `sessions saved before C11-C15 decode with their new evidence not recorded`() {
        val raw = Json.parseToJsonElement(legacyJson).jsonArray.single().jsonObject
        listOf("backgroundStartedNanos", "permissionDialogsWatched", "manifestFacts").forEach { assertFalse(it, raw.containsKey(it)) }
        val session = AuditJson.decodeSessions(legacyJson).single()
        assertNull("no background step recorded", session.backgroundStartedNanos)
        assertNull("permission dialogs not watched, not 'none seen'", session.permissionDialogsWatched)
        assertNull("manifest not read", session.manifestFacts)
        val result = RulesEngine(TrackerCatalog.loadDefault()).evaluate(listOf(session))
        listOf(CheckId.C11, CheckId.C12, CheckId.C13, CheckId.C14, CheckId.V1)
            .forEach { id -> assertTrue(id.code, result.findings.none { it.checkId == id }) }
        // Older audits did save the consent-screen tree, so C15 can be read from them; it is info and moves no score.
        val c15 = result.findings.single { it.checkId == CheckId.C15 }
        assertEquals(Severity.INFO, c15.severity)
        assertEquals(result.score, ScoreCalculator.compute(result.findings - c15).first)
    }

    @Test
    fun `new session evidence round-trips and an older encoder's output still decodes`() {
        val facts = AppManifestFacts(
            versionName = "4.2.0", versionCode = 420,
            collectionFlags = mapOf("firebase_analytics_collection_enabled" to "false"),
            sdkComponents = listOf("com.google.android.gms.measurement.AppMeasurementService"),
            partsRead = listOf(AppManifestFacts.PART_META_DATA, AppManifestFacts.PART_SERVICES),
        )
        val session = AuditSession(
            id = "a", targetPackage = "com.shop.app", targetLabel = "Shop", plan = AuditPlan.SILENT,
            consentEvents = listOf(ConsentEvent(1 * s, ConsentKind.PERMISSION_DIALOG, "Allow Shop to access your contacts?")),
            backgroundStartedNanos = 30 * s, permissionDialogsWatched = true, manifestFacts = facts,
        )
        assertEquals(listOf(session), AuditJson.decodeSessions(AuditJson.encodeSessions(listOf(session))))
        val partial = Json.decodeFromString(AppManifestFacts.serializer(), "{\"versionName\":\"1\"}")
        assertTrue(partial.partsRead.isEmpty() && partial.collectionFlags.isEmpty() && partial.sdkComponents.isEmpty())
    }

    @Test
    fun `new checks and consent kinds are appended, never renamed`() {
        assertEquals(
            listOf("C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8", "C9", "C10", "NC", "V0", "U1", "S1", "C11", "C12", "C13", "C14", "C15", "V1", "C16"),
            CheckId.entries.map { it.name },
        )
        CheckId.entries.forEach { assertEquals(it.name, it.code) }
        assertEquals(listOf("SCREEN_APPEARED", "ACCEPT", "REJECT", "SETTINGS", "MANUAL_MARK", "PERMISSION_DIALOG", "REPROMPT"), ConsentKind.entries.map { it.name })
    }
}
