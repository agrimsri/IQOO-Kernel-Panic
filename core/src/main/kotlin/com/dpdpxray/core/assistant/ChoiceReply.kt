package com.dpdpxray.core.assistant

/** The answer to "Which one?" after two apps were offered. */
sealed interface ChoiceReply {
    data class Picked(val index: Int) : ChoiceReply
    data object Cancel : ChoiceReply

    /** Not an answer to the question; the words should be treated as a new command. */
    data object Unclear : ChoiceReply

    companion object {
        private val FIRST = setOf("first", "1", "1st", "former", "pehla", "pahla", "pehli", "pahli", "पहला", "पहली", "पहले")
        private val SECOND = setOf("second", "2", "2nd", "latter", "other", "doosra", "dusra", "doosri", "dusri", "दूसरा", "दूसरी", "दूसरे")

        /** Bare numbers count only when no ordinal was said ("the second one" is the second). */
        private val ONE = setOf("one", "एक")
        private val TWO = setOf("two", "दो")
        private val CANCEL = setOf(
            "cancel", "nevermind", "never", "neither", "none", "nothing", "no", "nahi", "nahin", "rehne", "chhodo", "chodo",
            "नहीं", "रहने", "छोडो", "कोई",
        )

        /** Reads "the first one", "दूसरा", "Instagram Lite" or "never mind" against the offered [options]. */
        fun parse(text: String, options: List<AppCandidate>): ChoiceReply {
            val words = VoiceCommandParser.tokens(text).toSet()
            if (words.isEmpty()) return Unclear
            if (words.any { it in CANCEL } && words.none { it in FIRST || it in SECOND }) return Cancel
            when (val m = AppNameMatcher.match(text, options)) {
                is AppMatch.Found -> return Picked(options.indexOf(m.app))
                else -> Unit
            }
            val ordinal = words.any { it in FIRST || it in SECOND }
            val first = words.any { it in FIRST } || (!ordinal && words.any { it in ONE })
            val second = words.any { it in SECOND } || (!ordinal && words.any { it in TWO })
            return when {
                first && !second -> Picked(0)
                second && !first && options.size > 1 -> Picked(1)
                else -> Unclear
            }
        }
    }
}
