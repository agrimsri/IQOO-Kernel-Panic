package com.dpdpxray.core.copilot

import com.dpdpxray.core.dpdp.DpdpCorpus
import com.dpdpxray.core.dpdp.Passage
import com.dpdpxray.core.dpdp.PassageRetriever
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.Finding
import com.dpdpxray.core.model.ScoreBand

/** The one reply for anything the audit's evidence cannot answer. Kept in English in every mode, word for word. */
const val NO_EVIDENCE = "I have no evidence about that in this audit"

/** What the copilot may read: one saved audit's findings, plus whether it is a sample. */
data class AuditEvidence(
    val label: String,
    val sample: Boolean,
    val result: AuditResult,
    /** Plans that actually ran; empty when unknown. */
    val plans: Set<AuditPlan> = emptySet(),
    /** Null for legacy callers; otherwise plans whose intended consent choice was actually recorded. */
    val completedPlans: Set<AuditPlan>? = null,
)

data class GroundedAnswer(
    val text: String,
    val citations: List<Passage>,
    val refused: Boolean,
    val intent: CopilotIntent?,
    val language: CopilotLanguage,
) {
    /** [text] with bracketed ids read out as words, for speech output. */
    val spokenText: String get() = speakable(text, language)
}

/**
 * Answers a question using only the audit's findings and the top three passages from [retriever]. The wording is
 * deterministic: no model writes the answer, so nothing outside the evidence can appear in it. It never states that an
 * app is safe; a missing finding is reported as "not recorded in the captured evidence".
 */
class GroundedAnswerer(private val corpus: DpdpCorpus, private val retriever: PassageRetriever) {

    fun answer(question: String, evidence: AuditEvidence, language: CopilotLanguage): GroundedAnswer {
        val intent = CopilotQuestions.match(question) ?: return refusal(language, null)
        return answer(intent, question, evidence, language)
    }

    fun answer(intent: CopilotIntent, question: String, evidence: AuditEvidence, language: CopilotLanguage): GroundedAnswer {
        val retrieved = retriever.search("$question ${QUERY[intent]}", TOP_K).take(TOP_K).map { it.passage }
        val citations = retrieved.filter { it.id in RELEVANT.getValue(intent) && corpus.byId(it.id) != null }
        val t = if (language == CopilotLanguage.HI) Hindi else English
        val body = buildString {
            if (evidence.sample) append(t.samplePrefix)
            val reason = com.dpdpxray.core.rules.Inconclusive.reason(evidence.result.findings)
            if (evidence.result.band == ScoreBand.INCONCLUSIVE || reason != null) append(t.inconclusive(reason))
            append(compose(intent, evidence, t))
            evidence.plans.filter { it != AuditPlan.SILENT && evidence.completedPlans?.contains(it) == false }
                .forEach { plan ->
                    // AFTER_REJECT already explains a missing refusal unless it reports an actual C2 finding.
                    val explained = plan == AuditPlan.REJECT && intent == CopilotIntent.AFTER_REJECT &&
                        evidence.result.findings.none { it.checkId == CheckId.C2 }
                    if (!explained) append(" " + t.planNotCompleted(plan))
                }
            if (citations.isNotEmpty()) append(t.lawLine(citations))
        }
        if (!CitationValidator.isGrounded(body, citations.map { it.id }.toSet())) return refusal(language, intent)
        return GroundedAnswer(body, citations, refused = false, intent = intent, language = language)
    }

    private fun refusal(language: CopilotLanguage, intent: CopilotIntent?) =
        GroundedAnswer(NO_EVIDENCE, emptyList(), refused = true, intent = intent, language = language)

