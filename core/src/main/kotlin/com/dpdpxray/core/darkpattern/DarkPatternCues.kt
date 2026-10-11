package com.dpdpxray.core.darkpattern

import com.dpdpxray.core.consent.ConsentLexicon
import java.text.Normalizer

/**
 * The fixed word lists behind the dark-pattern signals (English, Hindi, Kannada). Each list mirrors the wording of an
 * Annexure 1 illustration of the CCPA's 2023 guidelines (see docs/DARK_PATTERNS.md); a match is a signal for review.
 * Hindi and Kannada cues are short and literal; a native speaker should review them before relying on them.
 */
internal object DarkPatternCues {
    /** Decomposed form without the Devanagari nukta, so "ज़्यादा" and "ज्यादा" (and Kannada vowel signs) compare equal. */
    fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace("़", "")

    private fun re(vararg patterns: String) = patterns.map { Regex(fold(it)) }

    /** Annexure 1 #3 illustrations: "I will stay unsecured", "charity is for rich, I don't care". Read on decline options. */
    private val SHAMING = re(
        "\\b(i prefer|i would rather|id rather|i rather) pay(ing)? (more|extra|full price|the full price)\\b",
        "\\bi like paying (more|full price)\\b",
        "\\bi (dont|do not) care\\b",
        "\\b(ill|i will) stay (unprotected|unsecured)\\b",
        "^(i )?accept (the )?risks?\\b",
        "\\bi (dont|do not) (want|like|need) (to save|saving|savings|discounts?|deals?|offers?|rewards?)\\b",
        "\\b(im|i am) (fine|ok|okay|happy) (paying|to pay|missing out)\\b",
        "\\bi (hate|dont like|do not like) (saving|savings|money|discounts?|deals?)\\b",
        "\\b(ill|i will) miss out\\b",
        // Hindi: "I don't want savings / a discount", "I don't care", "(I'll pay) more / the full price".
        "मुझे\\s*(बचत|छूट|डिस्काउंट|ऑफर|फायदा)\\s*नहीं\\s*चाहिए",
        "(परवाह|फिक्र)\\s*नहीं",
        "(ज्यादा|अधिक)\\s*(पैसे|भुगतान|कीमत)",
        "पूरी\\s*कीमत",
        // Kannada: "I don't want savings / a discount".
        "ಉಳಿತಾಯ\\s*ಬೇಡ",
        "ರಿಯಾಯಿತಿ\\s*ಬೇಡ",
    )

    /** Annexure 1 #1 illustrations: "Only 2 rooms left! 30 others are looking at this right now", a sale "for a limited time only". */
    private val URGENCY = re(
        "\\b(offers?|deals?|sale|discount|prices?)\\s+(ends|ending|expires|expiring)\\b",
        "\\b(ends|ending|expires|expiring)\\s+in\\s+\\d",
        "\\b\\d{1,2}:\\d{2}(:\\d{2})?\\s*(left|remaining)\\b",
        "\\bonly\\s+\\d+\\s+(left|remaining|available)\\b",
        "\\blimited[- ]time\\b",
        "\\bhurry\\b",
        "\\blast chance\\b",
        "\\b(today|tonight) only\\b",
        "\\bselling fast\\b",
        "\\b\\d+\\s+(others|people|users|shoppers)\\s+(are\\s+)?(looking|viewing|watching|buying)\\b",
        // Hindi: "limited time", "only N left", "<countdown> … ends", "hurry", "last chance".
        "सीमित\\s*समय",
        "(केवल|सिर्फ)\\s*\\d+\\s*(बचे|शेष)",
        "\\d{1,2}:\\d{2}.{0,20}(खत्म|समाप्त)",
        "जल्दी\\s*करें",
        "(आखिरी|अंतिम)\\s*मौका",
        // Kannada: "limited time", "only N left".
        "ಸೀಮಿತ\\s*ಸಮಯ",
        "ಕೇವಲ\\s*\\d+\\s*(ಉಳಿದಿವೆ|ಬಾಕಿ)",
    )

    /** Annexure 1 #11: "double negatives". Read on every consent-screen text. */
    private val DOUBLE_NEGATIVE = re(
        "\\bun(check|tick|select|mark)\\w*\\b(\\s+\\w+){0,4}?\\s+(to|if you)\\s+(not|dont|do not|never)\\b",
        "\\b(do not|dont|never)\\s+(un(check|tick|select|mark)\\w*|opt[- ]?out)\\b",
        "\\b(do not|dont)\\s+(wish|want)\\s+to\\s+not\\b",
        "\\bnot\\s+to\\s+not\\b",
        "(अनचेक|अनटिक)[^।.]{0,30}(नहीं|न )",
    )

    private val CLOSE = listOf("close", "dismiss", "skip", "cancel", "x", "×", "✕", "✖", "बंद करें", "बंद", "छोड़ें", "ಮುಚ್ಚಿ").map(::fold)
    private val PLAIN_DECLINE = listOf("no", "later", "maybe later", "ask me later", "बाद में", "ನಂತರ").map(::fold)

    private fun norm(label: String) = fold(ConsentLexicon.normalize(label))

    private fun startsWithAny(text: String, phrases: List<String>) = phrases.any { p -> text == p || text.startsWith("$p ") }

    fun isShaming(label: String): Boolean = norm(label).let { t -> SHAMING.any { it.containsMatchIn(t) } }

    fun isUrgency(text: String): Boolean = fold(text.lowercase()).let { t -> URGENCY.any { it.containsMatchIn(t) } }

    fun isDoubleNegative(text: String): Boolean = norm(text).let { t -> DOUBLE_NEGATIVE.any { it.containsMatchIn(t) } }

    /** A close/skip control (often an "X" icon with a content description). */
    fun isClose(label: String): Boolean = startsWithAny(norm(label), CLOSE)

    /** "No", "Maybe later": declines that the consent lexicon does not list as Reject. */
    fun isPlainDecline(label: String): Boolean = startsWithAny(norm(label), PLAIN_DECLINE)

    /** The on-device AI's free-text names, mapped to the pattern they name; null when they name none of the six. */
    private val AI_NAMES: List<Pair<CcpaPattern, Regex>> = listOf(
        CcpaPattern.NAGGING to "\\bnag(s|ging|ged)?\\b|re-?prompt|asks? again|again and again|keeps asking|repeated",
        CcpaPattern.CONFIRM_SHAMING to "sham(e|ing)|guilt",
        CcpaPattern.FALSE_URGENCY to "urgency|countdown|timer|scarcity|limited[- ]time",
        CcpaPattern.TRICK_QUESTION to "trick|double[- ]negative|confusing (wording|language)",
        CcpaPattern.FORCED_ACTION to "forced|no (reject|decline|refuse)|no option to (decline|refuse|reject)|no way to (decline|refuse|reject)|can(no|')?t refuse",
        CcpaPattern.INTERFACE_INTERFERENCE to "interface interference|hidden (reject|decline|refuse)|(reject|decline|refuse) (is )?hidden|less prominent|prominen|tiny|low contrast|gr[ae]yed|hard to find|buried",
    ).map { (p, s) -> p to Regex(s, RegexOption.IGNORE_CASE) }

    fun aiPattern(said: String): CcpaPattern? = AI_NAMES.firstOrNull { (_, r) -> r.containsMatchIn(said) }?.first
}
