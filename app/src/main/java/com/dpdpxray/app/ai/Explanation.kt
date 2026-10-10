package com.dpdpxray.app.ai

import com.dpdpxray.core.copilot.CitationValidator
import com.dpdpxray.core.dpdp.Passage
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.Finding

/**
 * Where a piece of result text comes from, shown next to it: seen on the device, read by the on-device AI, reasoned from
 * vendor documentation, or a rules-based fallback written without the AI.
 */
enum class ResultSource(val label: String, val meaning: String) {
    OBSERVED("Observed", "Seen on this phone: captured lookups or the screen's accessibility tree."),
    AI_INFERRED("AI-inferred", "Written or read by the on-device AI. It can be wrong; the evidence above is what counts."),
    INFERRED("Inferred", "Reasoned from vendor documentation, not seen directly."),
    FALLBACK("Fallback", "Rules-based text written without the AI, from this finding's own evidence."),
}

object ResultSources {
    private val SCREEN_CHECKS = setOf(CheckId.C3, CheckId.C4, CheckId.C5, CheckId.C6, CheckId.C7, CheckId.C8, CheckId.C9)

    /** An inferred consent-screen finding comes from the AI's reading of the screen when a model reviewed it. */
    fun of(finding: Finding, sessions: List<AuditSession>): ResultSource = when {
        finding.evidenceType == EvidenceType.OBSERVED -> ResultSource.OBSERVED
        finding.checkId in SCREEN_CHECKS && sessions.any { it.screenAudit?.modelId != null } -> ResultSource.AI_INFERRED
        else -> ResultSource.INFERRED
    }
}

/** A finding explanation with its source; [detail] says which model wrote it or why the fallback was used. */
data class Explanation(val text: String, val source: ResultSource, val detail: String)

object Explanations {
    private val bareRef = Regex("\\b(section|sec\\.|s\\.|rule|धारा|नियम)\\s*([0-9]+)", RegexOption.IGNORE_CASE)
    private val idNumber = Regex("^[a-z]+([0-9]+)")

    /** An AI explanation, kept only when it is non-empty and cites no law it was not given. */
    fun fromModel(text: String?, finding: Finding, passages: List<Passage>, hindi: Boolean, model: String): Explanation {
        val t = plain(text)
        if (t.isEmpty()) return fallback(finding, hindi, "the on-device AI returned no text")
        if (!groundedIn(t, passages)) return fallback(finding, hindi, "the AI's answer cited law it was not given, so it is not shown")
        return Explanation(t, ResultSource.AI_INFERRED, "Written on this phone by $model from this finding's evidence and the law shown above. It can be wrong.")
    }

    /** True when every bracketed id and every "Section N" / "Rule N" in [text] is one of the passages given to the model. */
    fun groundedIn(text: String, passages: List<Passage>): Boolean {
        val allowed = passages.map { it.id }.toSet()
        if (!CitationValidator.citedIds(text).all { it in allowed }) return false
        val numbers = allowed.mapNotNull { idNumber.find(it)?.groupValues?.get(1) }.toSet()
        return bareRef.findAll(text).all { it.groupValues[2] in numbers }
    }

    /** Deterministic text from the finding itself, so an explanation is always available even with no model loaded. */
    fun fallback(finding: Finding, hindi: Boolean, reason: String): Explanation {
        val evidence = finding.evidence.firstOrNull()
        val text = if (hindi) {
            "नियमों पर आधारित सार (AI के बिना): इस ऑडिट में ${finding.checkId.code} दर्ज हुआ — ${finding.title}।" +
                (evidence?.let { " पहला सबूत: $it।" } ?: "") +
                " पूरा विवरण और कानून ऊपर दिया गया है।"
        } else {
            "${finding.checkId.code}: ${finding.title}. ${firstSentences(finding.explanation, 2)}" +
                (evidence?.let { " First evidence: $it." } ?: "")
        }
        return Explanation(text, ResultSource.FALLBACK, "Shown because $reason. Built only from this finding's own text and evidence.")
    }

    /** Model text without markdown emphasis, headings or bullet markers, which the result screen would show literally. */
    fun plain(text: String?): String = text.orEmpty()
        .replace("**", "").replace("__", "")
        .lines().joinToString("\n") { it.trimStart().removePrefix("#").removePrefix("#").removePrefix("* ").removePrefix("- ").trimEnd() }
        .trim()

    private fun firstSentences(s: String, n: Int): String =
        s.split(Regex("(?<=[.!?])\\s+")).take(n).joinToString(" ")
}
