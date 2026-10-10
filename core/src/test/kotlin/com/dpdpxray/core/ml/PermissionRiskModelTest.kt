package com.dpdpxray.core.ml

import com.dpdpxray.core.model.EvidenceType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionRiskModelTest {
    private val model = PermissionRiskModel.loadDefault()

    @Serializable
    private data class Golden(val source: String, val permissions: List<String>, val logit: Double, val probability: Double)

    @Serializable
    private data class GoldenFile(val model: String, val version: String, val tolerance: Double, val samples: List<Golden>)

    private val golden: GoldenFile = Json { ignoreUnknownKeys = true }.decodeFromString(
        javaClass.getResourceAsStream("/ml/apk_permission_risk_golden.json")!!.bufferedReader().use { it.readText() },
    )

    @Test
    fun `kotlin scores match the python reference for every exported sample`() {
        assertEquals(golden.version, model.version)
        assertTrue("expected about 50 golden samples", golden.samples.size >= 45)
        for (s in golden.samples) {
            val score = model.score(s.permissions.toSet())
            assertEquals(s.source, s.logit, score.logit, golden.tolerance)
            assertEquals(s.source, s.probability, score.probability, golden.tolerance)
        }
    }

    @Test
    fun `the bundled model is small, labelled inferred and has a usable threshold`() {
        assertEquals(85, model.vocabularySize)
        assertTrue(model.threshold > 0.0 && model.threshold < 1.0)
        assertTrue(model.sources.any { it.contains("NATICUSdroid") })
        assertEquals(EvidenceType.INFERRED, model.score(emptySet()).evidence)
    }

    @Test
    fun `permissions outside the vocabulary are ignored, never guessed`() {
        val empty = model.score(emptySet())
        val unknown = model.score(setOf("com.example.app.permission.C2D_MESSAGE", "android.permission.NOT_A_REAL_ONE"))
        assertEquals(empty.probability, unknown.probability, 0.0)
        assertEquals(0, unknown.recognised)
    }

    @Test
    fun `a doubled android permission prefix counts as the platform permission`() {
        val doubled = model.score(setOf("android.permission.android.permission.READ_PHONE_STATE"))
        val plain = model.score(setOf("android.permission.READ_PHONE_STATE"))
        assertEquals(plain.probability, doubled.probability, 0.0)
        assertEquals(1, doubled.recognised)
    }

    @Test
    fun `the explanation lists only the app's own permissions, strongest first`() {
        val perms = setOf(
            "android.permission.SEND_SMS", "android.permission.READ_PHONE_STATE",
            "com.google.android.c2dm.permission.RECEIVE", "com.example.UNKNOWN",
        )
        val score = model.score(perms)
        assertEquals(3, score.recognised)
        assertTrue(score.raising.all { it.permission in perms && it.weight > 0 })
        assertTrue(score.lowering.all { it.permission in perms && it.weight < 0 })
        assertEquals(score.raising.sortedByDescending { it.weight }, score.raising)
        assertEquals(score.lowering.sortedBy { it.weight }, score.lowering)
        assertEquals("android.permission.READ_PHONE_STATE", score.raising.first().permission)
        assertEquals("com.google.android.c2dm.permission.RECEIVE", score.lowering.first().permission)
    }

    @Test
    fun `the wording is an inferred score, never a verdict`() {
        val high = model.score(setOf("android.permission.READ_PHONE_STATE", "android.permission.SEND_SMS", "android.permission.RECEIVE_SMS"))
        val low = model.score(setOf("android.permission.INTERNET", "com.google.android.c2dm.permission.RECEIVE", "com.android.vending.BILLING"))
        assertTrue(high.elevated)
        assertFalse(low.elevated)
        for (s in listOf(high, low)) {
            val text = s.summary().lowercase()
            assertTrue(text, text.contains("inferred"))
            assertTrue(text, text.contains("not a malware verdict"))
            listOf("malicious", "is safe", "is malware", "fraud", "illegal").forEach { assertFalse(text, text.contains(it)) }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a file with another schema is refused`() {
        PermissionRiskModel.fromJson("""{"schema":"something-else","version":"1","intercept":0,"threshold":0.5,"weights":{}}""")
    }
}
