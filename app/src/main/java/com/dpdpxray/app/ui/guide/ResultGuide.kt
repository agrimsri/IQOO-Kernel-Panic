package com.dpdpxray.app.ui.guide

import com.dpdpxray.core.model.ScoreBand

/** Saved facts the Results guide is derived from. Nothing here changes a score, band or finding. */
data class ResultFacts(
    val band: ScoreBand,
    val score: Int?,
    val findingCount: Int,
    val fixCount: Int,
    /** A planned Accept/Reject choice was never recorded (see AuditPlanCoverage.gaps). */
    val coverageGaps: Boolean,
    /** A Reject run was part of this check at all. */
    val rejectPlanned: Boolean,
    val fastPreview: Boolean,
    val sample: Boolean,
    /** Why an inconclusive result has no score; null for the older "too little was captured" wording. */
    val inconclusiveReason: com.dpdpxray.core.rules.Inconclusive.Reason? = null,
    /** The consent-history range of a kept-data check (score still open); null when the result has one score. */
    val bounds: com.dpdpxray.core.model.HistoryBounds? = null,
    /** A website check: its evidence is the requests the page made in TraceVeil's own browser, not DNS lookups. */
    val web: Boolean = false,
    /** A background watch of this many minutes: what the app sends in the background, its consent screen not reached. */
    val watchMinutes: Int? = null,
)

enum class NextAction { SEE_EVIDENCE, HOW_TO_FIX, CHECK_AGAIN, CHECK_REAL_APP, ASK, REPORT }

data class NextStep(val action: NextAction, val title: Bi, val reason: Bi)

data class ResultExplanation(val meaning: List<Bi>, val next: List<NextStep>)

/** "What this means" and "What you can do next" in plain language, from the score band, findings and coverage. */
object ResultGuide {
    val meaningTitle = Bi("What this means", "इसका मतलब")
    val nextTitle = Bi("What you can do next", "आगे आप क्या कर सकते हैं")

    private fun findings(n: Int) = if (n == 1) "1 finding" else "$n findings"

    private val lookupCaveat = Bi(
        "A lookup is an attempt to reach an address, not proof that personal data was sent.",
        "लुकअप किसी पते तक पहुँचने की कोशिश है, यह इस बात का सबूत नहीं कि निजी डेटा भेजा गया।",
    )

    private val requestCaveat = Bi(
        "A request shows the page asked to contact an address, not proof of what personal data was sent.",
        "रिक्वेस्ट दिखाती है कि पेज ने किसी पते से संपर्क माँगा, यह इस बात का सबूत नहीं कि कौन-सा निजी डेटा भेजा गया।",
    )

