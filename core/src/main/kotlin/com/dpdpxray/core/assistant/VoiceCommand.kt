package com.dpdpxray.core.assistant

import com.dpdpxray.core.copilot.CopilotQuestions

/** Screens a voice command can open. The app maps each to its own navigation route. */
enum class OpenTarget { REPORTS, SETTINGS, HOME, TRUST }

/** What the user asked TraceVeil to do. Anything that is not a command is a [Question] for the grounded copilot. */
sealed interface VoiceCommand {
    /** Check (audit) an installed app; [name] is the spoken app name, or null when none was given. */
    data class CheckApp(val name: String?) : VoiceCommand

    /** Check an APK file with APK Guard. */
    data object CheckFile : VoiceCommand

    /** "My phone checkup": look over the installed apps. */
    data object PhoneCheckup : VoiceCommand

    /** Read out the latest saved result. */
    data object LastResult : VoiceCommand

    /** Open a screen; [target] is null when the screen asked for is not one TraceVeil can open by voice. */
    data class Open(val target: OpenTarget?) : VoiceCommand

    data object Help : VoiceCommand

    /** Stop listening for "Hey TraceVeil". */
    data object Stop : VoiceCommand

    /** Anything else, answered from the saved audit by the grounded copilot (or refused). [text] is as spoken. */
    data class Question(val text: String) : VoiceCommand

    /** Nothing was said (or only the wake phrase). */
    data object Empty : VoiceCommand
}

/** [hindi] is true when the words were Hindi, in Devanagari or in Latin script (Hinglish). */
data class ParsedCommand(val command: VoiceCommand, val hindi: Boolean)

/**
 * Deterministic keyword parser for spoken or typed commands in English, Hindi (Devanagari) and Hinglish. It never
 * guesses an action from loose speech: a command needs its verb or its fixed phrase, and every question the copilot
 * already understands stays a question. No model is involved.
 */
object VoiceCommandParser {
    private val WAKE_WORDS = setOf("hey", "hi", "ok", "okay", "हे", "हाय", "trace", "traceveil", "veil", "veal", "vail", "ट्रेस", "ट्रेसवील", "वील")
    private val WAKE_CORE = setOf("trace", "traceveil", "ट्रेस", "ट्रेसवील")

    private val CHECK_VERBS = setOf(
        "check", "checkup", "audit", "scan", "test", "inspect", "examine", "analyse", "analyze", "investigate",
        "jaanch", "janch", "jaancho", "jancho", "jaanchiye", "जांच", "जांचो", "जांचिए", "चेक", "स्कैन", "ऑडिट", "टेस्ट", "परखो",
    )

    /** Politeness and connective words that never belong to an app name. */
    private val FILLER = setOf(
        "please", "plz", "kindly", "can", "could", "would", "will", "you", "u", "now", "just", "quickly", "quick", "again", "for", "me",
        "a", "an", "the", "this", "that", "of", "on", "my", "app", "apps", "application", "run", "do", "start", "begin", "go", "ahead",
        "if", "is", "it", "whether", "safe", "okay", "ok", "and", "up",
        "ek", "zara", "jara", "abhi", "jaldi", "ko", "ki", "ka", "ke", "karo", "kar", "kare", "karein", "karen", "kariye", "kijiye", "de", "dena",
        "mera", "meri", "mere", "wala", "waala",
        "एक", "ज़रा", "जरा", "कृपया", "अभी", "जल्दी", "को", "की", "का", "के", "करो", "कर", "करें", "कीजिए", "कीजिये", "करिए", "दो", "दें",
        "ऐप", "एप", "एप्लीकेशन", "मेरा", "मेरी", "मेरे", "यह", "इस",
    )

    private val STOP_WORDS = setOf("stop", "quiet", "sleep", "band", "ruko", "ruk", "chup", "bas", "बंद", "रुको", "रुक", "चुप", "बस")
    private val STOP_ALLOWED = STOP_WORDS + setOf(
        "listening", "listen", "now", "please", "it", "be", "go", "to", "karo", "kar", "do", "sunna", "jao", "ho", "jaao",
        "करो", "कर", "दो", "सुनना", "जाओ", "हो", "जाइए", "hey", "trace", "veil", "traceveil",
    )

    private val FILE_WORDS = setOf("apk", "apks", "file", "files", "एपीके", "फाइल", "फाइलें")
    private val APK_WORDS = setOf("apk", "apks", "एपीके")
    private val PHONE_WORDS = setOf("phone", "mobile", "device", "handset", "फोन", "मोबाइल", "डिवाइस")
    private val ALL_WORDS = setOf("my", "all", "every", "mere", "sare", "saare", "sab", "सारे", "सभी", "सब", "मेरे")
    private val APPS_WORDS = setOf("apps", "applications", "ऐप्स", "एप्स")

    private val RESULT_WORDS = setOf(
        "result", "results", "findings", "summary", "summarise", "summarize", "natija", "nateeja", "parinaam",
        "नतीजा", "नतीजे", "परिणाम", "रिजल्ट", "सारांश",
    )
    private val LAST_WORDS = setOf("last", "latest", "recent", "previous", "pichla", "pichhla", "pichli", "पिछला", "पिछली", "पिछले", "आखिरी")
    private val REPORT_WORDS = setOf("check", "audit", "report", "scan", "jaanch", "janch", "रिपोर्ट", "जांच")