    private fun compose(intent: CopilotIntent, e: AuditEvidence, t: Texts): String {
        val f = e.result.findings.associateBy { it.checkId }
        fun planMissing(plan: AuditPlan) = e.plans.isNotEmpty() && plan !in e.plans
        return when (intent) {
            CopilotIntent.CHILDREN -> f[CheckId.C9]?.let { t.childrenFound(it) }
                ?: t.childrenAbsent(e.label, entities(e.result.findings))
            CopilotIntent.AFTER_REJECT -> when {
                f[CheckId.C2] != null -> t.afterReject(entities(listOf(f.getValue(CheckId.C2))))
                planMissing(AuditPlan.REJECT) -> t.planNotRun(AuditPlan.REJECT)
                e.completedPlans?.contains(AuditPlan.REJECT) == false -> t.planNotCompleted(AuditPlan.REJECT)
                else -> t.notRecorded(CheckId.C2)
            }
            CopilotIntent.BEFORE_CONSENT -> {
                val c1 = f[CheckId.C1]
                val c10 = f[CheckId.C10]
                if (c1 == null && c10 == null) t.notRecorded(CheckId.C1)
                else listOfNotNull(c1?.let { t.beforeConsent(entities(listOf(it))) }, c10?.let { t.deviceIds(it.evidenceType) }).joinToString(" ")
            }
            CopilotIntent.CONSENT_SCREEN -> SCREEN_CHECKS.mapNotNull { f[it] }.let { found ->
                if (found.isEmpty()) t.notRecordedMany(SCREEN_CHECKS) else t.screenProblems(found)
            }
            CopilotIntent.WITHDRAWAL -> f[CheckId.C7]?.let { t.found(it) } ?: t.notRecorded(CheckId.C7)
            CopilotIntent.LANGUAGE -> f[CheckId.C8]?.let { t.found(it) } ?: t.notRecorded(CheckId.C8)
            CopilotIntent.SCORE -> t.score(e.result)
            CopilotIntent.TRACKERS -> entities(e.result.findings).let { if (it.isEmpty()) t.noTrackers else t.trackers(it) }
        }
    }

    private fun entities(findings: List<Finding>) = findings.flatMap { it.entities }.distinct().take(MAX_ENTITIES)

    private interface Texts {
        val samplePrefix: String
        fun inconclusive(reason: com.dpdpxray.core.rules.Inconclusive.Reason?): String
        val noTrackers: String
        fun childrenFound(c9: Finding): String
        fun childrenAbsent(label: String, entities: List<String>): String
        fun afterReject(entities: List<String>): String
        fun beforeConsent(entities: List<String>): String
        fun deviceIds(type: EvidenceType): String
        fun screenProblems(found: List<Finding>): String
        fun found(f: Finding): String
        fun notRecorded(id: CheckId): String
        fun notRecordedMany(ids: List<CheckId>): String
        fun planNotRun(plan: AuditPlan): String
        fun planNotCompleted(plan: AuditPlan): String
        fun score(r: AuditResult): String
        fun trackers(entities: List<String>): String
        fun lawLine(passages: List<Passage>): String
    }

    private object English : Texts {
        override val samplePrefix = "Sample audit, not a live result. "
        override fun inconclusive(reason: com.dpdpxray.core.rules.Inconclusive.Reason?) = when (reason) {
            com.dpdpxray.core.rules.Inconclusive.Reason.CONSENT_HISTORY_UNKNOWN ->
                "The app kept its data, so consent history is unknown and this audit gives no readiness verdict. "
            com.dpdpxray.core.rules.Inconclusive.Reason.LOW_VISIBILITY -> "Network visibility was low, so this audit is inconclusive. "
            null -> "This audit is inconclusive. "
        }
        override val noTrackers = "None of this audit's findings name a known tracker."
        override fun childrenFound(c9: Finding) = "This audit recorded ${c9.checkId.code}, “${c9.title}”: ${c9.explanation}"
        override fun childrenAbsent(label: String, entities: List<String>) =
            "This audit recorded no sign that $label is aimed at children, so check C9 did not apply. " +
                "TraceVeil cannot say it is suitable for children; that needs a review beyond this audit." +
                (if (entities.isEmpty()) "" else " It did observe lookups to these trackers: ${entities.joinToString()}.")
        override fun afterReject(entities: List<String>) =
            "After consent was rejected, this audit observed lookups to ${entities.ifEmpty { listOf("tracker hosts") }.joinToString()}."
        override fun beforeConsent(entities: List<String>) =
            "Before any consent choice, this audit observed lookups to ${entities.ifEmpty { listOf("tracker hosts") }.joinToString()}."
        override fun deviceIds(type: EvidenceType) =
            "It also flagged that device identifiers are likely shared before consent (${type.name.lowercase()})."
        override fun screenProblems(found: List<Finding>) =
            "On the consent screen this audit recorded: ${found.joinToString("; ") { "${it.checkId.code} “${it.title}”" }}."
        override fun found(f: Finding) = "This audit recorded ${f.checkId.code}, “${f.title}”."
        override fun notRecorded(id: CheckId) = "This audit did not record ${id.code} (“${id.title}”) in its captured evidence."
        override fun notRecordedMany(ids: List<CheckId>) =
            "This audit did not record ${ids.joinToString(", ") { it.code }} in its captured evidence."
        override fun planNotRun(plan: AuditPlan) = "This audit did not run ${plan.label}, so it has no evidence about that."
        override fun planNotCompleted(plan: AuditPlan) = when (plan) {
            AuditPlan.ACCEPT -> "The intended Accept choice was not recorded; this planned consent test is incomplete. The score covers only the evidence recorded."
            AuditPlan.REJECT -> "The intended ${plan.label} choice was not recorded; C2 not tested. The score is not a completed refusal assessment."
            AuditPlan.SILENT -> ""
        }
        override fun score(r: AuditResult) = if (r.score == null || com.dpdpxray.core.rules.Inconclusive.reason(r.findings) != null) "This audit is inconclusive and has no score."
        else "It scored ${r.score} out of 100 (${r.band.label}) with ${r.findings.size} finding${if (r.findings.size == 1) "" else "s"}" +
            (if (r.findings.isEmpty()) "." else ": ${r.findings.joinToString(", ") { it.checkId.code }}.") +
            (robustness(r)?.let { " " + it.line().replace(" · ", "; ") + "." } ?: "")
        override fun trackers(entities: List<String>) = "This audit's findings name these trackers: ${entities.joinToString()}."
        override fun lawLine(passages: List<Passage>) = " Relevant law: ${passages.joinToString("; ") { "[${it.id}] ${it.title}" }}."
    }

