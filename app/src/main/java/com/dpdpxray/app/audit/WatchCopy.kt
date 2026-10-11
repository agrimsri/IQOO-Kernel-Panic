package com.dpdpxray.app.audit

import com.dpdpxray.app.ui.guide.Bi
import com.dpdpxray.core.model.BackgroundWatches

/**
 * Every word of the background watch in the app, in both guidance languages: the check sheets' option, the ongoing
 * notification, the live screen, the result's one-tap action and the notes saved with the watch. Plain words, no verdicts.
 * Pure, so the copy and the time formatting are tested.
 */
object WatchCopy {
    // --- Check sheets ---------------------------------------------------------------------------------------------------

    val optionTitle = Bi("Watch in the background", "बैकग्राउंड में देखें")

    fun optionDetail(minutes: Int) = Bi(
        "Records only this app's lookups and connections for $minutes minutes while it runs in the background. TraceVeil doesn't " +
            "open, restart or tap it, and its data stays. Use your phone as usual or lock it.",
        "$minutes मिनट तक, जब यह ऐप बैकग्राउंड में चलता है, सिर्फ़ इसके लुकअप और कनेक्शन दर्ज करता है। TraceVeil इसे न खोलता, " +
            "न रीस्टार्ट करता, न टैप करता है, और इसका डेटा बना रहता है। फ़ोन हमेशा की तरह इस्तेमाल करें या लॉक करें।",
    )

    val lengthLabel = Bi("How long", "कितनी देर")

    fun lengthChip(minutes: Int) = Bi("$minutes min", "$minutes मिनट")

    val caveat = Bi(
        "A watch shows what the app sends in the background, not how its consent popup behaves. Connections it opened before " +
            "the watch began are not seen, and a quiet app may send almost nothing in that time.",
        "निगरानी दिखाती है कि ऐप बैकग्राउंड में क्या भेजता है, यह नहीं कि उसका कंसेंट पॉपअप कैसे काम करता है। निगरानी शुरू होने से " +
            "पहले खोले गए कनेक्शन नहीं दिखते, और शांत ऐप उस समय में लगभग कुछ भी न भेजे, ऐसा हो सकता है।",
    )

    val start = Bi("Start watching", "देखना शुरू करें")

    // --- The result's one-tap action ------------------------------------------------------------------------------------

    fun action(app: String, minutes: Int) = Bi("Watch $app in the background for $minutes minutes", "$app को $minutes मिनट बैकग्राउंड में देखें")

    val actionNote = Bi(
        "TraceVeil keeps recording only this app's connections while you use your phone or lock it. It doesn't open, restart " +
            "or tap the app, and its data stays.",
        "जब आप फ़ोन इस्तेमाल करते हैं या लॉक करते हैं, TraceVeil सिर्फ़ इस ऐप के कनेक्शन दर्ज करता रहता है। यह ऐप को न खोलता, " +
            "न रीस्टार्ट करता, न टैप करता है, और उसका डेटा बना रहता है।",
    )

    // --- While watching: notification and live screen -------------------------------------------------------------------

    /** Whole minutes left, rounded up, never below 1 while the watch runs: "7 min left". */
    fun minutesLeft(remainingMs: Long): Bi {
        val m = ((remainingMs.coerceAtLeast(0) + 59_999) / 60_000).coerceAtLeast(1)
        return Bi("$m min left", "$m मिनट बाकी")
    }

    /** "Watching RollCall's connections · 7 min left" */
    fun notificationTitle(app: String, remainingMs: Long): Bi {
        val left = minutesLeft(remainingMs)
        return Bi("Watching $app's connections · ${left.en}", "$app के कनेक्शन देखे जा रहे हैं · ${left.hi}")
    }

    fun notificationText(app: String) = Bi(
        "Only $app's traffic goes through TraceVeil, which sends nothing of its own. Use your phone as usual or lock it.",
        "सिर्फ़ $app का ट्रैफ़िक TraceVeil से होकर जाता है; TraceVeil अपना कुछ नहीं भेजता। फ़ोन हमेशा की तरह इस्तेमाल करें या लॉक करें।",
    )

    val stop = Bi("Stop and save", "रोकें और सहेजें")

    fun finishedTitle(app: String) = Bi("Finished watching $app", "$app को देखना पूरा हुआ")

    val finishedText = Bi("Tap to see what TraceVeil saw.", "TraceVeil ने क्या देखा, यह देखने के लिए टैप करें।")

    /** The orchestrator's progress line; its prefix marks the live phase (see LivePhase). */
    fun step(remainingMs: Long) = "$STEP_PREFIX: ${minutesLeft(remainingMs).en}"

    const val STEP_PREFIX = "Background watch"

    val liveLabel = Bi("Live · Background watch", "लाइव · बैकग्राउंड निगरानी")

    fun liveNote(app: String) = Bi(
        "Use your phone as usual or lock it. TraceVeil doesn't open, restart or tap $app; a notification shows the time left.",
        "फ़ोन हमेशा की तरह इस्तेमाल करें या लॉक करें। TraceVeil $app को न खोलता, न रीस्टार्ट करता, न टैप करता है; बचा समय नोटिफ़िकेशन में दिखता है।",
    )

    // --- Notes saved with the watch's plan (English, like every plan note) ----------------------------------------------

    fun startNote(app: String, minutes: Int) =
        "Background watch: TraceVeil did not open, restart or tap $app; it kept capturing only this app's lookups and connections " +
            "for up to $minutes minutes while the phone was used as usual."

    fun stoppedNote(watchedMs: Long) = "You stopped the watch after ${BackgroundWatches.duration(watchedMs)}; what was seen until then is kept."

    fun captureStoppedNote(watchedMs: Long) =
        "The capture stopped after ${BackgroundWatches.duration(watchedMs)}, so the watch ended there; what was seen until then is kept."

    const val LOCKED_NOTE = "The phone was locked for part of the watch. That is expected: a background watch runs while the phone " +
        "is locked or used for other things."

    const val NEVER_OFF_SCREEN_NOTE = "The app could not be confirmed off screen during the watch (the screen could not be read), " +
        "so no lookup is counted as made in the background."

    fun onScreenNote(app: String, ms: Long) = "$app was on screen, or the screen could not be read, for about " +
        "${BackgroundWatches.duration(ms)} of the watch; lookups then are not counted as made in the background."

    /** Everything a person can read or hear, for the language and verdict-word tests. */
    fun all(): List<Bi> = listOf(
        optionTitle, optionDetail(10), lengthLabel, lengthChip(5), caveat, start, action("App", 10), actionNote, minutesLeft(420_000),
        notificationTitle("App", 420_000), notificationText("App"), stop, finishedTitle("App"), finishedText, liveLabel, liveNote("App"),
    )
}
