package com.dpdpxray.core.model

import com.dpdpxray.core.report.AuditJson
import com.dpdpxray.core.rules.RulesEngine
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
    fun `positional NetEvent construction is unchanged`() {
        val e = NetEvent(1, "a.example", 28, "X", Category.ANALYTICS, Phase.AFTER_REJECT, "com.google.android.gms")
        assertEquals(28, e.queryType)
        assertEquals("com.google.android.gms", e.viaPackage)
        assertTrue(e.cnameChain.isEmpty() && e.answerIps.isEmpty() && e.disguisedVia == null)
    }
}