    private object Hindi : Texts {
        override val samplePrefix = "नमूना ऑडिट, लाइव परिणाम नहीं। "
        override fun inconclusive(reason: com.dpdpxray.core.rules.Inconclusive.Reason?) = when (reason) {
            com.dpdpxray.core.rules.Inconclusive.Reason.CONSENT_HISTORY_UNKNOWN ->
                "ऐप ने अपना डेटा रखा, इसलिए सहमति का इतिहास पता नहीं है और यह ऑडिट तैयारी पर कोई निष्कर्ष नहीं देता। "
            com.dpdpxray.core.rules.Inconclusive.Reason.LOW_VISIBILITY -> "नेटवर्क की जानकारी कम थी, इसलिए यह ऑडिट अनिर्णायक है। "
            null -> "यह ऑडिट अनिर्णायक है। "
        }
        override val noTrackers = "इस ऑडिट के किसी निष्कर्ष में कोई ज्ञात ट्रैकर नहीं है।"
        override fun childrenFound(c9: Finding) =
            "इस ऑडिट में ${c9.checkId.code} दर्ज हुआ: बच्चों के ऐप में ट्रैकिंग। ट्रैकर: ${c9.entities.joinToString()}।"
        override fun childrenAbsent(label: String, entities: List<String>) =
            "इस ऑडिट में ऐसा कोई संकेत नहीं मिला कि $label बच्चों के लिए बना है, इसलिए C9 जाँच लागू नहीं हुई। " +
                "TraceVeil यह नहीं कह सकता कि यह बच्चों के लिए उपयुक्त है; इसके लिए इस ऑडिट से आगे की जाँच चाहिए।" +
                (if (entities.isEmpty()) "" else " ऑडिट ने इन ट्रैकरों से संपर्क देखा: ${entities.joinToString()}।")
        override fun afterReject(entities: List<String>) =
            "सहमति से मना करने के बाद, इस ऑडिट ने इनसे संपर्क देखा: ${entities.ifEmpty { listOf("ट्रैकर होस्ट") }.joinToString()}।"
        override fun beforeConsent(entities: List<String>) =
            "सहमति चुनने से पहले, इस ऑडिट ने इनसे संपर्क देखा: ${entities.ifEmpty { listOf("ट्रैकर होस्ट") }.joinToString()}।"
        override fun deviceIds(type: EvidenceType) =
            "इसने यह भी दर्ज किया कि सहमति से पहले डिवाइस पहचानकर्ता साझा होने की संभावना है (${if (type == EvidenceType.OBSERVED) "देखा गया" else "अनुमानित"})।"
        override fun screenProblems(found: List<Finding>) =
            "सहमति स्क्रीन पर इस ऑडिट ने ये दर्ज किए: ${found.joinToString(", ") { it.checkId.code }}।"
        override fun found(f: Finding) = "इस ऑडिट में ${f.checkId.code} दर्ज हुआ।"
        override fun notRecorded(id: CheckId) = "इस ऑडिट के दर्ज साक्ष्य में ${id.code} नहीं मिला।"
        override fun notRecordedMany(ids: List<CheckId>) = "इस ऑडिट के दर्ज साक्ष्य में ${ids.joinToString(", ") { it.code }} नहीं मिले।"
        override fun planNotRun(plan: AuditPlan) = "इस ऑडिट में ${plan.label} नहीं चला, इसलिए इसका कोई साक्ष्य नहीं है।"
        override fun planNotCompleted(plan: AuditPlan) = when (plan) {
            AuditPlan.ACCEPT -> "इस ऑडिट में स्वीकार करने का चुनाव दर्ज नहीं हुआ; नियोजित सहमति परीक्षण अधूरा है। स्कोर केवल दर्ज साक्ष्य पर आधारित है।"
            AuditPlan.REJECT -> "इस ऑडिट में मना करने का चुनाव दर्ज नहीं हुआ; C2 की जाँच नहीं हुई। स्कोर को पूरी हुई अस्वीकृति-जाँच न मानें।"
            AuditPlan.SILENT -> ""
        }
        override fun score(r: AuditResult) = if (r.score == null || com.dpdpxray.core.rules.Inconclusive.reason(r.findings) != null) "यह ऑडिट अनिर्णायक है और इसका कोई स्कोर नहीं है।"
        else "स्कोर ${r.score}/100 (${r.band.label}), ${r.findings.size} निष्कर्ष" +
            (if (r.findings.isEmpty()) "।" else ": ${r.findings.joinToString(", ") { it.checkId.code }}।") +
            (robustness(r)?.let { " " + it.line(hindi = true).replace(" · ", "; ") + "।" } ?: "")
        override fun trackers(entities: List<String>) = "इस ऑडिट के निष्कर्षों में ये ट्रैकर हैं: ${entities.joinToString()}।"
        override fun lawLine(passages: List<Passage>) = " संबंधित कानून: ${passages.joinToString("; ") { "[${it.id}] ${it.title}" }}।"
    }

