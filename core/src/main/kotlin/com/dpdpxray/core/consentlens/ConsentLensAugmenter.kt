package com.dpdpxray.core.consentlens

import com.dpdpxray.core.consent.ConsentLexicon
import com.dpdpxray.core.model.AuditSource
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.ConsentLensEvidence
import com.dpdpxray.core.model.ConsentLensLabel
import com.dpdpxray.core.model.RejectOption
import com.dpdpxray.core.model.ScreenAudit
import com.dpdpxray.core.model.UiNode
import java.util.Locale

/**
 * Adds ConsentLens-1's INFERRED reading of the strings the lexicon does not recognise (for example a German
 * "Alle ablehnen" button) to an existing [ScreenAudit] from the tree and, if present, the screen-review model.
 *
 * Rules (the rules engine still decides every finding):
 *  - only strings for which [ConsentLexicon.classifyButton] returns null are classified;
 *  - only predictions at or above [threshold] that are not OTHER count;
 *  - an OBSERVED field (in observedFields) is never changed; a disagreement is added to [ScreenAudit.conflicts];
 *  - a field already set by the screen-review model is not changed; a disagreement is listed in the evidence;
 *  - only unknown fields are filled, and each filled field is listed in [ConsentLensEvidence.inferredFields];
 *  - absence of a string is never turned into "missing" (no withdrawal/purpose/privacy-link `false` from the model).
 */
object ConsentLensAugmenter {
    const val DEFAULT_THRESHOLD = 0.8
    const val MAX_STRINGS = 48
    private const val PURPOSE_LABEL_MAX_CHARS = 48

    fun augment(
        audit: ScreenAudit,
        root: UiNode,
        classifier: ConsentRoleClassifier,
        threshold: Double = DEFAULT_THRESHOLD,
    ): ScreenAudit {
        val nodes = root.flatten().filter { it.label.isNotBlank() }
        val candidates = nodes.filter { ConsentLexicon.classifyButton(it.label) == null }
            .distinctBy { it.label.trim() }
            .take(MAX_STRINGS)
        if (candidates.isEmpty()) return audit
        val predictions = runCatching { classifier.classify(candidates.map { it.label.trim() }) }.getOrNull()
        if (predictions == null || predictions.size != candidates.size) return audit

        val labelled = candidates.zip(predictions)
        val confident = labelled.filter { (_, p) -> p.role != ConsentRole.OTHER && p.confidence >= threshold }
        val buttons = confident.filter { (n, p) -> n.isClickable && p.role.isChoice }
        val notices = confident.filter { (_, p) -> p.role.isNotice }
        fun top(list: List<Pair<UiNode, RolePrediction>>, role: ConsentRole) =
            list.filter { it.second.role == role }.maxByOrNull { it.second.confidence }?.second

        val inferred = mutableListOf<String>()
        val support = linkedMapOf<String, List<String>>()
        val supportConfidence = mutableListOf<Double>()
        val conflicts = audit.conflicts.toMutableList()
        val disagreements = mutableListOf<String>()
        fun infer(field: String, why: List<RolePrediction>) {
            inferred += field
            support[field] = why.map(::describe)
            supportConfidence += why.minOf { it.confidence }
        }

        // 1. Is this a consent screen at all? Only asked when neither the lexicon nor the screen review said so.
        var isConsent = audit.isConsentScreen
        if (!isConsent) {
            val choice = top(buttons, ConsentRole.ACCEPT) ?: top(buttons, ConsentRole.REJECT)
            val notice = notices.maxByOrNull { it.second.confidence }?.second
            if (choice != null && notice != null) {
                isConsent = true
                infer("isConsentScreen", listOf(choice, notice))
            }
        }
        val evidenceOnly = { audit.copy(consentLens = evidence(classifier, threshold, labelled, inferred, support, notices, disagreements)) }
        if (!isConsent) return evidenceOnly()

        // 2. Pre-ticked options: the checkbox state is read from the tree, but its relevance rests on the inference.
        var preTicked = audit.preTicked
        if ("isConsentScreen" in inferred && preTicked == null && "preTicked" !in audit.observedFields) {
            preTicked = root.flatten().any { it.isCheckable && it.isChecked }
            infer("preTicked", listOfNotNull(top(buttons, ConsentRole.ACCEPT) ?: top(buttons, ConsentRole.REJECT)))
        }

        // 3. Reject option, from lexicon kinds plus ConsentLens button roles (same precedence as the tree auditor).
        var rejectOption = audit.rejectOption
        if (buttons.isNotEmpty()) {
            val lexKinds = nodes.filter { it.isClickable }.mapNotNull { ConsentLexicon.classifyButton(it.label) }.toSet()
            val lensKinds = buttons.map { it.second.role }.toSet()
            val combined = rejectOptionOf(lexKinds, lensKinds)
            val why = when (combined) {
                RejectOption.VISIBLE -> top(buttons, ConsentRole.REJECT)
                RejectOption.HIDDEN -> top(buttons, ConsentRole.MANAGE)
                RejectOption.NONE -> top(buttons, ConsentRole.ACCEPT)
                RejectOption.UNKNOWN -> null
            }
            when {
                combined == RejectOption.UNKNOWN || why == null -> Unit
                "rejectOption" in audit.observedFields -> if (combined != audit.rejectOption) {
                    conflicts += "rejectOption: tree=${audit.rejectOption}, ${classifier.modelId}=$combined (${describe(why)})"
                }
                audit.rejectOption == RejectOption.UNKNOWN -> {
                    rejectOption = combined
                    infer("rejectOption", listOf(why))
                }
                combined != audit.rejectOption -> disagreements += "rejectOption: screen review=${audit.rejectOption}, ${classifier.modelId}=$combined"
            }
        }

        // 4. Withdrawal information and stated purposes: only "found" can be inferred, never "missing".
        var withdrawal = audit.withdrawalInfo
        top(notices, ConsentRole.WITHDRAW_INFO)?.let { w ->
            when (audit.withdrawalInfo) {
                null -> { withdrawal = true; infer("withdrawalInfo", listOf(w)) }
                false -> disagreements += "withdrawalInfo: screen review=false, ${classifier.modelId} found ${describe(w)}"
                true -> Unit
            }
        }
        var purposeStated = audit.purposeStated
        val purposeTexts = notices.filter { it.second.role == ConsentRole.PURPOSE_NOTICE }.map { it.second }
        if (purposeTexts.isNotEmpty()) when (audit.purposeStated) {
            null -> { purposeStated = true; infer("purposeStated", listOf(purposeTexts.maxBy { it.confidence })) }
            false -> disagreements += "purposeStated: screen review=false, ${classifier.modelId} found ${describe(purposeTexts.first())}"
            true -> Unit
        }
        var purposesCount = audit.purposesCount
        val purposeLabels = purposeTexts.filter { ConsentLensText.normalize(it.text).length <= PURPOSE_LABEL_MAX_CHARS }
            .distinctBy { ConsentLensText.normalize(it.text) }
        if (purposesCount == null && purposeLabels.size >= 2) {
            purposesCount = purposeLabels.size
            infer("purposesCount", purposeLabels)
        }

        if (inferred.isEmpty() && conflicts.size == audit.conflicts.size) return evidenceOnly()
        return audit.copy(
            isConsentScreen = isConsent,
            preTicked = preTicked,
            rejectOption = rejectOption,
            withdrawalInfo = withdrawal,
            purposeStated = purposeStated,
            purposesCount = purposesCount,
            conflicts = conflicts,
            confidence = (listOf(audit.confidence) + supportConfidence).min(),
            source = if (inferred.isNotEmpty() && audit.source == AuditSource.DETERMINISTIC) AuditSource.MERGED else audit.source,
            consentLens = evidence(classifier, threshold, labelled, inferred, support, notices, disagreements),
        )
    }

