package com.dpdpxray.core.consent

import com.dpdpxray.core.model.ConsentKind

/**
 * Recognises consent buttons and consent screens from on-screen text (English, Hindi, Kannada).
 * Reject phrases are checked before accept phrases because many contain accept words
 * ("Don't allow", "Continue without accepting").
 */
object ConsentLexicon {
    private const val MAX_BUTTON_LENGTH = 40

    private val REJECT = listOf(
        "reject", "reject all", "decline", "deny", "dont allow", "do not allow", "disagree", "no thanks",
        "not now", "refuse", "only necessary", "necessary only", "essential only", "only essential",
        "continue without accepting", "use necessary cookies only",
        "अस्वीकार", "अस्वीकार करें", "मना करें", "नहीं", "ತಿರಸ್ಕರಿಸಿ",
    )
    private val SETTINGS = listOf(
        "manage", "manage options", "manage preferences", "settings", "customize", "customise",
        "preferences", "more options", "प्राथमिकताएँ", "सेटिंग्स",
    )
    private val ACCEPT = listOf(
        "accept", "accept all", "i accept", "agree", "i agree", "agree and continue", "agree continue",
        "accept and continue", "accept continue", "allow", "allow all", "ok", "okay", "got it", "continue",
        "yes", "consent", "i consent", "yes i agree",
        "सहमत", "सहमत हूँ", "मैं सहमत हूँ", "स्वीकार", "स्वीकार करें", "अनुमति दें", "ठीक है",
        "ಒಪ್ಪುತ್ತೇನೆ", "ಸ್ವೀಕರಿಸಿ",
    )
    private val SCREEN_CUES = listOf(
        "privacy", "consent", "cookie", "personal data", "data protection", "personalised ads", "personalized ads",
        "share data", "share usage data", "data policy", "tracking", "gdpr", "dpdp", "terms",
        "गोपनीयता", "सहमति", "व्यक्तिगत डेटा", "ಗೌಪ್ಯತೆ", "ಸಮ್ಮತಿ",
    )

    fun classifyButton(label: String?): ConsentKind? {
        val text = normalize(label ?: return null)
        if (text.isEmpty() || text.length > MAX_BUTTON_LENGTH) return null
        return when {
            matches(text, REJECT) -> ConsentKind.REJECT
            matches(text, SETTINGS) -> ConsentKind.SETTINGS
            matches(text, ACCEPT) -> ConsentKind.ACCEPT
            else -> null
        }
    }

    /** Two distinct cue words, or one cue word plus a recognisable accept/reject button. */
    fun isConsentScreen(labels: List<String?>): Boolean {
        val texts = labels.filterNotNull().map { normalize(it) }.filter { it.isNotEmpty() }
        val all = texts.joinToString(" | ")
        val cueHits = SCREEN_CUES.count { all.contains(it) }
        if (cueHits >= 2) return true
        val hasChoice = labels.any { classifyButton(it).let { k -> k == ConsentKind.ACCEPT || k == ConsentKind.REJECT } }
        return cueHits >= 1 && hasChoice
    }

    /**
     * Wording that names a privacy policy or notice document, or a link to one: English (privacy policy / notice /
     * statement / centre, data policy, cookie policy, "privacy & terms"), Hindi and Kannada. "Privacy" alone ("We value
     * your privacy", "Settings › Privacy") does not count.
     */
    private val POLICY_PATTERNS = listOf(
        Regex("\\bprivacy (policy|policies|notice|statement|center|centre|terms)\\b"),
        Regex("\\b(data|cookie|cookies) (policy|notice)\\b"),
        Regex("\\bdata protection (policy|notice)\\b"),
        Regex("\\b(terms|terms of (use|service)) (and )?privacy\\b"),
        Regex("\\bprivacypolicy\\b"),
        Regex("https?\\S*privacy"),
        Regex("(गोपनीयता|निजता|प्राइवेसी) (नीति|सूचना|पॉलिसी|पालिसी)"),
        Regex("(ಗೌಪ್ಯತಾ|ಗೌಪ್ಯತೆ|ಖಾಸಗಿತನ) (ನೀತಿ|ಸೂಚನೆ)"),
    )

    /** True when any label names a privacy policy or notice document (see [POLICY_PATTERNS]). */
    fun mentionsPrivacyPolicy(labels: List<String?>): Boolean =
        labels.filterNotNull().any { raw ->
            val lower = raw.lowercase()
            val text = normalize(raw)
            POLICY_PATTERNS.any { it.containsMatchIn(text) || it.containsMatchIn(lower) }
        }

    private fun matches(text: String, phrases: List<String>): Boolean =
        phrases.any { p -> text == p || text.startsWith("$p ") }

    internal fun normalize(s: String): String =
        s.lowercase()
            .replace("&", " ")
            .replace("'", "")
            .replace("’", "")
            .replace(Regex("[\\p{Punct}&&[^-]]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
