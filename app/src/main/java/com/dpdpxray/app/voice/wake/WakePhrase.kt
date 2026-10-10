package com.dpdpxray.app.voice.wake

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.Locale

/** Outcome of checking one final recogniser result for the wake phrase. */
data class WakeHeard(val accepted: Boolean, val reason: Reason, val confidence: Double? = null) {
    enum class Reason { MATCH, NO_MATCH, LOW_CONFIDENCE, UNREADABLE }
}

/**
 * The "Hey TraceVeil" phrase: the restricted grammar handed to the offline recogniser and the matcher applied to its
 * final results. Partial results are never used, so a half-heard phrase cannot trigger anything.
 *
 * "traceveil" is not in the small English model's vocabulary; Vosk drops an unknown word from a grammar phrase, so in
 * practice that model can only produce "hey trace veil". The matcher still requires the whole phrase, so "hey" alone
 * never counts.
 */
object WakePhrase {
    val PHRASES = listOf("hey trace veil", "hey traceveil")
    const val UNKNOWN = "[unk]"

    /**
     * Lowest per-word confidence accepted inside the phrase. This is a conservative starting value, not a calibrated
     * threshold: false-accept and false-reject rates have not been measured on real phones yet.
     */
    const val MIN_WORD_CONFIDENCE = 0.70

    private const val GAP = "_"
    private val SEQUENCES = PHRASES.map { it.split(' ') }
    private val NON_WORD = Regex("[^a-z0-9_]+")

    /** JSON array for Vosk's grammar recogniser: only the wake phrase or unknown speech can be produced. */
    fun grammarJson(): String = (PHRASES + UNKNOWN).joinToString(",", "[", "]") { "\"$it\"" }

    /** Lower-case words without punctuation; recogniser "[unk]" becomes a gap token so it breaks a phrase. */
    fun tokens(text: String): List<String> =
        text.lowercase(Locale.ROOT)
            .replace(UNKNOWN, " $GAP ")
            .replace(NON_WORD, " ")
            .split(' ')
            .filter { it.isNotEmpty() }

    fun normalise(text: String): String = tokens(text).joinToString(" ")

    fun matches(text: String): Boolean = matchRange(tokens(text)) != null

    /**
     * Checks one Vosk final result (`{"text": ..., "result": [{"word", "conf"}, ...]}`). When word confidences are
     * present, every word of the matched phrase must reach [minConfidence].
     */
    fun detect(resultJson: String, minConfidence: Double = MIN_WORD_CONFIDENCE): WakeHeard {
        val obj = runCatching { Json.parseToJsonElement(resultJson) as? JsonObject }.getOrNull()
            ?: return WakeHeard(false, WakeHeard.Reason.UNREADABLE)
        val text = (obj["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (!matches(text)) return WakeHeard(false, WakeHeard.Reason.NO_MATCH)

        val words = (obj["result"] as? JsonArray).orEmpty().mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val word = (o["word"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val token = tokens(word).singleOrNull() ?: return@mapNotNull null
            token to (o["conf"] as? JsonPrimitive)?.doubleOrNull
        }
        if (words.isEmpty()) return WakeHeard(true, WakeHeard.Reason.MATCH, null)

        val range = matchRange(words.map { it.first }) ?: return WakeHeard(false, WakeHeard.Reason.NO_MATCH)
        val lowest = range.mapNotNull { words[it].second }.minOrNull()
        if (lowest != null && lowest < minConfidence) return WakeHeard(false, WakeHeard.Reason.LOW_CONFIDENCE, lowest)
        return WakeHeard(true, WakeHeard.Reason.MATCH, lowest)
    }

    private fun matchRange(tokens: List<String>): IntRange? {
        for (seq in SEQUENCES) {
            for (i in 0..tokens.size - seq.size) {
                if (tokens.subList(i, i + seq.size) == seq) return i until i + seq.size
            }
        }
        return null
    }
}
