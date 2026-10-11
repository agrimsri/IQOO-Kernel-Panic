package com.dpdpxray.app.ui.guide

import com.dpdpxray.app.audit.AuditProgress
import com.dpdpxray.app.control.Preflight
import com.dpdpxray.core.model.AuditPlan

/** The orchestrator's progress text, recognised by its stable prefix. Unknown text is still shown, just not read aloud. */
enum class LivePhase {
    STARTING, LOADING_AI, RESETTING, KEEPING_DATA, LAUNCHING, WAITING_SCREEN, AGENT_CHOOSING, YOUR_TURN,
    OBSERVING_CHOICE, OBSERVING_NO_CHOICE, OBSERVING_SILENT, REVIEWING, EVALUATING, RESTARTING, OTHER,

    /** A background watch is running (its line carries the minutes left). */
    WATCHING;

    companion object {
        fun of(step: String): LivePhase = when {
            step.startsWith("Starting") -> STARTING
            step.startsWith("Loading on-device AI") -> LOADING_AI
            step.startsWith("Resetting") -> RESETTING
            step.startsWith("Keeping") -> KEEPING_DATA
            step.startsWith("Restarting") -> RESTARTING
            step.startsWith("Launching") -> LAUNCHING
            step.startsWith("Waiting for a consent screen") -> WAITING_SCREEN
            step.startsWith("Agent is choosing") -> AGENT_CHOOSING
            step.startsWith("Your turn") -> YOUR_TURN
            step.startsWith("Observing traffic after") -> OBSERVING_CHOICE
            step.startsWith("Observing traffic without") -> OBSERVING_NO_CHOICE
            step.startsWith("Observing without touching") -> OBSERVING_SILENT
            step.startsWith("Finishing AI review") -> REVIEWING
            step.startsWith("Evaluating") -> EVALUATING
            step.startsWith(com.dpdpxray.app.audit.WatchCopy.STEP_PREFIX) -> WATCHING
            else -> OTHER
        }
    }
}

/**
 * Guided mode: one journey of four steps (get ready, choose an app, watch the check, read the result). Every function
 * is pure so the copy can be tested; screens only render the returned [GuideStep].
 */
object GuideFlow {
    const val TOTAL = 4

    private fun step(key: String, index: Int, tone: GuideTone, title: Bi, body: Bi, spoken: Bi) =
        GuideStep(key, index, TOTAL, tone, title, body, spoken)

    private fun heard(index: Int, en: String, hi: String) = Bi("Step $index of $TOTAL. $en", "चरण $index, कुल $TOTAL में से। $hi")

