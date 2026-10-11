package com.dpdpxray.core.web

import com.dpdpxray.core.export.AuditBundles
import com.dpdpxray.core.export.BundleApp
import com.dpdpxray.core.export.BundleTarget
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.report.AuditJson
import com.dpdpxray.core.rules.RulesEngine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A website check travels in the same traceveil.audit.v1 bundle, with an optional target.kind = "web". */
class WebExportTest {
    private val sessions = listOf(WebTestData.build(WebTestData.leakyReject()))
    private val result = RulesEngine(WebTestData.catalog).evaluate(sessions)
    private val meta = JsonObject(mapOf("id" to JsonPrimitive("web-1"), "targetKind" to JsonPrimitive("web"), "score" to JsonPrimitive(result.score)))

    private fun bundle(target: BundleTarget?, s: List<AuditSession> = sessions) = AuditBundles.audit(
        app = BundleApp("web:news.example", "https://news.example"), meta = meta, sessions = s, result = result,
        networkReadings = null, toolVersion = "1.1.0", exportedAt = "2026-10-11T08:00:00Z", target = target,
    )

    @Test
    fun `a website bundle round-trips with its target and web evidence`() {
        val b = bundle(BundleTarget(WebAudit.KIND, "https://news.example/"))
        val text = AuditBundles.encode(b)
        val back = AuditBundles.decode(text)
        assertEquals(b, back)
        assertEquals("web", back.target?.kind)
        assertEquals("https://news.example/", back.target?.url)
        assertEquals(sessions.single().web, back.sessions.single().web)
        assertEquals("web", AuditBundles.metaOf(back).targetKind)
        // The rules give the same result from the exported sessions (what traceveil-ci check re-evaluates).
        assertEquals(result, RulesEngine(WebTestData.catalog).evaluate(back.sessions))
        val root = AuditBundles.element(b).jsonObject
        assertEquals(JsonPrimitive("web"), root.getValue("target").jsonObject["kind"])
        assertTrue(root.getValue("sessions").jsonArray.first().jsonObject.containsKey("web"))
    }

    @Test
    fun `an app bundle keeps exactly its old shape - no target, no web part`() {
        val app = sessions.map { it.copy(web = null, targetPackage = "com.shop.app") }
        val root = AuditBundles.element(bundle(null, app)).jsonObject
        assertFalse(root.containsKey("target"))
        assertFalse(root.getValue("sessions").jsonArray.first().jsonObject.containsKey("web"))
        assertFalse(AuditJson.encodeSessions(app).contains("\"web\""))
        assertNull(AuditBundles.metaOf(AuditBundles.decode(AuditBundles.encode(bundle(null, app)).replace("\"targetKind\": \"web\",", ""))).targetKind)
    }

    @Test
    fun `an older reader's view - unknown target and web fields are ignored and the bundle still reads`() {
        val text = AuditBundles.encode(bundle(BundleTarget(WebAudit.KIND, "https://news.example/")))
            .replace("\"target\"", "\"futureTarget\"")
        val back = AuditBundles.decode(text)
        assertNull(back.target)
        assertEquals(AuditPlan.REJECT, back.sessions.single().plan)
        assertEquals(result.score, back.result.score)
    }
}
