package com.dpdpxray.app.ui.screens

import com.dpdpxray.app.data.SampleAudit
import com.dpdpxray.app.ui.guide.GuideLang
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The robustness line under the score on the result screen, for the in-app LeakyShop sample (8/100). */
class ScoreRobustnessLineTest {
    private val engine = RulesEngine(TrackerCatalog.loadDefault())
    private val sample = engine.evaluate(SampleAudit.sessions(0L))

    @Test
    fun `the sample shows its range and why, in English and Hindi`() {
        assertEquals(8, sample.score)
        val rob = ScoreRobustnessLine.of(sample)!!
        assertEquals(
            "AI and documentation readings can move this between 0 and 25 · the band stays At risk. " +
                "The 5 findings marked INFERRED (C5, C6, C7, C8, C10) come from AI screen review or SDK documentation and count half. " +
                "If all were confirmed on the phone the score would be 0 (At risk); if all were wrong, 25 (At risk). The score shown does not change.",
            ScoreRobustnessLine.text(rob, GuideLang.EN),
        )
        assertEquals(
            "AI और दस्तावेज़ों से लिए गए निष्कर्ष इस स्कोर को 0 से 25 के बीच ले जा सकते हैं · श्रेणी जोखिम में ही रहती है। " +
                "INFERRED चिह्नित 5 निष्कर्ष (C5, C6, C7, C8, C10) AI स्क्रीन समीक्षा या SDK दस्तावेज़ों से आते हैं और आधे गिने जाते हैं। " +
                "अगर सभी फ़ोन पर पक्के होते तो स्कोर 0 (जोखिम में) होता; अगर सभी गलत होते तो 25 (जोखिम में)। दिखाया गया स्कोर नहीं बदलता।",
            ScoreRobustnessLine.text(rob, GuideLang.HI),
        )
    }

    @Test
    fun `nothing is shown when no inferred finding carries points`() {
        assertNull(ScoreRobustnessLine.of(sample.copy(findings = sample.findings.filter { it.evidenceType.name == "OBSERVED" }, score = 25)))
    }

    @Test
    fun `nothing is shown for a saved result whose score the current rules would not give`() {
        assertNull(ScoreRobustnessLine.of(sample.copy(score = 21)))
        assertNull(ScoreRobustnessLine.of(sample.copy(band = ScoreBand.NEEDS_WORK)))
    }
}
