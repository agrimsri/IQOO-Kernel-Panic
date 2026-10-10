package com.dpdpxray.core.law

import com.dpdpxray.core.model.Finding

/**
 * A finding's explanation for a lens. The rules engine writes its explanations with India's DPDP Act in mind (saved
 * audits keep that text), so under any other lens the DPDP-specific sentences are removed and the observation is kept;
 * the lens's own law is shown next to it. When an explanation still names DPDP (an older or newer wording this table does
 * not know), [Explained.citesIndia] is true and callers say so instead of hiding it.
 */
object LensText {
    /** Exact sentences the rules engine adds for India, and what replaces them under other lenses. */
    private val INDIA_SENTENCES: List<Pair<String, String>> = listOf(
        " Under the DPDP Act, personal data may be processed only with consent (or a legitimate use), and consent must be " +
            "given by a clear affirmative action before processing starts." to "",
        " DPDP requires a notice (s.5) and valid consent (s.6) before processing, and the Data Fiduciary must be able to " +
            "prove both (s.6(10))." to "",
        " Consent must be free and specific; processing based on a refused consent has no lawful ground." to "",
        " DPDP consent needs a clear affirmative action by the user." to "",
        " Consent must be free, and withdrawing it must be as easy as giving it." to "",
        " Consent must be specific to each specified purpose." to "",
        ", as Rule 3 requires." to ".",
        ", or complain to the Board." to ", or complain to a regulator.",
        " DPDP requires an option to access it in English or any Eighth Schedule language." to "",
        " DPDP bars tracking, behavioural monitoring and targeted advertising directed at children, and requires verifiable " +
            "parental consent." to "",
    )

    private val INDIA_MARKERS = listOf("DPDP", "Data Fiduciary", "Rule 3", "Eighth Schedule", "the Board")

    data class Explained(val text: String, val citesIndia: Boolean)

    fun explanation(finding: Finding, profile: LegalProfile): Explained {
        if (profile == LegalProfile.INDIA) return Explained(finding.explanation, false)
        val text = INDIA_SENTENCES.fold(finding.explanation) { acc, (from, to) -> acc.replace(from, to) }
        return Explained(text, citesIndia(text))
    }

    fun citesIndia(text: String): Boolean = INDIA_MARKERS.any { text.contains(it) }

    /** Shown under an explanation that still cites India's law while another lens is selected. */
    const val INDIA_WORDING_NOTE = "This explanation cites India's DPDP Act. The selected lens's own law is shown below."
}