    fun home(pf: Preflight): GuideStep {
        val todo = SetupSteps.todo(SetupSteps.from(pf)).firstOrNull()
        return when (todo?.kind) {
            SetupKind.VPN -> step(
                "home.vpn", 1, GuideTone.ACTION,
                Bi("Allow the VPN", "VPN की अनुमति दें"),
                Bi("Tap Fix next to \"Allow the VPN\". Android asks once. TraceVeil then sees only the app you check.",
                    "\"Allow the VPN\" के आगे Fix दबाएँ। Android एक बार पूछेगा। फिर TraceVeil सिर्फ़ उसी ऐप को देखेगा जिसकी आप जाँच करते हैं।"),
                heard(1, "Allow the VPN. Tap Fix, and Android will ask you once.", "VPN की अनुमति दें। Fix दबाएँ, Android एक बार पूछेगा।"),
            )
            SetupKind.WATCHER -> step(
                "home.watcher", 1, GuideTone.ACTION,
                Bi("Turn on the consent watcher", "कंसेंट वॉचर चालू करें"),
                Bi("Tap Fix, open \"TraceVeil consent watcher\" in Accessibility, switch it on, then come back here.",
                    "Fix दबाएँ, Accessibility में \"TraceVeil consent watcher\" खोलें, उसे चालू करें और यहाँ वापस आएँ।"),
                heard(1, "Turn on the consent watcher. Tap Fix, switch it on, then come back.", "कंसेंट वॉचर चालू करें। Fix दबाएँ, उसे चालू करें, फिर वापस आएँ।"),
            )
            SetupKind.PRIVATE_DNS -> step(
                "home.dns", 2, GuideTone.CAUTION,
                Bi("Almost ready", "लगभग तैयार"),
                Bi("Private DNS is on and can hide lookups. Turn it off with Fix, or tap Check an app anyway.",
                    "Private DNS चालू है और लुकअप छिपा सकता है। Fix से इसे बंद करें, या फिर भी Check an app दबाएँ।"),
                heard(2, "Private DNS is on and can hide lookups. Turn it off, or tap Check an app.",
                    "Private DNS चालू है, यह लुकअप छिपा सकता है। इसे बंद करें, या Check an app दबाएँ।"),
            )
            else -> step(
                "home.ready", 2, GuideTone.ACTION,
                Bi("Check an app", "किसी ऐप की जाँच करें"),
                Bi("Tap Check an app, then pick an app you're allowed to test. New here? See a sample first.",
                    "Check an app दबाएँ, फिर वह ऐप चुनें जिसकी जाँच की आपको अनुमति है। पहली बार? पहले See a sample देखें।"),
                heard(2, "Tap Check an app, then pick the app you want to test.", "Check an app दबाएँ, फिर जाँच के लिए ऐप चुनें।"),
            )
        }
    }

    /** Home while a check runs in the background. Not spoken: the live narrator already reads the check's own steps. */
    fun homeRunning(appLabel: String) = step(
        "home.running", 3, GuideTone.WAIT,
        Bi("A check is running", "एक जाँच चल रही है"),
        Bi("TraceVeil is checking $appLabel. Tap Watch the check to follow it.", "TraceVeil $appLabel की जाँच कर रहा है। देखने के लिए Watch the check दबाएँ।"),
        Bi("", ""),
    )

    fun picker(demoInstalled: Boolean) = step(
        "pick.app", 2, GuideTone.ACTION,
        Bi("Pick the app to check", "जाँच के लिए ऐप चुनें"),
        if (demoInstalled) Bi("Tap an app you're allowed to test. LeakyShop, a demo app, is a good first choice.",
            "वह ऐप दबाएँ जिसकी जाँच की आपको अनुमति है। डेमो ऐप LeakyShop पहली जाँच के लिए अच्छा है।")
        else Bi("Tap an app you're allowed to test. Avoid banking, payment and chat apps.",
            "वह ऐप दबाएँ जिसकी जाँच की आपको अनुमति है। बैंकिंग, पेमेंट और चैट ऐप से बचें।"),
        heard(2, "Tap the app you want to check.", "जिस ऐप की जाँच करनी है, उसे दबाएँ।"),
    )

    fun pickerSetup(fullAvailable: Boolean) = step(
        "pick.setup", 2, GuideTone.ACTION,
        Bi("Choose how to check", "जाँच का तरीका चुनें"),
        if (fullAvailable) Bi("Full runs three times: don't touch, Reject, Accept. Quick runs once and keeps the app's data. Then tap Start check.",
            "Full तीन बार चलता है: बिना छुए, Reject, Accept। Quick एक बार चलता है और ऐप का डेटा रखता है। फिर Start check दबाएँ।")
        else Bi("Quick runs once and keeps the app's data. Full needs Shizuku and a test app you may reset. Then tap Start check.",
            "Quick एक बार चलता है और ऐप का डेटा रखता है। Full के लिए Shizuku और ऐसा टेस्ट ऐप चाहिए जिसे रीसेट किया जा सके। फिर Start check दबाएँ।"),
        Bi("Choose Full or Quick, then tap Start check.", "Full या Quick चुनें, फिर Start check दबाएँ।"),
    )

