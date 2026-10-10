package com.dpdpxray.core.assistant

import com.dpdpxray.core.copilot.AuditEvidence
import com.dpdpxray.core.copilot.CopilotLanguage
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.Finding
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.Severity

/**
 * A short spoken summary of one saved audit: score and band, the headline, the most serious finding and any missing
 * consent choice. Every sentence comes from the saved result; nothing is inferred. A lookup is described as a lookup
 * of a tracker host, never as data sent, and no wording says an app is safe or harmful; "in this check" marks every
 * claim as limited to what was captured.
 *
 * When consent history is unknown (U1: the app kept its data and showed no consent screen) the summary never gives a
 * readiness band. With tracker lookups in those plans there is no score sentence at all, for results saved before the
 * rules made such a check Inconclusive (band READY) as well as after (band INCONCLUSIVE, no score).
 */
object AuditSpokenSummary {
    private const val MAX_NAMES = 3

    private val SCREEN_CHECKS = setOf(CheckId.C3, CheckId.C4, CheckId.C5, CheckId.C6, CheckId.C7, CheckId.C8)

    fun summarize(e: AuditEvidence, language: CopilotLanguage): String {
        val t = if (language == CopilotLanguage.HI) Hindi else English
        val r = e.result
        val score = r.score
        val historyUnknown = r.findings.firstOrNull { it.checkId == CheckId.U1 }
        val parts = mutableListOf<String>()
        var hint = ""
        if (e.sample) parts += t.sample
        when {
            r.findings.any { it.checkId == CheckId.V0 } -> parts += t.lowVisibility(e.label)
            historyUnknown != null && historyUnknown.entities.isNotEmpty() -> {
                parts += t.noVerdict(e.label, inconclusive = r.band == ScoreBand.INCONCLUSIVE)
                parts += t.lookupsWithUnknownHistory(names(historyUnknown.entities, t))
                if (r.findings.any { it.severity != Severity.INFO }) mostSerious(r.findings)?.let { parts += t.mostSerious(r.findings.size, it) }
                hint = t.fullCheckHint
            }
            score == null || r.band == ScoreBand.INCONCLUSIVE -> parts += t.inconclusive(e.label)
            else -> {
                if (historyUnknown == null) {
                    parts += t.score(e.label, score, r.band)
                } else {
                    // A score may stand, but no band: whether consent was given earlier is unknown.
                    parts += t.scoreOnly(e.label, score)
                    parts += t.historyCaveat
                }
                parts += headline(r.findings, t)
                mostSerious(r.findings)?.let { parts += t.mostSerious(r.findings.size, it) }
                if (score == 100) hint = t.notCertificate
            }
        }
        if (AuditPlan.ACCEPT in e.plans && e.completedPlans?.contains(AuditPlan.ACCEPT) == false) parts += t.acceptMissing
        if (AuditPlan.REJECT in e.plans && e.completedPlans?.contains(AuditPlan.REJECT) == false) parts += t.rejectMissing
        parts += hint
        return parts.filter { it.isNotBlank() }.joinToString(" ")
    }

    /** Highest severity first; among equals, the order the rules engine reported. */
    fun mostSerious(findings: List<Finding>): Finding? = findings.withIndex()
        .sortedWith(compareByDescending<IndexedValue<Finding>> { it.value.severity.weight }.thenBy { it.index })
        .firstOrNull()?.value

    private fun headline(findings: List<Finding>, t: Texts): String {
        val c1 = findings.firstOrNull { it.checkId == CheckId.C1 }
        val c2 = findings.firstOrNull { it.checkId == CheckId.C2 }
        return when {
            c2 != null -> t.afterRefusal(names(c2.entities, t))
            c1 != null -> t.beforeChoice(c1.entities.size, names(c1.entities, t))
            findings.isEmpty() -> t.nothingRecorded
            findings.any { it.checkId in SCREEN_CHECKS } -> t.screenNeedsWork
            findings.all { it.severity == Severity.INFO } -> t.infoOnly
            // Other findings (for example C10 or NC) are named by the "most serious" sentence that follows.
            else -> t.noLookupsBeforeConsent
        }
    }

