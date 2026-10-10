package com.dpdpxray.core.ml

import com.dpdpxray.core.model.EvidenceType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostnameModelTest {
    private val model = HostnameModel.loadDefault()

    @Serializable
    private data class Golden(val source: String, val host: String, val logit: Double, val probability: Double)

    @Serializable
    private data class GoldenFile(val model: String, val version: String, val tolerance: Double, val samples: List<Golden>)

    private val golden: GoldenFile = Json { ignoreUnknownKeys = true }.decodeFromString(
        javaClass.getResourceAsStream("/ml/tracker_hostname_golden.json")!!.bufferedReader().use { it.readText() },
    )

    @Test
    fun `kotlin scores match the python reference for every exported sample`() {
        assertEquals(golden.version, model.version)
        assertTrue("expected about 50 golden samples", golden.samples.size >= 45)
        for (s in golden.samples) {
            val score = model.score(s.host)
            assertEquals("${s.source}: ${s.host}", s.logit, score.logit, golden.tolerance)
            assertEquals("${s.source}: ${s.host}", s.probability, score.probability, golden.tolerance)
        }
    }

    @Test
    fun `fnv-1a matches the published 32-bit test vectors`() {
        assertEquals(0x811C9DC5.toInt(), HostnameModel.fnv1a32(""))
        assertEquals(0xE40C292C.toInt(), HostnameModel.fnv1a32("a"))
        assertEquals(0xBF9CF968.toInt(), HostnameModel.fnv1a32("foobar"))
    }

    @Test
    fun `the site is scored, after trimming, lower-casing and dropping trailing dots`() {
        assertEquals("app-measurement.com", model.score("  Region1.App-Measurement.COM. ").site)
        assertEquals("example.co.in", HostnameModel.site("smetrics.example.co.in", model.secondLevel))
        assertEquals("example.io", HostnameModel.site("tracker.example.io", model.secondLevel))
        assertEquals("localhost", HostnameModel.site("localhost", model.secondLevel))
        assertEquals(model.score("app-measurement.com").probability, model.score("x.y.APP-measurement.com.").probability, 0.0)
    }

    @Test
    fun `explanations are the site's own n-grams that raise the score`() {
        val score = model.score("metrics-collector-analytics.com")
        assertTrue(score.topNgrams.isNotEmpty())
        assertTrue(score.topNgrams.all { it.weight > 0 && "^${score.site}$".contains(it.ngram) && it.ngram.length >= 3 })
        assertEquals(score.topNgrams.sortedByDescending { it.weight }, score.topNgrams)
    }

    @Test
    fun `wording is an inferred score and never claims data was sent`() {
        for (h in listOf("metrics-collector-analytics.com", "wikipedia.org")) {
            val s = model.score(h)
            assertEquals(EvidenceType.INFERRED, s.evidence)
            val text = s.summary().lowercase()
            assertTrue(text, text.contains("inferred"))
            assertTrue(text, text.contains("not proof that data was sent"))
            listOf("malicious", "is safe", "illegal", "sent your data").forEach { assertFalse(text, text.contains(it)) }
        }
    }

    @Test
    fun `the bundled weights stay small`() {
        val bytes = javaClass.getResourceAsStream("/ml/tracker_hostname.json")!!.use { it.readBytes() }.size
        assertTrue("tracker_hostname.json is $bytes bytes", bytes < 500 * 1024)
        assertEquals(1 shl model.hashBits, model.hashedSize)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a file with another schema is refused`() {
        HostnameModel.fromJson("""{"schema":"x","version":"1","intercept":0,"threshold":0.5,"dense":{},"hashedWeightsFloat32LeBase64":"","featureSpace":{"hashBits":1,"secondLevel":[]}}""")
    }
}
