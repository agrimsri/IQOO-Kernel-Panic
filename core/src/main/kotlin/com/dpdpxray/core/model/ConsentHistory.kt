package com.dpdpxray.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Whether the person agreed earlier to tracking or personalised ads in the audited app, for plans that kept the app's
 * data and showed no consent screen (check U1). Only the person can say: TraceVeil never sets anything but [UNKNOWN] by
 * itself, and an answer is always labelled as theirs. Saved by name: add new values at the end and never rename one.
 */
@Serializable
enum class ConsentHistory {
    /** Not asked, or not answered: the result is the bounded range. */
    UNKNOWN,

    /** "No, never": the kept plans are judged as if no consent was ever given (the low end of the range). */
    DECLARED_NEVER,

    /** "Yes, I agreed": the kept plans are judged as after an agreed consent (the high end, never Ready on its own). */
    DECLARED_AGREED,

    /** "Not sure": asked, and the person does not know; the range stays. */
    NOT_SURE;

    /** True for the two answers that pick an end of the range. */
    val declared: Boolean get() = this == DECLARED_NEVER || this == DECLARED_AGREED

    /** One line for reports and the command-line tools. */
    fun line(): String = "Consent history: " + when (this) {
        UNKNOWN -> "unknown (not answered)"
        DECLARED_NEVER -> "declared by the person: never agreed"
        DECLARED_AGREED -> "declared by the person: agreed earlier"
        NOT_SURE -> "the person is not sure (unknown)"
    }
}

/**
 * The verdict of a result whose consent history is unknown, bounded by the two answers the person could give. Both ends
 * come from the same sessions and the same score calculator: [lowScore]/[lowBand] if the person never agreed (the kept
 * plans judged as a fresh plan would be), [highScore]/[highBand] if they agreed earlier (the same findings without U1).
 *
 * The result is never Ready on an unchecked history: [bestBand] is Ready only when both ends are. When [lowBand] equals
 * [bestBand] the band holds whichever way the person answers ([verdictBand]); otherwise the one question can decide it.
 */
@Serializable
data class HistoryBounds(
    val lowScore: Int,
    val lowBand: ScoreBand,
    val highScore: Int,
    val highBand: ScoreBand,
    /** The person's answer, once given; null = not asked or not answered. Written only when set. */
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault(EncodeDefault.Mode.NEVER)
    val answer: ConsentHistory? = null,
) {
    /** The best band this result can reach: the agreed-earlier end's band, but never Ready unless the other end is too. */
    val bestBand: ScoreBand get() = if (highBand == ScoreBand.READY && lowBand != ScoreBand.READY) ScoreBand.NEEDS_WORK else highBand

    /** The band is the same whichever way the person answers. */
    val bandSettled: Boolean get() = lowBand == bestBand

    /** The one question could change the band (and no answer that picks an end was given yet). */
    val questionNeeded: Boolean get() = !bandSettled && answer?.declared != true

    /** The band that holds either way, or null when the answer decides it. */
    val verdictBand: ScoreBand? get() = lowBand.takeIf { bandSettled }

    /** The score and band the person's answer picked, or null while it is a range. */
    val answered: Pair<Int, ScoreBand>? get() = when (answer) {
        ConsentHistory.DECLARED_NEVER -> lowScore to lowBand
        ConsentHistory.DECLARED_AGREED -> highScore to bestBand
        else -> null
    }

    val range: String get() = "$lowScore–$highScore"

    /** The short verdict: "Needs work either way · 65–99", "At best Needs work · 41–77", or the answered end. */
    fun verdict(hindi: Boolean = false): String {
        answered?.let { (score, band) ->
            return if (hindi) "$score · ${bandHi(band)} · आपके जवाब के आधार पर" else "$score · ${band.label} · based on your answer"
        }
        return when {
            bandSettled && hindi -> "दोनों हाल में ${bandHi(lowBand)} · $range"
            bandSettled -> "${lowBand.label} either way · $range"
            hindi -> "ज़्यादा से ज़्यादा ${bandHi(bestBand)} · $range"
            else -> "At best ${bestBand.label} · $range"
        }
    }

    /** What each end means, in one sentence. */
    fun ends(hindi: Boolean = false): String = if (hindi) {
        "अगर आपने कभी सहमति नहीं दी: $lowScore (${bandHi(lowBand)})। अगर आपने पहले सहमति दी थी: $highScore (${bandHi(bestBand)})।"
    } else {
        "If you never agreed: $lowScore (${lowBand.label}). If you agreed earlier: $highScore (${bestBand.label}" +
            (if (bestBand != highBand) ", never Ready on an unchecked answer" else "") + ")."
    }

    companion object {
        fun bandHi(b: ScoreBand) = when (b) {
            ScoreBand.READY -> "तैयार"
            ScoreBand.NEEDS_WORK -> "सुधार की ज़रूरत"
            ScoreBand.AT_RISK -> "जोखिम में"
            ScoreBand.INCONCLUSIVE -> "अनिर्णायक"
        }
    }
}
