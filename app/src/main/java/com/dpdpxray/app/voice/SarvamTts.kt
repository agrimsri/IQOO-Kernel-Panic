package com.dpdpxray.app.voice

import com.dpdpxray.core.copilot.CopilotLanguage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

sealed interface SarvamResult {
    class Audio(val wav: ByteArray, val requestId: String?) : SarvamResult
    data class Failed(val reason: String) : SarvamResult
}

/**
 * Sarvam Bulbul v3 text-to-speech over REST (POST /text-to-speech, `api-subscription-key` header, base64 WAV reply).
 * Only used when [ApiConfig.useApi] is on. Blocking: call it off the main thread. Never logs the key or the text.
 */
object SarvamTts {
    private const val ENDPOINT = "https://api.sarvam.ai/text-to-speech"
    const val MAX_CHARS = 2500

    fun requestBody(text: String, language: CopilotLanguage): String = JsonObject(
        mapOf(
            "text" to JsonPrimitive(text.take(MAX_CHARS)),
            "language_code" to JsonPrimitive(language.tag),
            "model" to JsonPrimitive("bulbul:v3"),
            "speaker" to JsonPrimitive("shubh"),
            "pace" to JsonPrimitive(1.0),
            "speech_sample_rate" to JsonPrimitive(22050),
            "output_audio_codec" to JsonPrimitive("wav"),
        ),
    ).toString()

    fun parseResponse(body: String): SarvamResult = runCatching {
        val obj = Json.parseToJsonElement(body).jsonObject
        val requestId = (obj["request_id"] as? JsonPrimitive)?.contentOrNull
        val first = (obj["audios"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.content
            ?: return SarvamResult.Failed("No audio in the response")
        val wav = Base64.getDecoder().decode(first)
        if (!isWav(wav)) SarvamResult.Failed("Response audio is not WAV") else SarvamResult.Audio(wav, requestId)
    }.getOrElse { SarvamResult.Failed("Unreadable response") }

    /** Keeps only the HTTP status and the error code: messages can echo request details. */
    fun parseError(status: Int, body: String?): SarvamResult.Failed {
        val code = runCatching {
            Json.parseToJsonElement(body.orEmpty()).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return SarvamResult.Failed("HTTP $status${code?.let { " $it" }.orEmpty()}")
    }

    fun synthesize(text: String, language: CopilotLanguage, apiKey: String): SarvamResult = runCatching {
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("api-subscription-key", apiKey)
        }
        try {
            conn.outputStream.use { it.write(requestBody(text, language).toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            if (status == 200) parseResponse(conn.inputStream.bufferedReader().use { it.readText() })
            else parseError(status, conn.errorStream?.bufferedReader()?.use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }.getOrElse { SarvamResult.Failed("Network error (${it.javaClass.simpleName})") }

    private fun isWav(b: ByteArray) = b.size >= 12 &&
        String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WAVE"
}
