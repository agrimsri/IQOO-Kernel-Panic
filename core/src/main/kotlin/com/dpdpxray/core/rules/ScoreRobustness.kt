package com.dpdpxray.core.rules

import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.ScoreBand

/**
 * How far AI or documentation readings could move a result: the score if every INFERRED finding were confirmed on the
 * phone ([worstCase]) and if every one were wrong ([bestCase]). Computed on the fly by [ScoreCalculator.robustness] from
 * the same findings; it never changes the score or band and is never stored.
 *
 * Always worstCase <= score <= bestCase, and the bands are ordered the same way, so the band is stable exactly when both
 * ends give the same band. Before clamping at 0 and rounding, the score is the midpoint of the interval, because an
 * INFERRED finding counts half.
 */
data class ScoreRobustness(
    val score: Int,
    val band: ScoreBand,
    val worstCase: Int,
    val worstBand: ScoreBand,
    val bestCase: Int,
    val bestBand: ScoreBand,
    /** INFERRED findings that carry points, in check order. */
    val inferred: List<CheckId>,
) {
    val bandStable: Boolean get() = worstBand == band && bestBand == band

    /** Whether AI or documentation readings can move this score at all (false when nothing inferred carries points, or the observed findings alone already give 0). */
    val movable: Boolean get() = bestCase > worstCase

    /** One calm line for under the score. */
    fun line(hindi: Boolean = false): String = if (hindi) {
        "AI और दस्तावेज़ों से लिए गए निष्कर्ष इस स्कोर को $worstCase से $bestCase के बीच ले जा सकते हैं · " +
            if (bandStable) "श्रेणी ${hi(band)} ही रहती है" else "श्रेणी बदल सकती है (${hi(worstBand)} से ${hi(bestBand)} तक)"
    } else {
        "AI and documentation readings can move this between $worstCase and $bestCase · " +
            if (bandStable) "the band stays ${band.label}" else "the band could change (${worstBand.label} to ${bestBand.label})"
    }

    /** The "why" behind [line]: which findings are inferred and what each end of the interval means. */
    fun detail(hindi: Boolean = false): String {
        val codes = inferred.joinToString { it.code }
        val one = inferred.size == 1
        return if (hindi) {
            "INFERRED चिह्नित " + (if (one) "निष्कर्ष ($codes) AI स्क्रीन समीक्षा या SDK दस्तावेज़ों से आता है और आधा गिना जाता है। "
            else "${inferred.size} निष्कर्ष ($codes) AI स्क्रीन समीक्षा या SDK दस्तावेज़ों से आते हैं और आधे गिने जाते हैं। ") +
                (if (one) "अगर यह फ़ोन पर पक्का होता तो स्कोर $worstCase (${hi(worstBand)}) होता; अगर यह गलत होता तो $bestCase (${hi(bestBand)})। "
                else "अगर सभी फ़ोन पर पक्के होते तो स्कोर $worstCase (${hi(worstBand)}) होता; अगर सभी गलत होते तो $bestCase (${hi(bestBand)})। ") +
                "दिखाया गया स्कोर नहीं बदलता।"
        } else {
            (if (one) "The finding marked INFERRED ($codes) comes from AI screen review or SDK documentation and counts half. "
            else "The ${inferred.size} findings marked INFERRED ($codes) come from AI screen review or SDK documentation and count half. ") +
                (if (one) "If it were confirmed on the phone the score would be $worstCase (${worstBand.label}); if it were wrong, $bestCase (${bestBand.label}). "
                else "If all were confirmed on the phone the score would be $worstCase (${worstBand.label}); if all were wrong, $bestCase (${bestBand.label}). ") +
                "The score shown does not change."
        }
    }

    private fun hi(b: ScoreBand) = when (b) {
        ScoreBand.READY -> "तैयार"
        ScoreBand.NEEDS_WORK -> "सुधार की ज़रूरत"
        ScoreBand.AT_RISK -> "जोखिम में"
        ScoreBand.INCONCLUSIVE -> "अनिर्णायक"
    }
}