    private val OPEN_VERBS = setOf(
        "open", "show", "go", "goto", "take", "display", "launch", "kholo", "khol", "dikhao", "dikha", "jao", "chalo",
        "खोलो", "खोल", "दिखाओ", "दिखा", "जाओ", "चलो", "ले",
    )
    private val TARGETS = mapOf(
        OpenTarget.REPORTS to setOf("reports", "report", "history", "रिपोर्ट", "रिपोर्ट्स", "रिपोर्टें"),
        OpenTarget.SETTINGS to setOf("settings", "setting", "preferences", "सेटिंग", "सेटिंग्स"),
        OpenTarget.HOME to setOf("home", "होम"),
        OpenTarget.TRUST to setOf("trust", "ट्रस्ट"),
    )
    private val TARGET_FILLER = setOf("my", "the", "map", "screen", "page", "tab", "to", "me", "please", "mera", "meri", "मेरी", "मेरा", "पर", "pe", "par")

    private val STRONG_HINDI = setOf(
        "karo", "kariye", "kijiye", "karein", "karen", "kya", "mera", "meri", "mere", "batao", "bataiye", "dikhao", "kholo",
        "jaanch", "janch", "jaancho", "band", "ruko", "sakte", "sakti", "mila", "pichla", "pichhla", "natija", "nateeja", "sunna",
        "chalao", "dekho", "madad",
    )
    private val WEAK_HINDI = setOf("ko", "ki", "ka", "ke", "kar", "do", "hai", "ho", "tum", "aap", "ek")

    fun parse(raw: String): ParsedCommand {
        val text = raw.trim()
        val tokens = stripWake(tokens(text))
        val hindi = isHindi(text, tokens)
        return ParsedCommand(classify(text, tokens), hindi)
    }

    /** Lower-case words; Devanagari chandrabindu becomes anusvara and nukta dots are dropped so spellings agree. */
    fun tokens(text: String): List<String> =
        CopilotQuestions.tokenize(text.replace('ँ', 'ं').replace("़", ""))

    fun isHindi(text: String, tokens: List<String> = tokens(text)): Boolean =
        text.any { it in 'ऀ'..'ॿ' } || tokens.any { it in STRONG_HINDI } || tokens.count { it in WEAK_HINDI } >= 2

    private fun stripWake(tokens: List<String>): List<String> {
        val lead = tokens.takeWhile { it in WAKE_WORDS }
        return if (lead.any { it in WAKE_CORE }) tokens.drop(lead.size) else tokens
    }

    private fun classify(text: String, t: List<String>): VoiceCommand {
        if (t.isEmpty()) return VoiceCommand.Empty
        val words = t.toSet()
        val copilotTopic = CopilotQuestions.match(text) != null
        val hasCheckVerb = words.any { it in CHECK_VERBS }

        if (words.any { it in STOP_WORDS } && words.all { it in STOP_ALLOWED }) return VoiceCommand.Stop
        if (!copilotTopic && isHelp(words)) return VoiceCommand.Help
        if (!copilotTopic && isLastResult(words)) return VoiceCommand.LastResult
        if (words.any { it in APK_WORDS } || (hasCheckVerb && words.any { it in FILE_WORDS })) return VoiceCommand.CheckFile
        if (isPhoneCheckup(words, hasCheckVerb)) return VoiceCommand.PhoneCheckup
        open(words)?.let { return it }
        if (hasCheckVerb) {
            val name = t.filterNot { it in CHECK_VERBS || it in FILLER }
            if (name.isEmpty()) return VoiceCommand.CheckApp(null)
            val joined = name.joinToString(" ")
            // "check the score", "check trackers": a question about the audit, not an app called "score".
            if (CopilotQuestions.match(joined) != null) return VoiceCommand.Question(text)
            return VoiceCommand.CheckApp(joined)
        }
        return VoiceCommand.Question(text)
    }

    private fun isHelp(w: Set<String>): Boolean =
        "help" in w || "madad" in w || "मदद" in w || "commands" in w ||
            ("what" in w && "can" in w && "you" in w && "do" in w) ||
            ("how" in w && "use" in w) ||
            ("kya" in w && "kar" in w && ("sakte" in w || "sakti" in w || "sakta" in w)) ||
            ("क्या" in w && "कर" in w && ("सकते" in w || "सकती" in w || "सकता" in w))

    private fun isLastResult(w: Set<String>): Boolean =
        w.any { it in RESULT_WORDS } ||
            ("what" in w && ("find" in w || "found" in w)) ||
            (("kya" in w || "क्या" in w) && ("mila" in w || "मिला" in w)) ||
            (w.any { it in LAST_WORDS } && w.any { it in REPORT_WORDS })

    private fun isPhoneCheckup(w: Set<String>, hasCheckVerb: Boolean): Boolean {
        if (!hasCheckVerb) return false
        return w.any { it in PHONE_WORDS } || (w.any { it in APPS_WORDS } && w.any { it in ALL_WORDS })
    }

    private fun open(w: Set<String>): VoiceCommand.Open? {
        val target = TARGETS.entries.firstOrNull { (_, keys) -> keys.any { it in w } }?.key
        val verb = w.any { it in OPEN_VERBS }
        return when {
            verb && target != null -> VoiceCommand.Open(target)
            // A bare screen name ("reports", "settings") also opens it, but only when nothing else was said.
            target != null && w.all { word -> word in TARGET_FILLER || TARGETS.getValue(target).contains(word) } -> VoiceCommand.Open(target)
            verb && w.none { it in CHECK_VERBS } && w.any { it == "open" || it == "kholo" || it == "खोलो" } -> VoiceCommand.Open(null)
            else -> null
        }
    }
}