    /** Same precedence as the tree auditor: any reject → VISIBLE, else settings → HIDDEN, else accept → NONE. */
    private fun rejectOptionOf(lex: Set<ConsentKind>, lens: Set<ConsentRole>): RejectOption = when {
        ConsentKind.REJECT in lex || ConsentRole.REJECT in lens -> RejectOption.VISIBLE
        ConsentKind.SETTINGS in lex || ConsentRole.MANAGE in lens -> RejectOption.HIDDEN
        ConsentKind.ACCEPT in lex || ConsentRole.ACCEPT in lens -> RejectOption.NONE
        else -> RejectOption.UNKNOWN
    }

    private fun describe(p: RolePrediction): String =
        "“${p.text.take(60)}” → ${p.role} ${String.format(Locale.ROOT, "%.2f", p.confidence)}"

    private fun evidence(
        classifier: ConsentRoleClassifier,
        threshold: Double,
        labelled: List<Pair<UiNode, RolePrediction>>,
        inferred: List<String>,
        support: Map<String, List<String>>,
        notices: List<Pair<UiNode, RolePrediction>>,
        disagreements: List<String>,
    ) = ConsentLensEvidence(
        modelId = classifier.modelId,
        backend = classifier.backendLabel,
        threshold = threshold,
        labels = labelled.map { (n, p) -> ConsentLensLabel(p.text, p.role.name, p.confidence, n.isClickable) },
        inferredFields = inferred.toList(),
        support = support.toMap(),
        privacyLinkFound = if (notices.any { it.second.role == ConsentRole.PRIVACY_LINK }) true else null,
        disagreements = disagreements.toList(),
    )
}