    companion object {
        const val TOP_K = 3

        /** The robustness range for a score answer: only when inferred findings can move it and the saved score is today's. */
        private fun robustness(r: AuditResult) = com.dpdpxray.core.rules.ScoreCalculator.robustness(r.findings)
            ?.takeIf { it.movable && it.score == r.score && it.band == r.band }
        private const val MAX_ENTITIES = 6
        private val SCREEN_CHECKS = listOf(CheckId.C3, CheckId.C4, CheckId.C5, CheckId.C6)

        /** English expansion so Hindi or short questions still retrieve the right passages from the English corpus. */
        private val QUERY = mapOf(
            CopilotIntent.CHILDREN to "child children parental consent tracking behavioural monitoring targeted advertising well-being",
            CopilotIntent.AFTER_REJECT to "withdraw consent cease processing withdrawal",
            CopilotIntent.BEFORE_CONSENT to "consent lawful purpose processing free specific informed notice",
            CopilotIntent.WITHDRAWAL to "withdraw consent ease withdrawal rights complaint",
            CopilotIntent.LANGUAGE to "language English Eighth Schedule notice consent request",
            CopilotIntent.CONSENT_SCREEN to "consent free specific informed unambiguous affirmative action notice itemised purpose",
            CopilotIntent.SCORE to "",
            CopilotIntent.TRACKERS to "consent lawful purpose processing",
        )

        /** Passages that may be cited for each question; anything else retrieved is dropped. */
        private val RELEVANT = mapOf(
            CopilotIntent.CHILDREN to setOf("s9(1)", "s9(2)", "s9(3)"),
            CopilotIntent.AFTER_REJECT to setOf("s6(4)", "s6(6)", "s4(1)"),
            CopilotIntent.BEFORE_CONSENT to setOf("s4(1)", "s6(1)", "s5(1)"),
            CopilotIntent.WITHDRAWAL to setOf("s6(4)", "r3(c)"),
            CopilotIntent.LANGUAGE to setOf("s5(3)", "s6(3)"),
            CopilotIntent.CONSENT_SCREEN to setOf("s6(1)", "s5(1)", "r3(b)"),
            CopilotIntent.SCORE to emptySet(),
            CopilotIntent.TRACKERS to setOf("s4(1)", "s6(1)"),
        )
    }
}

private val CITATION_ID = Regex("\\[(s|r)([0-9]+)(\\([0-9a-z]+\\))?]")

/** Reads `[s9(3)]` as "section 9(3)" (or "धारा 9(3)") so a speech engine does not spell out brackets. */
internal fun speakable(text: String, language: CopilotLanguage): String = CITATION_ID.replace(text) { m ->
    val (kind, num, sub) = m.destructured
    val word = when {
        kind == "s" && language == CopilotLanguage.HI -> "धारा"
        kind == "s" -> "section"
        language == CopilotLanguage.HI -> "नियम"
        else -> "rule"
    }
    "$word $num$sub"
}
