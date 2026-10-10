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
    enum class Reason {
        MATCH,
        NO_MATCH,
        LOW_CONFIDENCE,
        UNREADABLE,

        /** Strict: other speech came right before the phrase in the same utterance (typical of a busy room). */
        SPEECH_BEFORE,

        /** Strict: the phrase was implausibly short, long or broken up for a person saying "Hey TraceVeil". */
        BAD_TIMING,

        /** Strict: the recogniser gave no per-word confidence, so the phrase cannot be scored. */
        NO_WORD_SCORES,
    }
}

/**
 * How picky the wake-word decision is. Neither mode is calibrated against measured false-accept rates yet; the
 * "Recent wake events" counter in Settings and the `HeyTraceVeil` log line exist so a presenter can see the effect.
 *
 * - [STRICT] (default, for noisy rooms): every word of the phrase at 0.90 or more, word scores required, the phrase
 *   must open the utterance (no speech straight before it), and it must take a plausible 0.35 to 2.5 seconds with
 *   no gap over 0.6 seconds between words. Speaking on after the phrase is fine.
 * - [NORMAL]: every word at 0.75 or more (up from the earlier 0.70 floor); nothing else is checked.
 */
enum class WakeSensitivity(
    val label: String,
    val hindi: String,
    val minWordConfidence: Double,
    val requireWordScores: Boolean,
    val requirePhraseFirst: Boolean,
    val checkTiming: Boolean,
) {
    NORMAL("Normal", "सामान्य", 0.75, requireWordScores = false, requirePhraseFirst = false, checkTiming = false),
    STRICT("Strict", "सख़्त", 0.90, requireWordScores = true, requirePhraseFirst = true, checkTiming = true);

    companion object {
        fun fromName(name: String?): WakeSensitivity? = entries.firstOrNull { it.name == name }
    }
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

    const val MIN_PHRASE_SECONDS = 0.35
    const val MAX_PHRASE_SECONDS = 2.5
    const val MAX_WORD_GAP_SECONDS = 0.6

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

    private class Word(val token: String, val conf: Double?, val start: Double?, val end: Double?)

    /**
     * Checks one Vosk final result (`{"text": ..., "result": [{"word", "conf", "start", "end"}, ...]}`) under the
     * chosen [sensitivity]. The returned confidence is the lowest word confidence inside the phrase.
     */
    fun detect(resultJson: String, sensitivity: WakeSensitivity = WakeSensitivity.STRICT): WakeHeard {
        val obj = runCatching { Json.parseToJsonElement(resultJson) as? JsonObject }.getOrNull()
            ?: return WakeHeard(false, WakeHeard.Reason.UNREADABLE)
        val text = (obj["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (!matches(text)) return WakeHeard(false, WakeHeard.Reason.NO_MATCH)

        val words = (obj["result"] as? JsonArray).orEmpty().mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val raw = (o["word"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val token = tokens(raw).singleOrNull() ?: return@mapNotNull null
            Word(token, o.number("conf"), o.number("start"), o.number("end"))
        }
        if (words.isEmpty()) {
            return if (sensitivity.requireWordScores) WakeHeard(false, WakeHeard.Reason.NO_WORD_SCORES)
            else WakeHeard(true, WakeHeard.Reason.MATCH, null)
        }

        val range = matchRange(words.map { it.token }) ?: return WakeHeard(false, WakeHeard.Reason.NO_MATCH)
        val phrase = range.map { words[it] }
        val lowest = phrase.mapNotNull { it.conf }.minOrNull()
        if (sensitivity.requireWordScores && phrase.any { it.conf == null }) return WakeHeard(false, WakeHeard.Reason.NO_WORD_SCORES, lowest)
        if (lowest != null && lowest < sensitivity.minWordConfidence) return WakeHeard(false, WakeHeard.Reason.LOW_CONFIDENCE, lowest)
        if (sensitivity.requirePhraseFirst && range.first > 0) return WakeHeard(false, WakeHeard.Reason.SPEECH_BEFORE, lowest)
        if (sensitivity.checkTiming && !plausibleTiming(phrase)) return WakeHeard(false, WakeHeard.Reason.BAD_TIMING, lowest)
        return WakeHeard(true, WakeHeard.Reason.MATCH, lowest)
    }

    /** Missing timings are not held against the phrase; only reported ones are checked. */
    private fun plausibleTiming(phrase: List<Word>): Boolean {
        val start = phrase.first().start
        val end = phrase.last().end
        if (start != null && end != null) {
            val length = end - start
            if (length < MIN_PHRASE_SECONDS || length > MAX_PHRASE_SECONDS) return false
        }
        return phrase.zipWithNext().all { (a, b) ->
            val gap = if (a.end != null && b.start != null) b.start - a.end else null
            gap == null || gap <= MAX_WORD_GAP_SECONDS
        }
    }

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun matchRange(tokens: List<String>): IntRange? {
        for (seq in SEQUENCES) {
            for (i in 0..tokens.size - seq.size) {
                if (tokens.subList(i, i + seq.size) == seq) return i until i + seq.size
            }
        }
        return null
    }
}

/** One wake decision as shown in Settings: when, the outcome, the lowest word confidence and the mode. No words or audio. */
data class WakeEventRecord(val atWallMs: Long, val reason: WakeHeard.Reason, val confidence: Double?, val sensitivity: WakeSensitivity) {
    val accepted: Boolean get() = reason == WakeHeard.Reason.MATCH
}

/**
 * Counts of accepted and rejected wake-phrase decisions since TraceVeil started, plus the last few. Results that did
 * not contain the phrase at all ([WakeHeard.Reason.NO_MATCH]) are ordinary speech or noise and are not counted.
 */
data class WakeEventLog(
    val accepted: Int = 0,
    val rejected: Int = 0,
    val rejectedBy: Map<WakeHeard.Reason, Int> = emptyMap(),
    val recent: List<WakeEventRecord> = emptyList(),
) {
    fun record(heard: WakeHeard, sensitivity: WakeSensitivity, atWallMs: Long): WakeEventLog {
        if (heard.reason == WakeHeard.Reason.NO_MATCH || heard.reason == WakeHeard.Reason.UNREADABLE) return this
        val entry = WakeEventRecord(atWallMs, heard.reason, heard.confidence, sensitivity)
        val recentNext = (listOf(entry) + recent).take(MAX_RECENT)
        return if (heard.accepted) copy(accepted = accepted + 1, recent = recentNext)
        else copy(
            rejected = rejected + 1,
            rejectedBy = rejectedBy + (heard.reason to (rejectedBy[heard.reason] ?: 0) + 1),
            recent = recentNext,
        )
    }

    companion object {
        const val MAX_RECENT = 8

        fun describe(reason: WakeHeard.Reason): String = when (reason) {
            WakeHeard.Reason.MATCH -> "accepted"
            WakeHeard.Reason.LOW_CONFIDENCE -> "rejected: not confident enough"
            WakeHeard.Reason.SPEECH_BEFORE -> "rejected: other speech just before it"
            WakeHeard.Reason.BAD_TIMING -> "rejected: too fast, too slow or broken up"
            WakeHeard.Reason.NO_WORD_SCORES -> "rejected: no word scores"
            WakeHeard.Reason.NO_MATCH -> "not the phrase"
            WakeHeard.Reason.UNREADABLE -> "unreadable result"
        }
    }
}
