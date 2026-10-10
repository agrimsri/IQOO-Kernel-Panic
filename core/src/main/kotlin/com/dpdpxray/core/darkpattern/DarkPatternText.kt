package com.dpdpxray.core.darkpattern

import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.EvidenceType
import kotlinx.serialization.Serializable

/**
 * Words shown with the dark-pattern signals on the Results card, in everyday mode, in the reports and in the export.
 * Neutral by rule: a signal "resembles" a pattern the CCPA describes; it is never called a dark pattern, a violation or
 * illegal, and it never changes the score.
 */
object DarkPatternText {
    const val CARD_TITLE = "Dark-pattern signals (India CCPA 2023)"
    const val NONE = "None seen in this capture"
    const val NOT_CHECKED = "Not checked: no consent-screen tree was captured"
    const val SOURCE_TITLE = "CCPA, Guidelines for Prevention and Regulation of Dark Patterns, 2023 (notified 30 Nov 2023), Annexure 1"
    const val SOURCE_URL =
        "https://ccpa.doca.gov.in/files/The%20Guidelines%20for%20Prevention%20and%20Regulation%20of%20Dark%20Patterns,%202023_1732707717.pdf"

    const val FOOTER = "Signals for review, not legal findings, and not counted in the score. Each one resembles a pattern described in " +
        "Annexure 1 of the CCPA's Guidelines for Prevention and Regulation of Dark Patterns, 2023, which says its list provides \"only guidance\". " +
        "Checked only on the consent screen and the seconds after the choice: 7 of the 13 listed patterns happen at checkout, pricing or " +
        "billing and are not checked here."

    const val AI_NOTE = "AI-inferred rows repeat the on-device AI's reading of the screen; the fixed rules did not see them."

    const val BASIS = "Checked on the phone with fixed rules from this bundle's consent-screen trees and recorded taps; rows marked " +
        "INFERRED repeat the on-device AI's reading only. Each signal resembles a pattern described in Annexure 1 of the CCPA's " +
        "Guidelines for Prevention and Regulation of Dark Patterns, 2023, which says its list provides only guidance. Signals for " +
        "review, not legal findings; they do not change the score."

    fun summary(signals: List<DarkPatternSignal>, screensChecked: Int): String = when {
        signals.isEmpty() && screensChecked == 0 -> NOT_CHECKED
        signals.isEmpty() -> NONE
        else -> {
            val ai = signals.count { it.evidenceType == EvidenceType.INFERRED }
            "${signals.size} of ${CcpaPattern.CHECKABLE.size} checkable patterns seen at the consent moment" +
                if (ai > 0) " ($ai from the AI reading only)" else ""
        }
    }

    fun rowTitle(s: DarkPatternSignal) = "${s.pattern.title} · Annexure 1 #${s.pattern.number}"

    fun resembles(s: DarkPatternSignal) = "Resembles the pattern described in CCPA 2023 Annexure 1 #${s.pattern.number} (${s.pattern.title})."

    fun chip(s: DarkPatternSignal) = if (s.evidenceType == EvidenceType.OBSERVED) "Observed" else "AI-inferred"

    fun plans(s: DarkPatternSignal) = s.plans.joinToString { it.label }

    /** Everyday mode: one calm sentence, or null when no consent screen could be checked. */
    fun everydayLine(signals: List<DarkPatternSignal>, screensChecked: Int): String? = when {
        signals.isEmpty() && screensChecked == 0 -> null
        signals.isEmpty() -> "Consent popup design: no dark-pattern signals seen in this capture."
        else -> "Consent popup design: ${signals.size} signal${if (signals.size == 1) "" else "s"} resembling India's CCPA dark-pattern list " +
            "(${signals.joinToString { it.pattern.title.lowercase() }}). Not part of the score."
    }
}

/**
 * The optional "darkPatterns" part of a traceveil.audit.v1 bundle. Derived on the phone from the bundle's own sessions;
 * readers that predate it ignore it, and a bundle without it means "not exported", never "none seen".
 */
@Serializable
data class DarkPatternExport(
    val basis: String = DarkPatternText.BASIS,
    /** Consent-screen trees that were available to check; 0 = nothing could be checked. */
    val screensChecked: Int,
    val signals: List<DarkPatternSignal>,
) {
    companion object {
        fun of(sessions: List<AuditSession>) =
            DarkPatternExport(screensChecked = DarkPatternScanner.screensChecked(sessions), signals = DarkPatternScanner.scan(sessions))
    }
}