    private fun names(entities: List<String>, t: Texts): String {
        if (entities.isEmpty()) return t.trackerHosts
        val shown = entities.take(MAX_NAMES)
        val rest = entities.size - shown.size
        return shown.joinToString(", ") + if (rest > 0) t.andMore(rest) else ""
    }

    private interface Texts {
        val sample: String
        val trackerHosts: String
        val nothingRecorded: String
        val historyCaveat: String
        val infoOnly: String
        val screenNeedsWork: String
        val noLookupsBeforeConsent: String
        val acceptMissing: String
        val rejectMissing: String
        val notCertificate: String
        val fullCheckHint: String
        fun lowVisibility(label: String): String
        fun inconclusive(label: String): String
        fun noVerdict(label: String, inconclusive: Boolean): String
        fun lookupsWithUnknownHistory(names: String): String
        fun score(label: String, score: Int, band: ScoreBand): String
        fun scoreOnly(label: String, score: Int): String
        fun afterRefusal(names: String): String
        fun beforeChoice(count: Int, names: String): String
        fun mostSerious(total: Int, f: Finding): String
        fun andMore(n: Int): String
    }

    private object English : Texts {
        override val sample = "This is the sample audit, not a live result."
        override val trackerHosts = "tracker hosts"
        override val nothingRecorded = "No tracker lookups before consent and no consent-screen problems were recorded."
        override val historyCaveat = "Consent history is unknown, because the app kept its data from before, so this score is not a readiness verdict."
        override val infoOnly = "No third-party tracker lookups before consent and no consent-screen problems were recorded; there are information notes."
        override val screenNeedsWork = "No tracker lookups before consent were recorded, but the consent screen needs work."
        override val noLookupsBeforeConsent = "No tracker lookups before consent were recorded."
        override val acceptMissing = "Your Accept choice was not recorded, so this check is incomplete."
        override val rejectMissing = "The Reject choice was not recorded, so refusal was not tested."
        override val notCertificate = "A full score is not a safety certificate."
        override val fullCheckHint = "To test consent from a first launch, run a Full check of a test install."
        override fun lowVisibility(label: String) = "In this check of $label, the result is Inconclusive: too few lookups were captured to give a score."
        override fun inconclusive(label: String) = "In this check of $label, the result is Inconclusive, so there is no score."
        override fun noVerdict(label: String, inconclusive: Boolean) =
            if (inconclusive) "In this check of $label, the result is Inconclusive: the app kept its data from before, so consent history is unknown and there is no readiness verdict."
            else "In this check of $label, there is no readiness verdict: the app kept its data from before, so consent history is unknown."
        override fun lookupsWithUnknownHistory(names: String) =
            "It looked up tracker hosts of $names; with consent history unknown, these are not counted as before consent."
        override fun score(label: String, score: Int, band: ScoreBand) = "In this check, $label scored $score out of 100: ${band(band)}."
        override fun scoreOnly(label: String, score: Int) = "In this check, $label scored $score out of 100."
        override fun afterRefusal(names: String) = "Tracker hosts were looked up after consent was refused: $names."
        override fun beforeChoice(count: Int, names: String) =
            "$count tracker host${if (count == 1) " was" else "s were"} looked up before any consent choice: $names."
        override fun mostSerious(total: Int, f: Finding) =
            "$total finding${if (total == 1) "" else "s"}; the most serious is ${f.checkId.code}, ${f.title}."
        override fun andMore(n: Int) = " and $n more"

        /** Spoken band names, kept here so a label change elsewhere cannot change what the assistant says. */
        private fun band(b: ScoreBand) = when (b) {
            ScoreBand.READY -> "Ready"
            ScoreBand.NEEDS_WORK -> "Needs work"
            ScoreBand.AT_RISK -> "At risk"
            ScoreBand.INCONCLUSIVE -> "Inconclusive"
        }
    }

