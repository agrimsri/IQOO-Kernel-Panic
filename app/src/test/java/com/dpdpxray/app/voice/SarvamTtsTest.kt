package com.dpdpxray.app.voice

import com.dpdpxray.core.copilot.CopilotLanguage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SarvamTtsTest {
    @Test
    fun `request body uses bulbul v3 and language_code`() {
        val body = Json.parseToJsonElement(SarvamTts.requestBody("नमस्ते", CopilotLanguage.HI)).jsonObject
        assertEquals("bulbul:v3", body["model"]!!.jsonPrimitive.content)
        assertEquals("hi-IN", body["language_code"]!!.jsonPrimitive.content)
        assertEquals("नमस्ते", body["text"]!!.jsonPrimitive.content)
        assertEquals("wav", body["output_audio_codec"]!!.jsonPrimitive.content)
        assertTrue("v3 rejects pitch", "pitch" !in body && "loudness" !in body)
    }

    @Test
    fun `long text is cut to the REST limit`() {
        val body = Json.parseToJsonElement(SarvamTts.requestBody("a".repeat(3000), CopilotLanguage.EN)).jsonObject
        assertEquals(SarvamTts.MAX_CHARS, body["text"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun `parses base64 wav from the audios array`() {
        val wav = "RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray(Charsets.ISO_8859_1)
        val json = """{"request_id":"r-1","audios":["${Base64.getEncoder().encodeToString(wav)}"]}"""
        val r = SarvamTts.parseResponse(json) as SarvamResult.Audio
        assertArrayEquals(wav, r.wav)
        assertEquals("r-1", r.requestId)
    }

    @Test
    fun `rejects a response that is not a wav`() {
        val json = """{"request_id":"r-2","audios":["${Base64.getEncoder().encodeToString("hello".toByteArray())}"]}"""
        assertTrue(SarvamTts.parseResponse(json) is SarvamResult.Failed)
    }

    @Test
    fun `error bodies keep only the code, never echo text`() {
        val r = SarvamTts.parseError(403, """{"error":{"message":"Invalid API key abc123","code":"invalid_api_key_error","request_id":"x"}}""")
        assertEquals("HTTP 403 invalid_api_key_error", r.reason)
        assertTrue(!r.reason.contains("abc123"))
    }

    @Test
    fun `empty audios is a failure`() = assertTrue(SarvamTts.parseResponse("""{"request_id":null,"audios":[]}""") is SarvamResult.Failed)

    @Test
    fun `uses a female Indian voice per language`() {
        fun speaker(l: CopilotLanguage) =
            Json.parseToJsonElement(SarvamTts.requestBody("x", l)).jsonObject["speaker"]!!.jsonPrimitive.content
        assertEquals("ishita", speaker(CopilotLanguage.EN))
        assertEquals("priya", speaker(CopilotLanguage.HI))
    }
}