    fun explain(f: ResultFacts): ResultExplanation {
        val meaning = buildList {
            add(
                when (f.band) {
                    ScoreBand.READY -> Bi(
                        "No problems were found in the evidence TraceVeil captured. This is not a safety or compliance certificate: it covers only " +
                            (if (f.web) "the requests this site made during this short check."
                            else if (f.watchMinutes != null) "what the app did in this ${f.watchMinutes}-minute background watch."
                            else "this short window of DNS lookups."),
                        "TraceVeil के दर्ज सबूतों में कोई समस्या नहीं मिली। यह सुरक्षा या अनुपालन का प्रमाणपत्र नहीं है: यह सिर्फ़ " +
                            (if (f.web) "इस छोटी जाँच में साइट की रिक्वेस्ट को दिखाता है।"
                            else if (f.watchMinutes != null) "${f.watchMinutes} मिनट की इस बैकग्राउंड निगरानी में ऐप के काम को दिखाता है।"
                            else "DNS लुकअप की इस छोटी अवधि को दिखाता है।"),
                    )
                    ScoreBand.NEEDS_WORK -> Bi(
                        "TraceVeil recorded ${findings(f.findingCount)} in the captured evidence. The consent flow needs work; each finding below shows its evidence.",
                        "TraceVeil ने दर्ज सबूतों में ${f.findingCount} फ़ाइंडिंग पाईं। कंसेंट फ़्लो में सुधार की ज़रूरत है; नीचे हर फ़ाइंडिंग का सबूत दिया है।",
                    )
                    ScoreBand.AT_RISK -> Bi(
                        "TraceVeil recorded ${findings(f.findingCount)}, and the score is in the At risk range. Open each finding to see the ${if (f.web) "requests" else "lookups"} and times behind it.",
                        "TraceVeil ने ${f.findingCount} फ़ाइंडिंग दर्ज कीं, और स्कोर At risk श्रेणी में है। हर फ़ाइंडिंग खोलकर उसके पीछे के ${if (f.web) "रिक्वेस्ट" else "लुकअप"} और समय देखें।",
                    )
                    ScoreBand.INCONCLUSIVE -> if (f.bounds != null) range(f.bounds)
                    else if (f.inconclusiveReason == com.dpdpxray.core.rules.Inconclusive.Reason.CONSENT_HISTORY_UNKNOWN) Bi(
                        "No score: the lookups were captured, but this app kept its data, so whether you agreed to tracking earlier can't be seen. Unknown stays unknown: this is not a pass.",
                        "कोई स्कोर नहीं: लुकअप दर्ज हुए, लेकिन ऐप ने अपना डेटा रखा, इसलिए आपने पहले ट्रैकिंग की सहमति दी थी या नहीं, यह नहीं दिखता। अज्ञात, अज्ञात ही रहता है: इसे पास न समझें।",
                    ) else if (f.watchMinutes != null) Bi(
                        "The app made almost no connections in ${f.watchMinutes} minutes of watching, so there is nothing to score. Unknown stays unknown: this is not a pass.",
                        "${f.watchMinutes} मिनट की निगरानी में ऐप ने लगभग कोई कनेक्शन नहीं बनाया, इसलिए स्कोर देने को कुछ नहीं है। अज्ञात, अज्ञात ही रहता है: इसे पास न समझें।",
                    ) else Bi(
                        "Too little was captured to give a score. Unknown stays unknown: this is not a pass.",
                        "स्कोर देने के लिए बहुत कम जानकारी दर्ज हुई। अज्ञात, अज्ञात ही रहता है: इसे पास न समझें।",
                    )
                },
            )
            if (f.findingCount > 0) add(if (f.web) requestCaveat else lookupCaveat)
            if (f.coverageGaps) add(Bi(
                "Part of the planned check didn't finish, so the score covers only what was recorded.",
                "जाँच का कुछ तय हिस्सा पूरा नहीं हुआ, इसलिए स्कोर सिर्फ़ दर्ज हिस्से पर आधारित है।",
            ))
            if (f.watchMinutes != null) add(Bi(
                "This was a ${f.watchMinutes}-minute background watch: it shows what the app sends while it runs in the background. Its consent screen was not reached, so the popup itself was not tested.",
                "यह ${f.watchMinutes} मिनट की बैकग्राउंड निगरानी थी: यह दिखाती है कि ऐप बैकग्राउंड में चलते हुए क्या भेजता है। इसकी सहमति स्क्रीन तक नहीं पहुँचा गया, इसलिए पॉपअप की जाँच नहीं हुई।",
            ))
            else if (f.fastPreview) add(Bi(
                "This was a Fast Preview: one short Accept run. Don't-touch and Reject runs weren't tested.",
                "यह Fast Preview था: Accept का एक छोटा रन। बिना छुए और Reject वाले रन की जाँच नहीं हुई।",
            ))
            else if (!f.rejectPlanned && !f.sample) add(Bi(
                "Refusing consent wasn't tested in this check, so what happens after Reject is unknown.",
                "इस जाँच में Reject की जाँच नहीं हुई, इसलिए Reject के बाद क्या होता है, यह पता नहीं है।",
            ))
            if (f.sample) add(Bi(
                "This is a built-in sample, not a check of an app on your phone.",
                "यह पहले से बना नमूना है, आपके फ़ोन के किसी ऐप की जाँच नहीं।",
            ))
        }

        val incomplete = f.coverageGaps || f.fastPreview || !f.rejectPlanned || f.band == ScoreBand.INCONCLUSIVE
        val next = buildList {
            if (f.findingCount > 0) add(NextStep(NextAction.SEE_EVIDENCE,
                Bi("See the evidence", "सबूत देखें"),
                if (f.web) Bi("Open the first finding to see the requests and times behind it.", "पहली फ़ाइंडिंग खोलकर उसकी रिक्वेस्ट और समय देखें।")
                else Bi("Open the first finding to see the lookups and times behind it.", "पहली फ़ाइंडिंग खोलकर उसके लुकअप और समय देखें।")))
            if (f.fixCount > 0) add(NextStep(NextAction.HOW_TO_FIX,
                Bi("How to fix (${f.fixCount})", "कैसे ठीक करें (${f.fixCount})"),
                Bi("Code and manifest changes for developers. Rebuild, then check again.", "डेवलपर्स के लिए कोड और मैनिफ़ेस्ट बदलाव। फिर से बनाएँ और दोबारा जाँचें।")))
            if (f.sample) add(NextStep(NextAction.CHECK_REAL_APP,
                Bi("Check a real app", "असली ऐप जाँचें"),
                Bi("Go back and check an app you're allowed to test.", "वापस जाएँ और ऐसा ऐप जाँचें जिसकी आपको अनुमति है।")))
            else if (incomplete) add(NextStep(NextAction.CHECK_AGAIN,
                Bi("Check again", "दोबारा जाँचें"),
                if (f.web) Bi("Check the site again: each run starts in an emptied browser, and you can tap its banner yourself.",
                    "साइट को दोबारा जाँचें: हर रन खाली ब्राउज़र में शुरू होता है, और आप उसका बैनर ख़ुद टैप कर सकते हैं।")
                else Bi("For your own test app, a Full check runs don't touch, Reject and Accept for a fuller picture.",
                    "अपने टेस्ट ऐप के लिए Full जाँच बिना छुए, Reject और Accept, तीनों रन चलाती है।")))
            add(NextStep(NextAction.ASK,
                Bi("Ask this audit", "इस ऑडिट से पूछें"),
                Bi("Ask by voice or tap, in English or Hindi. Answers come from this check's evidence.",
                    "आवाज़ या टैप से, अंग्रेज़ी या हिंदी में पूछें। जवाब इसी जाँच के सबूतों से आते हैं।")))
            add(NextStep(NextAction.REPORT,
                Bi("Report and export", "रिपोर्ट और एक्सपोर्ट"),
                Bi("Save or share a PDF or Markdown report, limits included.", "PDF या Markdown रिपोर्ट सहेजें या साझा करें, सीमाओं सहित।")))
        }
        return ResultExplanation(meaning, next)
    }

