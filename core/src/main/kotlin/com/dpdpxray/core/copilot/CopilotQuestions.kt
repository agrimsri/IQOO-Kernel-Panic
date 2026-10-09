package com.dpdpxray.core.copilot

enum class CopilotLanguage(val tag: String) { EN("en-IN"), HI("hi-IN") }

/** The questions the copilot can answer from an audit. Anything else is refused. */
enum class CopilotIntent { CHILDREN, AFTER_REJECT, BEFORE_CONSENT, WITHDRAWAL, LANGUAGE, CONSENT_SCREEN, SCORE, TRACKERS }

data class CopilotQuestion(val intent: CopilotIntent, val en: String, val hi: String)

/** Tappable chips plus a whole-word keyword matcher for spoken or typed questions (English and Hindi). */
object CopilotQuestions {
    val chips = listOf(
        CopilotQuestion(CopilotIntent.CHILDREN, "Is this app safe for children?", "क्या यह ऐप बच्चों के लिए सुरक्षित है?"),
        CopilotQuestion(CopilotIntent.AFTER_REJECT, "What happened after I rejected consent?", "मना करने के बाद ऐप ने क्या किया?"),
        CopilotQuestion(CopilotIntent.BEFORE_CONSENT, "Which trackers ran before I agreed?", "सहमति से पहले कौन से ट्रैकर चले?"),
        CopilotQuestion(CopilotIntent.CONSENT_SCREEN, "What is wrong with the consent screen?", "सहमति स्क्रीन में क्या गलत है?"),
        CopilotQuestion(CopilotIntent.WITHDRAWAL, "Can I withdraw my consent easily?", "क्या मैं अपनी सहमति आसानी से वापस ले सकता हूँ?"),
        CopilotQuestion(CopilotIntent.LANGUAGE, "Is the notice offered in Hindi?", "क्या सूचना हिंदी में दी गई है?"),
        CopilotQuestion(CopilotIntent.SCORE, "What score did it get?", "इसका स्कोर क्या है?"),
        CopilotQuestion(CopilotIntent.TRACKERS, "Which trackers did it contact?", "इसने किन ट्रैकरों से संपर्क किया?"),
    )

    fun byIntent(intent: CopilotIntent): CopilotQuestion = chips.first { it.intent == intent }

    // Checked in this order: the more specific intents win when a question mentions several topics.
    private val keywords: List<Pair<CopilotIntent, Set<String>>> = listOf(
        CopilotIntent.CHILDREN to setOf("child", "children", "kid", "kids", "minor", "minors", "teen", "teens", "बच्चे", "बच्चों", "बच्चा", "बालक"),
        CopilotIntent.AFTER_REJECT to setOf("reject", "rejected", "rejecting", "refuse", "refused", "refusing", "declined", "deny", "denied", "मना", "अस्वीकार"),
        CopilotIntent.BEFORE_CONSENT to setOf("before", "पहले"),
        CopilotIntent.WITHDRAWAL to setOf("withdraw", "withdrawal", "withdrawing", "revoke", "वापस"),
        CopilotIntent.LANGUAGE to setOf("language", "languages", "hindi", "हिंदी", "हिन्दी", "भाषा"),
        CopilotIntent.CONSENT_SCREEN to setOf("screen", "checkbox", "ticked", "button", "स्क्रीन"),
        CopilotIntent.SCORE to setOf("score", "rating", "grade", "स्कोर"),
        CopilotIntent.TRACKERS to setOf("tracker", "trackers", "tracking", "companies", "ट्रैकर", "ट्रैकरों"),
    )

    fun match(text: String): CopilotIntent? {
        val words = tokenize(text).toSet()
        return keywords.firstOrNull { (_, keys) -> keys.any { it in words } }?.first
    }

    /** Devanagari vowel signs are combining marks (\p{M}), so they must stay inside words. */
    fun tokenize(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.isNotEmpty() }
}
