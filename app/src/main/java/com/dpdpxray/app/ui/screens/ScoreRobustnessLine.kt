package com.dpdpxray.app.ui.screens

import com.dpdpxray.app.ui.guide.GuideLang
import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.rules.ScoreCalculator
import com.dpdpxray.core.rules.ScoreRobustness

/**
 * The calm line under the score on the result screen: how far AI and documentation readings (INFERRED findings) could
 * move this result, with the "why" behind a fold. Computed from the saved findings each time; nothing is stored.
 */
object ScoreRobustnessLine {
    /**
     * The interval to show, or null when inferred findings cannot move the score (none carries points, or the observed
     * findings alone already give 0), or when the saved score is not the one today's rules give for the saved findings
     * (an audit saved under older rules), so the line never contradicts the score above it.
     */
    fun of(result: AuditResult): ScoreRobustness? = ScoreCalculator.robustness(result.findings)
        ?.takeIf { it.movable && it.score == result.score && it.band == result.band }

    /** The line, then the reason; FoldText shows the line and folds the reason behind "More". */
    fun text(r: ScoreRobustness, lang: GuideLang): String {
        val hi = lang == GuideLang.HI
        return r.line(hi) + (if (hi) "। " else ". ") + r.detail(hi)
    }
}
