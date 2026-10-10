package com.dpdpxray.app.voice.assistant

/**
 * Everything the assistant says, in English and Hindi. Short, plain sentences; no claim that an app is safe or
 * harmful, and results are always "in this check".
 */
object AssistantCopy {
    val cue = Said("Yes?", "हाँ, बोलिए?")

    val notCaught = Said(
        "Sorry, I didn't catch that. Say Hey TraceVeil and try again.",
        "माफ़ कीजिए, मैं समझ नहीं पाया। हे ट्रेसवील कहकर फिर से बोलिए।",
    )

    val help = Said(
        "You can say: check, then an app name, like check Instagram. Check my phone. Check a file. What did you find. " +
            "Open reports, or settings. Or ask about your last check, like which trackers did it contact. Say stop listening to turn me off.",
        "आप कह सकते हैं: इंस्टाग्राम की जाँच करो। मेरे फ़ोन की जाँच करो। फ़ाइल की जाँच करो। क्या मिला? रिपोर्ट खोलो। " +
            "या पिछली जाँच के बारे में पूछिए, जैसे किन ट्रैकरों से संपर्क किया। मुझे बंद करने के लिए कहिए: सुनना बंद करो।",
    )

    val stopped = Said("Okay, I've stopped listening for Hey TraceVeil.", "ठीक है, मैंने हे ट्रेसवील के लिए सुनना बंद कर दिया।")
    val notListening = Said("I'm not listening in the background right now.", "मैं अभी बैकग्राउंड में नहीं सुन रहा हूँ।")

    val noSavedChecks = Said(
        "You have no saved checks yet. Say check, then an app name, to run one.",
        "अभी कोई सहेजी हुई जाँच नहीं है। जाँच शुरू करने के लिए कहिए: इंस्टाग्राम की जाँच करो।",
    )

    val answering = Said("Let me look at your last check.", "आपकी पिछली जाँच देख रहा हूँ।")

    val noEvidenceHint = Said(" Say what can you do to hear what I can help with.", " मैं क्या कर सकता हूँ, यह सुनने के लिए पूछिए: तुम क्या कर सकते हो?")

    val openWhat = Said(
        "I can open Reports, Settings, Home or the trust map. To check an app, say check, then its name.",
        "मैं रिपोर्ट, सेटिंग्स, होम या ट्रस्ट मैप खोल सकता हूँ। किसी ऐप की जाँच के लिए कहिए: उसका नाम, फिर जाँच करो।",
    )

    fun opening(en: String, hi: String) = Said("Opening $en.", "$hi खोल रहा हूँ।")

    fun notInThisVersion(en: String, hi: String) = Said("$en is not in this version of TraceVeil.", "$hi ट्रेसवील के इस संस्करण में नहीं है।")

    val openingCheckup = Said("Opening My phone checkup.", "फ़ोन चेकअप खोल रहा हूँ।")
    val pickApk = Said("Choose the APK file you want me to check.", "जिस APK फ़ाइल की जाँच करनी है, उसे चुनिए।")

    val whichApp = Said("Which app should I check? Pick it from the list.", "किस ऐप की जाँच करूँ? सूची में से चुनिए।")

    fun notFound(name: String) = Said(
        "I couldn't find an app called $name on this phone.",
        "इस फ़ोन पर $name नाम का ऐप नहीं मिला।",
    )

    fun whichOne(a: String, b: String) = Said("I found $a and $b. Which one?", "मुझे $a और $b मिले। कौन सा?")

    val cancelled = Said("Okay, I won't check anything.", "ठीक है, मैं कोई जाँच नहीं करूँगा।")

    /**
     * Another check is running ([app] is its label when known). Nothing promises to report on it: a check started from
     * the app picker has no voice follow-up.
     */
    fun alreadyRunning(app: String?) = Said(
        (app?.let { "A check of $it is already running." } ?: "A check is already running.") + " Wait for it to finish, then ask me again.",
        (app?.let { "$it की एक जाँच पहले से चल रही है।" } ?: "एक जाँच पहले से चल रही है।") + " उसके पूरा होने तक रुकिए, फिर दोबारा कहिए।",
    )
    val quarantineActive = Said("Stop the quarantine run first, then ask me again.", "पहले क्वारंटीन रन बंद कीजिए, फिर दोबारा कहिए।")

    fun fixVpn(app: String) = Said(
        "Before I can check $app, allow TraceVeil's VPN. I'm opening the request: tap OK, then ask me again.",
        "$app की जाँच से पहले ट्रेसवील का VPN अनुमति दीजिए। मैं अनुरोध खोल रहा हूँ: OK दबाइए, फिर दोबारा कहिए।",
    )

