package com.dpdpxray.core.apk

import com.dpdpxray.core.ml.PermissionRiskModel
import com.dpdpxray.core.ml.PermissionRiskScore
import java.util.Locale
import kotlin.math.round

/**
 * The permission-profile model ([PermissionRiskModel]) shown as one extra APK Guard card. The card is always INFERRED,
 * "Worth knowing" only when the model score is elevated and "Info" otherwise. It is never "Review carefully", and
 * adding it changes no rule signal and not the rules' summary: the rules decide, the model only adds context.
 */
object ApkModelSignal {
    const val ID = "model_permission_profile"

    /** How a weight is printed on the card, e.g. "+1.03". */
    fun weight(w: Double): String = String.format(Locale.ROOT, "%+.2f", w)

    fun of(score: PermissionRiskScore, sources: List<String> = emptyList()): ApkSignal {
        val pct = round(score.probability * 100).toInt()
        val verdictLine = "मॉडल स्कोर अनुमान है, मैलवेयर का फ़ैसला नहीं।"
        val hindi = if (score.elevated) {
            "इस ऐप की माँगी गई अनुमतियों का पैटर्न रिसर्च डेटा के मैलवेयर नमूनों से मिलता-जुलता है (मॉडल स्कोर $pct%, अनुमान)। अनुमतियाँ ऐप का व्यवहार नहीं बतातीं। $verdictLine"
        } else {
            "इस ऐप की माँगी गई अनुमतियों का पैटर्न हमारे रिसर्च डेटा के मैलवेयर नमूनों से नहीं मिलता (मॉडल स्कोर $pct%, अनुमान)। कम स्कोर से ऐप को क्लीन चिट नहीं मिलती। $verdictLine"
        }
        val raising = score.raising.take(3).map { "${it.permission}  ${weight(it.weight)}" }
        val proof = raising.ifEmpty { listOf("No declared permission raised the score") } +
            "Based on ${score.recognised} of ${score.vocabularySize} recognised permissions"
        val trainedOn = sources.firstOrNull()?.let { "trained on $it" } ?: "trained on public research data"
        return ApkSignal(
            id = ID,
            severity = if (score.elevated) ApkSeverity.WORTH_KNOWING else ApkSeverity.INFO,
            evidence = ApkEvidence.INFERRED,
            title = Bilingual("Permission profile model", "अनुमति प्रोफ़ाइल मॉडल"),
            detail = Bilingual(score.summary(), hindi),
            advice = Bilingual(
                "Decide from the permission and service cards in this check, and allow only what the app clearly needs. The model sees only the permissions the app asks for, not what it does.",
                "इस जाँच के अनुमति और सर्विस कार्ड देखकर फ़ैसला करें, और सिर्फ़ वही अनुमति दें जिसकी ऐप को साफ़ ज़रूरत हो। मॉडल सिर्फ़ माँगी गई अनुमतियाँ देखता है, ऐप क्या करता है यह नहीं।",
            ),
            proof = proof,
            basis = "On-device logistic regression v${score.modelVersion} over declared permissions, $trainedOn; apps released 2010-2019. " +
                "It measures resemblance to those research samples, not behaviour.",
        )
    }

    /** [report] with the model card added in severity order. Every rule signal, their order and the summary stay as they were. */
    fun attach(report: ApkGuardReport, model: PermissionRiskModel): ApkGuardReport {
        val card = of(model.score(report.facts.permissions), model.sources)
        // Stable sort: the rule signals are already in this order, so only the card moves into place.
        val signals = (report.signals + card).sortedWith(compareBy({ it.severity.ordinal }, { it.evidence.ordinal }))
        return report.copy(signals = signals)
    }
}
