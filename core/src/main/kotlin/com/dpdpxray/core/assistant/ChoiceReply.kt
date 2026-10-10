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

        /** Bare numbers count only when nothing else is left ("one", "दो"); "the Lite one" names an app. */
        private val ONE = setOf("one", "एक")
        private val TWO = setOf("two", "दो")
        private val NUMBERS = ONE + TWO

        /** Hindi "दो" after another word is "do it" ("जाँच कर दो"), not "two". */
        private const val DO_IT = "दो"

        private val CANCEL = setOf(
            "cancel", "nevermind", "never", "neither", "none", "nothing", "no", "nahi", "nahin", "rehne", "chhodo", "chodo",
            "नहीं", "रहने", "छोडो", "कोई",
        )

        /** "No, Instagram Lite": a leading no rejects the question's framing, then names the app. */
        private val LEADING_NO = setOf("no", "nope", "nahi", "nahin", "नहीं")

        /** "not Instagram", "इंस्टाग्राम नहीं": which app was meant is unclear, so nothing is picked. */
        private val NEGATION = setOf("not", "dont", "don", "nahi", "nahin", "नहीं", "mat", "मत")

        /** Words that never name an app in a reply: the command parser's filler, check verbs and "which one" words. */
        private val STRIP = VoiceCommandParser.FILLER + VoiceCommandParser.CHECK_VERBS + VoiceCommandParser.MORE_WORDS + setOf(
            "one", "wala", "wali", "wale", "waala", "waali", "waale", "वाला", "वाली", "वाले", "please", "plz", "karo", "करो",
            "chahiye", "चाहिए", "chala", "chalao", "चला", "चलाओ", "dekho", "देखो",
        ) - NUMBERS

        /** How closely a spoken word must match a word of a label to count as appearing in it. */
        private const val WORD_MATCH = 0.8

        /**
         * Reads "the first one", "दूसरा", "Instagram Lite", "the Lite one" or "never mind" against the offered
         * [options]. A name is matched before cancel words, so "No, Instagram Lite" picks Instagram Lite.
         */
        fun parse(text: String, options: List<AppCandidate>): ChoiceReply {
            val all = VoiceCommandParser.tokens(text)
            if (all.isEmpty()) return Unclear
            // A tapped option or a label said in full ("My Jio"): matched before filler is stripped from it.
            val said = AppNameMatcher.words(text).joinToString("")
            options.indices.singleOrNull { AppNameMatcher.words(options[it].label).joinToString("") == said }?.let { return Picked(it) }
            val body = all.dropWhile { it in LEADING_NO }
            if (body.any { it in NEGATION }) return Cancel
            val rest = body.filterIndexed { i, w -> !(w == DO_IT && i > 0) && w !in STRIP }

            val name = rest.filterNot { it in NUMBERS || it in CANCEL || it in FIRST || it in SECOND }
            if (name.isNotEmpty()) {
                (AppNameMatcher.match(name.joinToString(" "), options) as? AppMatch.Found)?.let { return Picked(options.indexOf(it.app)) }
                onlyIn(name, options)?.let { return Picked(it) }
            }

            val number = rest.singleOrNull()?.takeIf { it in NUMBERS }
            val first = rest.any { it in FIRST } || (number != null && number in ONE)
            val second = rest.any { it in SECOND } || (number != null && number in TWO)
            return when {
                first && !second -> Picked(0)
                second && !first && options.size > 1 -> Picked(1)
                all.any { it in CANCEL } -> Cancel
                else -> Unclear
            }
        }

        /** The one option whose label contains a spoken word that no other option's label contains, if exactly one does. */
        private fun onlyIn(words: List<String>, options: List<AppCandidate>): Int? {
            val labels = options.map { AppNameMatcher.words(it.label) }
            fun has(i: Int, w: String) = labels[i].any { AppNameMatcher.score(w, it) >= WORD_MATCH }
            return options.indices.filter { i -> words.any { w -> has(i, w) && options.indices.none { j -> j != i && has(j, w) } } }.singleOrNull()
        }
    }
}