    /** A kept-data check's range: the band it holds either way, or the best it can reach, and why it is a range. */
    private fun range(b: com.dpdpxray.core.model.HistoryBounds): Bi {
        val never = " On an unchecked answer it is never Ready."
        return if (b.bandSettled) Bi(
            "${b.lowBand.label} whichever way you answer (${b.range}). The app kept its data, so whether you agreed to tracking earlier can't be seen: " +
                "${b.lowScore} if you never agreed, ${b.highScore} if you did." + never,
            "आप जो भी जवाब दें, ${bandHi(b.lowBand)} (${b.range})। ऐप ने अपना डेटा रखा, इसलिए यह नहीं दिखता कि आपने पहले ट्रैकिंग की सहमति दी थी या नहीं: " +
                "कभी नहीं दी तो ${b.lowScore}, दी थी तो ${b.highScore}। सिर्फ़ जवाब के आधार पर यह कभी 'तैयार' नहीं होता।",
        ) else Bi(
            "At best ${b.bestBand.label} (${b.range}). The app kept its data, so whether you agreed to tracking earlier can't be seen; " +
                "answer one question below to pick the score." + never,
            "ज़्यादा से ज़्यादा ${bandHi(b.bestBand)} (${b.range})। ऐप ने अपना डेटा रखा, इसलिए यह नहीं दिखता कि आपने पहले ट्रैकिंग की सहमति दी थी या नहीं; " +
                "स्कोर चुनने के लिए नीचे एक सवाल का जवाब दें। सिर्फ़ जवाब के आधार पर यह कभी 'तैयार' नहीं होता।",
        )
    }

    private fun bandHi(band: ScoreBand) = when (band) {
        ScoreBand.READY -> "तैयार"
        ScoreBand.NEEDS_WORK -> "सुधार की ज़रूरत"
        ScoreBand.AT_RISK -> "जोखिम में"
        ScoreBand.INCONCLUSIVE -> "अनिर्णायक"
    }