    fun fixWatcher(app: String) = Said(
        "Before I can check $app, turn on the TraceVeil consent watcher in Accessibility settings. I'm opening them; then ask me again.",
        "$app की जाँच से पहले Accessibility सेटिंग्स में ट्रेसवील कंसेंट वॉचर चालू कीजिए। मैं सेटिंग्स खोल रहा हूँ; फिर दोबारा कहिए।",
    )

    fun fixPrivateDns(app: String) = Said(
        "Before I can check $app, turn off Private DNS: it hides the lookups I need to see. I'm opening network settings; then ask me again.",
        "$app की जाँच से पहले Private DNS बंद कीजिए: इससे वे लुकअप छिप जाते हैं जो मुझे देखने हैं। मैं नेटवर्क सेटिंग्स खोल रहा हूँ; फिर दोबारा कहिए।",
    )

    fun checking(app: String) = Said(
        "Starting a Quick check of $app. Choose on the app's popup, I'm watching. I'll tell you what I find in this check.",
        "$app की क्विक जाँच शुरू। ऐप के पॉप-अप पर आप खुद चुनिए, मैं देख रहा हूँ। इस जाँच में जो मिलेगा, मैं बताऊँगा।",
    )

    fun checkingDemo(app: String) = Said(
        "Starting a Quick check of $app, a demo app. I'll tap Accept on its popup myself and tell you what I find in this check.",
        "$app डेमो ऐप की क्विक जाँच शुरू। इसके पॉप-अप पर Accept मैं खुद दबाऊँगा और इस जाँच में जो मिलेगा, बताऊँगा।",
    )

    val quickOnly = Said(
        "By voice I only run a Quick check that keeps the app's data; start a Full audit from the app list.",
        "आवाज़ से मैं सिर्फ़ क्विक जाँच करता हूँ, जो ऐप का डेटा नहीं मिटाती; फ़ुल ऑडिट ऐप सूची से शुरू कीजिए।",
    )

    // --- outcomes of screen work -------------------------------------------------------------------------------------

    val tapNotification = Said(
        "I need TraceVeil on screen for that. Tap the TraceVeil notification to continue.",
        "इसके लिए ट्रेसवील स्क्रीन पर चाहिए। आगे बढ़ने के लिए ट्रेसवील की सूचना पर टैप कीजिए।",
    )

    val unlockFirst = Said(
        "Unlock your phone, then tap the TraceVeil notification to continue.",
        "फ़ोन अनलॉक कीजिए, फिर आगे बढ़ने के लिए ट्रेसवील की सूचना पर टैप कीजिए।",
    )

    val openApp = Said("Open TraceVeil to do that.", "इसके लिए ट्रेसवील खोलिए।")

    fun couldNotStart(reason: String?) = Said(
        "I couldn't start the check" + (reason?.let { ": $it" } ?: ".") ,
        "जाँच शुरू नहीं हो पाई" + (reason?.let { ": $it" } ?: "।"),
    )

    val screenMissing = Said("That screen is not in this version of TraceVeil.", "वह स्क्रीन ट्रेसवील के इस संस्करण में नहीं है।")

    val checkCancelled = Said("The check was cancelled, so there is no new result.", "जाँच रद्द हो गई, इसलिए कोई नया नतीजा नहीं है।")

    fun checkStopped(reason: String) = Said("The check stopped: $reason", "जाँच रुक गई: $reason")

    val micUnavailable = Said(
        "I couldn't use the microphone for a command just now. Open TraceVeil and try again.",
        "अभी कमांड के लिए माइक्रोफ़ोन नहीं मिल पाया। ट्रेसवील खोलकर फिर कोशिश कीजिए।",
    )

    val noRecognizer = Said(
        "This phone has no on-device speech recognizer, so I can't take spoken commands. Open TraceVeil and use the buttons.",
        "इस फ़ोन में ऑन-डिवाइस स्पीच पहचान नहीं है, इसलिए मैं बोली गई कमांड नहीं ले सकता। ट्रेसवील खोलकर बटन इस्तेमाल कीजिए।",
    )

    /** Notification line for a command that needs the screen, e.g. "Tap to continue: check Instagram". */
    fun continueTitle(action: AssistantAction): String = when (action) {
        is AssistantAction.StartAudit -> "Tap to continue: check ${action.config.targetLabel}"
        is AssistantAction.FixSetup -> "Tap to finish setting up checks"
        AssistantAction.PickApkFile -> "Tap to choose an APK file"
        is AssistantAction.Navigate -> "Tap to open TraceVeil"
    }
}