    private object Hindi : Texts {
        override val sample = "यह नमूना ऑडिट है, लाइव परिणाम नहीं।"
        override val trackerHosts = "ट्रैकर होस्ट"
        override val nothingRecorded = "सहमति से पहले कोई ट्रैकर लुकअप और सहमति स्क्रीन की कोई समस्या दर्ज नहीं हुई।"
        override val historyCaveat = "सहमति का इतिहास अज्ञात है, क्योंकि ऐप ने अपना पुराना डेटा रखा; इसलिए यह स्कोर कोई अंतिम फ़ैसला नहीं है।"
        override val infoOnly = "सहमति से पहले कोई तीसरे-पक्ष ट्रैकर लुकअप और सहमति स्क्रीन की कोई समस्या दर्ज नहीं हुई; कुछ सूचना नोट हैं।"
        override val screenNeedsWork = "सहमति से पहले कोई ट्रैकर लुकअप दर्ज नहीं हुआ, पर सहमति स्क्रीन में सुधार चाहिए।"
        override val noLookupsBeforeConsent = "सहमति से पहले कोई ट्रैकर लुकअप दर्ज नहीं हुआ।"
        override val acceptMissing = "आपका स्वीकार करने का चुनाव दर्ज नहीं हुआ, इसलिए यह जाँच अधूरी है।"
        override val rejectMissing = "मना करने का चुनाव दर्ज नहीं हुआ, इसलिए अस्वीकृति की जाँच नहीं हुई।"
        override val notCertificate = "पूरा स्कोर सुरक्षा का प्रमाणपत्र नहीं है।"
        override val fullCheckHint = "पहली बार खोलने पर सहमति जाँचने के लिए, किसी टेस्ट इंस्टॉल की पूरी जाँच (Full check) चलाइए।"
        override fun lowVisibility(label: String) = "इस जाँच में $label का नतीजा अनिर्णायक है: बहुत कम लुकअप दर्ज हुए, इसलिए कोई स्कोर नहीं है।"
        override fun inconclusive(label: String) = "इस जाँच में $label का नतीजा अनिर्णायक है, इसलिए कोई स्कोर नहीं है।"
        override fun noVerdict(label: String, inconclusive: Boolean) =
            if (inconclusive) "इस जाँच में $label का नतीजा अनिर्णायक है: ऐप ने अपना पुराना डेटा रखा, इसलिए सहमति का इतिहास अज्ञात है और कोई अंतिम फ़ैसला नहीं है।"
            else "इस जाँच में $label पर कोई अंतिम फ़ैसला नहीं है: ऐप ने अपना पुराना डेटा रखा, इसलिए सहमति का इतिहास अज्ञात है।"
        override fun lookupsWithUnknownHistory(names: String) =
            "इसने इनके ट्रैकर होस्ट खोजे: $names; सहमति का इतिहास अज्ञात होने से इन्हें सहमति से पहले का नहीं गिना गया।"
        override fun score(label: String, score: Int, band: ScoreBand) = "इस जाँच में $label का स्कोर 100 में से $score है: ${band(band)}।"
        override fun scoreOnly(label: String, score: Int) = "इस जाँच में $label का स्कोर 100 में से $score है।"
        override fun afterRefusal(names: String) = "सहमति से मना करने के बाद ट्रैकर होस्ट खोजे गए: $names।"
        override fun beforeChoice(count: Int, names: String) = "किसी भी सहमति से पहले $count ट्रैकर होस्ट खोजे गए: $names।"
        override fun mostSerious(total: Int, f: Finding) = "$total निष्कर्ष; सबसे गंभीर ${f.checkId.code} है।"
        override fun andMore(n: Int) = " और $n अन्य"

        private fun band(b: ScoreBand) = when (b) {
            ScoreBand.READY -> "तैयार"
            ScoreBand.NEEDS_WORK -> "सुधार चाहिए"
            ScoreBand.AT_RISK -> "जोखिम में"
            ScoreBand.INCONCLUSIVE -> "अनिर्णायक"
        }
    }
}
