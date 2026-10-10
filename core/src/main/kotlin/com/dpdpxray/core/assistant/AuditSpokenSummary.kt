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
 */
object AuditSpokenSummary {
    private const val MAX_NAMES = 3

    fun summarize(e: AuditEvidence, language: CopilotLanguage): String {
        val t = if (language == CopilotLanguage.HI) Hindi else English
        val r = e.result
        val parts = mutableListOf<String>()
        if (e.sample) parts += t.sample
        val inconclusive = r.score == null || r.band == ScoreBand.INCONCLUSIVE
        if (inconclusive) {
            parts += t.inconclusive(e.label)
        } else {
            parts += t.score(e.label, r.score!!, r.band)
            parts += headline(r.findings, t)
            mostSerious(r.findings)?.let { parts += t.mostSerious(r.findings.size, it) }
        }
        if (AuditPlan.ACCEPT in e.plans && e.completedPlans?.contains(AuditPlan.ACCEPT) == false) parts += t.acceptMissing
        if (AuditPlan.REJECT in e.plans && e.completedPlans?.contains(AuditPlan.REJECT) == false) parts += t.rejectMissing
        if (!inconclusive && r.score == 100) parts += t.notCertificate
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
            findings.all { it.severity == Severity.INFO } && findings.any { it.checkId == CheckId.U1 } -> t.historyUnknown
            findings.all { it.severity == Severity.INFO } -> t.infoOnly
            else -> t.screenNeedsWork
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
        val historyUnknown: String
        val infoOnly: String
        val screenNeedsWork: String
        val acceptMissing: String
        val rejectMissing: String
        val notCertificate: String
        fun inconclusive(label: String): String
        fun score(label: String, score: Int, band: ScoreBand): String
        fun afterRefusal(names: String): String
        fun beforeChoice(count: Int, names: String): String
        fun mostSerious(total: Int, f: Finding): String
        fun andMore(n: Int): String
    }

    private object English : Texts {
        override val sample = "This is the sample audit, not a live result."
        override val trackerHosts = "tracker hosts"
        override val nothingRecorded = "No tracker lookups before consent and no consent-screen problems were recorded."
        override val historyUnknown = "Consent history is unknown, because the app kept its data from before."
        override val infoOnly = "No third-party tracker lookups before consent and no consent-screen problems were recorded; there are information notes."
        override val screenNeedsWork = "No tracker lookups before consent were recorded, but the consent screen needs work."
        override val acceptMissing = "Your Accept choice was not recorded, so this check is incomplete."
        override val rejectMissing = "The Reject choice was not recorded, so refusal was not tested."
        override val notCertificate = "A full score is not a safety certificate."
        override fun inconclusive(label: String) = "In this check of $label, too few lookups were captured to give a score."
        override fun score(label: String, score: Int, band: ScoreBand) = "In this check, $label scored $score out of 100: ${band.label}."
        override fun afterRefusal(names: String) = "Tracker hosts were looked up after consent was refused: $names."
        override fun beforeChoice(count: Int, names: String) =
            "$count tracker host${if (count == 1) " was" else "s were"} looked up before any consent choice: $names."
        override fun mostSerious(total: Int, f: Finding) =
            "$total finding${if (total == 1) "" else "s"}; the most serious is ${f.checkId.code}, ${f.title}."
        override fun andMore(n: Int) = " and $n more"
    }

    private object Hindi : Texts {
        override val sample = "यह नमूना ऑडिट है, लाइव परिणाम नहीं।"
        override val trackerHosts = "ट्रैकर होस्ट"
        override val nothingRecorded = "सहमति से पहले कोई ट्रैकर लुकअप और सहमति स्क्रीन की कोई समस्या दर्ज नहीं हुई।"
        override val historyUnknown = "सहमति का इतिहास अज्ञात है, क्योंकि ऐप ने अपना पुराना डेटा रखा।"
        override val infoOnly = "सहमति से पहले कोई तीसरे-पक्ष ट्रैकर लुकअप और सहमति स्क्रीन की कोई समस्या दर्ज नहीं हुई; कुछ सूचना नोट हैं।"
        override val screenNeedsWork = "सहमति से पहले कोई ट्रैकर लुकअप दर्ज नहीं हुआ, पर सहमति स्क्रीन में सुधार चाहिए।"
        override val acceptMissing = "आपका स्वीकार करने का चुनाव दर्ज नहीं हुआ, इसलिए यह जाँच अधूरी है।"
        override val rejectMissing = "मना करने का चुनाव दर्ज नहीं हुआ, इसलिए अस्वीकृति की जाँच नहीं हुई।"
        override val notCertificate = "पूरा स्कोर सुरक्षा का प्रमाणपत्र नहीं है।"
        override fun inconclusive(label: String) = "इस जाँच में $label के बहुत कम लुकअप दर्ज हुए, इसलिए कोई स्कोर नहीं है।"
        override fun score(label: String, score: Int, band: ScoreBand) = "इस जाँच में $label का स्कोर 100 में से $score है: ${band(band)}।"
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
