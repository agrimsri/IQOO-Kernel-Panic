package com.dpdpxray.core.consentlens

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kotlin preprocessing and output decoding must match the Python training code byte for byte. */
class ConsentLensTextTest {
    @Serializable
    private data class EncodeCase(val text: String, val normalized: String, val ids: List<Int>)

    @Serializable
    private data class DecodeCase(val int8: List<Int>, val role: String)

    @Serializable
    private data class Decode(val scale: Float, val zero_point: Int, val roles: List<String>, val cases: List<DecodeCase>)

    @Serializable
    private data class Golden(val max_bytes: Int, val encode: List<EncodeCase>, val decode: Decode)

    private val golden: Golden = Json { ignoreUnknownKeys = true }.decodeFromString(
        javaClass.getResourceAsStream("/consentlens/golden.json")!!.bufferedReader().use { it.readText() },
    )

    @Test
    fun `input length matches the model`() {
        assertEquals(golden.max_bytes, ConsentLensText.MAX_BYTES)
    }

    @Test
    fun `normalization matches Python for every golden string`() {
        assertTrue(golden.encode.size >= 20)
        for (c in golden.encode) assertEquals("normalize(${c.text})", c.normalized, ConsentLensText.normalize(c.text))
    }

    @Test
    fun `byte ids padding and truncation match Python for every golden string`() {
        for (c in golden.encode) assertArrayEquals("encode(${c.text})", c.ids.toIntArray(), ConsentLensText.encode(c.text))
    }

    @Test
    fun `long text is truncated to the byte limit and short text is zero padded`() {
        val long = ConsentLensText.encode("a".repeat(500))
        assertEquals(ConsentLensText.MAX_BYTES, long.size)
        assertTrue(long.all { it == 'a'.code })
        val short = ConsentLensText.encode("OK")
        assertEquals(listOf('o'.code, 'k'.code), short.take(2))
        assertTrue(short.drop(2).all { it == 0 })
    }

    @Test
    fun `role order matches the model outputs`() {
        assertEquals(golden.decode.roles, ConsentRole.entries.map { it.name })
    }

    @Test
    fun `int8 softmax outputs decode to the Python reference role`() {
        val d = golden.decode
        for (c in d.cases) {
            val probs = ConsentLensDecoder.dequantize(c.int8.map { it.toByte() }.toByteArray(), d.scale, d.zero_point)
            assertEquals(c.role, ConsentLensDecoder.decode("x", probs).role.name)
        }
    }

    @Test
    fun `dequantization uses scale and zero point`() {
        val probs = ConsentLensDecoder.dequantize(byteArrayOf(-128, 127, 0), 1f / 256, -128)
        assertEquals(0.0, probs[0], 1e-9)
        assertEquals(255.0 / 256, probs[1], 1e-9)
        assertEquals(0.5, probs[2], 1e-9)
    }
}