    /** Step 4 of the journey. The spoken line carries only score, band and counts: no app, host or tracker names. */
    fun step(f: ResultFacts): GuideStep {
        val b = f.bounds?.takeIf { f.score == null }
        val scoreEn = if (b != null) {
            if (b.bandSettled) "${b.lowBand.label} either way, ${b.lowScore} to ${b.highScore}, depending on whether you agreed to tracking earlier."
            else "At best ${b.bestBand.label}, ${b.lowScore} to ${b.highScore}: one answer about your consent history decides it."
        } else if ((f.band == ScoreBand.INCONCLUSIVE || f.score == null) && f.watchMinutes != null && f.inconclusiveReason == com.dpdpxray.core.rules.Inconclusive.Reason.LOW_VISIBILITY)
            "Inconclusive: the app made almost no connections in ${f.watchMinutes} minutes of watching."
        else if (f.band == ScoreBand.INCONCLUSIVE || f.score == null) (f.inconclusiveReason?.en ?: "Inconclusive: too little was captured to score.")
            else "Score ${f.score} out of 100: ${f.band.label}."
        val scoreHi = if (b != null) {
            if (b.bandSettled) "दोनों हाल में ${bandHi(b.lowBand)}, ${b.lowScore} से ${b.highScore}, इस पर निर्भर कि आपने पहले ट्रैकिंग की सहमति दी थी या नहीं।"
            else "ज़्यादा से ज़्यादा ${bandHi(b.bestBand)}, ${b.lowScore} से ${b.highScore}: सहमति के इतिहास के बारे में एक जवाब इसे तय करता है।"
        } else if ((f.band == ScoreBand.INCONCLUSIVE || f.score == null) && f.watchMinutes != null && f.inconclusiveReason == com.dpdpxray.core.rules.Inconclusive.Reason.LOW_VISIBILITY)
            "अनिर्णायक: ${f.watchMinutes} मिनट की निगरानी में ऐप ने लगभग कोई कनेक्शन नहीं बनाया।"
        else if (f.band == ScoreBand.INCONCLUSIVE || f.score == null) (f.inconclusiveReason?.hi ?: "अनिर्णायक: स्कोर के लिए बहुत कम जानकारी दर्ज हुई।")
            else "स्कोर 100 में से ${f.score}: ${bandHi(f.band)}।"
        val extraEn = buildString {
            if (f.findingCount > 0 || f.band != ScoreBand.INCONCLUSIVE) append(" ${findings(f.findingCount)}.")
            if (f.band == ScoreBand.READY) append(" It covers only what was captured; it is not a safety certificate.")
            if (f.coverageGaps) append(" Part of the check didn't finish.")
            if (f.sample) append(" This is sample data.")
        }
        val extraHi = buildString {
            if (f.findingCount > 0 || f.band != ScoreBand.INCONCLUSIVE) append(" ${f.findingCount} फ़ाइंडिंग।")
            if (f.band == ScoreBand.READY) append(" यह सिर्फ़ दर्ज सबूतों पर आधारित है; यह सुरक्षा प्रमाणपत्र नहीं है।")
            if (f.coverageGaps) append(" जाँच का कुछ हिस्सा पूरा नहीं हुआ।")
            if (f.sample) append(" यह नमूना डेटा है।")
        }
        return GuideStep(
            "results.${f.band}.${f.findingCount}.${f.coverageGaps}.${f.sample}", 4, GuideFlow.TOTAL,
            if (f.band == ScoreBand.INCONCLUSIVE || f.coverageGaps) GuideTone.CAUTION else GuideTone.DONE,
            Bi("Read your result", "अपना नतीजा पढ़ें"),
            Bi("Start with What this means, then pick something from What you can do next.",
                "पहले \"इसका मतलब\" पढ़ें, फिर \"आगे आप क्या कर सकते हैं\" में से कुछ चुनें।"),
            Bi("Step 4 of 4. Your result is ready. $scoreEn$extraEn", "चरण 4, कुल 4 में से। आपका नतीजा तैयार है। $scoreHi$extraHi"),
        )
    }
}