    /** Live audit guidance. Shown text may name the app; spoken text never does. */
    fun live(p: AuditProgress.Running): GuideStep {
        val app = p.config.targetLabel
        val i = p.planIndex
        val n = p.config.plans.size
        val choice = when (p.plan) { AuditPlan.ACCEPT -> "Accept"; AuditPlan.REJECT -> "Reject"; AuditPlan.SILENT -> null }
        val runEn = if (n > 1) "Run ${i + 1} of $n. " else ""
        val runHi = if (n > 1) "रन ${i + 1}, कुल $n में से। " else ""
        fun live(key: String, tone: GuideTone, title: Bi, body: Bi, spoken: Bi) = step(key, 3, tone, title, body, spoken)
        val silentWatch = live(
            "live.$i.silent", GuideTone.WAIT,
            Bi("Don't touch the app", "ऐप को न छुएँ"),
            Bi("Watching $app. Leave it alone: this run checks what happens when you make no choice.",
                "$app को देखा जा रहा है। इसे न छुएँ: यह रन देखता है कि कोई विकल्प न चुनने पर क्या होता है।"),
            Bi("Don't touch the app. TraceVeil is just watching.", "ऐप को न छुएँ। TraceVeil सिर्फ़ देख रहा है।"),
        )
        return when (LivePhase.of(p.step)) {
            LivePhase.STARTING -> live(
                "live.starting", GuideTone.WAIT,
                Bi("Starting the check", "जाँच शुरू हो रही है"),
                Bi("Starting the DNS watcher for $app. Keep TraceVeil open for a moment.", "$app के लिए DNS वॉचर शुरू हो रहा है। एक पल TraceVeil खुला रखें।"),
                heard(3, "Starting the check.", "जाँच शुरू हो रही है।"),
            )
            LivePhase.LOADING_AI -> live(
                "live.ai", GuideTone.WAIT,
                Bi("Loading on-device AI", "ऑन-डिवाइस AI लोड हो रहा है"),
                Bi("The AI model loads on this phone. This can take a few seconds.", "AI मॉडल इसी फ़ोन पर लोड होता है। इसमें कुछ सेकंड लग सकते हैं।"),
                Bi("Loading the on-device AI. This can take a few seconds.", "ऑन-डिवाइस AI लोड हो रहा है। कुछ सेकंड लग सकते हैं।"),
            )
            LivePhase.RESETTING -> live(
                "live.$i.reset", GuideTone.WAIT,
                Bi("Fresh start", "नई शुरुआत"),
                Bi("Clearing $app's data, as you approved, so it opens like a new install.",
                    "आपकी मंज़ूरी से $app का डेटा साफ़ किया जा रहा है, ताकि यह नए इंस्टॉल की तरह खुले।"),
                Bi("${runEn}Giving the test app a fresh start, as you approved.", "${runHi}आपकी मंज़ूरी से टेस्ट ऐप को नई शुरुआत दी जा रही है।"),
            )
            LivePhase.KEEPING_DATA -> live(
                "live.$i.keep", GuideTone.WAIT,
                Bi("Keeping the app's data", "ऐप का डेटा रखा जा रहा है"),
                Bi("$app keeps its data. If it already showed its consent popup before, it may not show it again.",
                    "$app का डेटा नहीं हटेगा। अगर यह पहले कंसेंट पॉपअप दिखा चुका है, तो शायद दोबारा न दिखाए।"),
                Bi("${runEn}The app keeps its data. It may not show its consent popup again.",
                    "${runHi}ऐप का डेटा रखा जा रहा है। हो सकता है कंसेंट पॉपअप दोबारा न दिखे।"),
            )
            LivePhase.RESTARTING -> live(
                "live.$i.restart", GuideTone.WAIT,
                Bi("Restarting the app", "ऐप रीस्टार्ट हो रहा है"),
                Bi("$app is closed and opened again so it starts fresh. Its data stays. If it showed its consent popup before, it may not show it again.",
                    "$app को बंद करके फिर खोला जा रहा है ताकि यह नए सिरे से शुरू हो। इसका डेटा बना रहता है। अगर यह पहले कंसेंट पॉपअप दिखा चुका है, तो शायद दोबारा न दिखाए।"),
                Bi("${runEn}Restarting the app. Its data stays.", "${runHi}ऐप रीस्टार्ट हो रहा है। इसका डेटा बना रहता है।"),
            )
            LivePhase.LAUNCHING -> live(
                "live.$i.launch", GuideTone.WAIT,
                Bi("Opening the app", "ऐप खुल रहा है"),
                Bi("Opening $app. TraceVeil keeps watching in the background.", "$app खुल रहा है। TraceVeil पीछे से देखता रहेगा।"),
                Bi("Opening the app. TraceVeil keeps watching in the background.", "ऐप खुल रहा है। TraceVeil पीछे से देखता रहेगा।"),
            )
            LivePhase.WAITING_SCREEN -> when {
                choice == null -> silentWatch
                p.config.autoDrive -> live(
                    "live.$i.wait", GuideTone.WAIT,
                    Bi("Watching the app", "ऐप को देखा जा रहा है"),
                    Bi("Watching $app… TraceVeil will tap $choice for you when the consent popup appears.",
                        "$app को देखा जा रहा है… कंसेंट पॉपअप आने पर TraceVeil आपके लिए $choice दबाएगा।"),
                    Bi("Watching the app. TraceVeil will tap $choice for you.", "ऐप को देखा जा रहा है। TraceVeil आपके लिए $choice दबाएगा।"),
                )
                else -> live(
                    "live.$i.wait", GuideTone.WAIT,
                    Bi("Wait for the consent popup", "कंसेंट पॉपअप का इंतज़ार करें"),
                    Bi("Watching $app. When its consent popup appears, you'll tap $choice.",
                        "$app को देखा जा रहा है। कंसेंट पॉपअप आने पर आपको $choice दबाना है।"),
                    Bi("Wait for the consent popup. Then you will tap $choice.", "कंसेंट पॉपअप का इंतज़ार करें। फिर आपको $choice दबाना है।"),
                )
            }
            LivePhase.AGENT_CHOOSING -> live(
                "live.$i.agent", GuideTone.WAIT,
                Bi("TraceVeil is tapping ${choice ?: "the choice"}", "TraceVeil ${choice ?: "विकल्प"} दबा रहा है"),
                Bi("The consent popup appeared. TraceVeil is tapping ${choice ?: "the planned choice"} for you. Don't touch the screen.",
                    "कंसेंट पॉपअप आ गया है। TraceVeil आपके लिए ${choice ?: "तय विकल्प"} दबा रहा है। स्क्रीन न छुएँ।"),
                Bi("The popup appeared. TraceVeil is tapping ${choice ?: "the choice"} for you.",
                    "पॉपअप आ गया है। TraceVeil आपके लिए ${choice ?: "विकल्प"} दबा रहा है।"),
            )
            LivePhase.YOUR_TURN -> live(
                "live.$i.turn", GuideTone.ACTION,
                Bi("Your turn: tap ${choice ?: "your choice"}", "अब आपकी बारी: ${choice ?: "अपना विकल्प"} दबाएँ"),
                Bi("Tap ${choice ?: "your choice"} in $app's consent popup. TraceVeil records the moment you tap.",
                    "$app के कंसेंट पॉपअप में ${choice ?: "अपना विकल्प"} दबाएँ। TraceVeil आपके दबाने का समय दर्ज करेगा।"),
                Bi("Your turn. Tap ${choice ?: "your choice"} in the app.", "अब आपकी बारी। ऐप में ${choice ?: "अपना विकल्प"} दबाएँ।"),
            )
            LivePhase.OBSERVING_CHOICE -> live(
                "live.$i.observe", GuideTone.WAIT,
                Bi("Choice recorded", "विकल्प दर्ज हो गया"),
                Bi("Keep $app open. TraceVeil keeps watching its lookups for a few more seconds.",
                    "$app खुला रखें। TraceVeil कुछ और सेकंड तक उसके लुकअप देखता रहेगा।"),
                Bi("Choice recorded. Keep the app open while TraceVeil keeps watching.", "विकल्प दर्ज हो गया। ऐप खुला रखें, TraceVeil देख रहा है।"),
            )
            LivePhase.OBSERVING_NO_CHOICE -> live(
                "live.$i.nochoice", GuideTone.CAUTION,
                Bi("No choice recorded", "कोई विकल्प दर्ज नहीं हुआ"),
                Bi("TraceVeil didn't see a tap on Accept or Reject. It keeps watching, and the result will say the choice is unknown.",
                    "TraceVeil ने Accept या Reject पर टैप नहीं देखा। यह देखता रहेगा, और नतीजे में लिखा होगा कि विकल्प अज्ञात है।"),
                Bi("No choice was recorded. TraceVeil keeps watching, and the result will say so.",
                    "कोई विकल्प दर्ज नहीं हुआ। TraceVeil देखता रहेगा, और नतीजे में यह बताया जाएगा।"),
            )
            LivePhase.OBSERVING_SILENT -> silentWatch
            LivePhase.REVIEWING -> live(
                "live.$i.review", GuideTone.WAIT,
                Bi("Finishing this run", "यह रन पूरा हो रहा है"),
                Bi("Reviewing the consent screen on this phone. This run is almost done.", "इसी फ़ोन पर कंसेंट स्क्रीन की समीक्षा हो रही है। यह रन लगभग पूरा है।"),
                Bi("Finishing this run.", "यह रन पूरा हो रहा है।"),
            )
            LivePhase.EVALUATING -> live(
                "live.evaluating", GuideTone.WAIT,
                Bi("Working out the result", "नतीजा तैयार हो रहा है"),
                Bi("Checking the captured lookups against TraceVeil's rules. The result opens next.",
                    "दर्ज लुकअप को TraceVeil के नियमों से जाँचा जा रहा है। इसके बाद नतीजा खुलेगा।"),
                Bi("Working out the result.", "नतीजा तैयार हो रहा है।"),
            )
            // One key for the whole watch: the minutes left change the line on screen, not what is read aloud.
            LivePhase.WATCHING -> live(
                "live.watch", GuideTone.WAIT,
                Bi("Watching in the background", "बैकग्राउंड में देखा जा रहा है"),
                com.dpdpxray.app.audit.WatchCopy.liveNote(app),
                Bi("Watching in the background. You can use your phone as usual or lock it.",
                    "बैकग्राउंड में देखा जा रहा है। आप फ़ोन हमेशा की तरह इस्तेमाल कर सकते हैं या लॉक कर सकते हैं।"),
            )
            // Unrecognised orchestrator text may contain names; it is shown as-is but never read aloud.
            LivePhase.OTHER -> live("live.$i.other.${p.step.hashCode()}", GuideTone.WAIT, Bi(p.step, p.step), Bi("", ""), Bi("", ""))
        }
    }

    /** What the background narrator reads during a check. Null when guidance is off or there is nothing to say. */
    fun narration(progress: AuditProgress, enabled: Boolean, lang: GuideLang): Narration? {
        if (!enabled) return null
        return when (progress) {
            is AuditProgress.Running -> live(progress).let { s -> s.spoken.pick(lang).takeIf { it.isNotBlank() }?.let { Narration(s.key, it, lang) } }
            is AuditProgress.Failed -> Narration("live.failed", Bi("The check stopped. Open TraceVeil to see why.", "जाँच रुक गई। कारण देखने के लिए TraceVeil खोलें।").pick(lang), lang)
            else -> null
        }
    }
}
