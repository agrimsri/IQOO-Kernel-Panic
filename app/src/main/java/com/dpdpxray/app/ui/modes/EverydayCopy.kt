package com.dpdpxray.app.ui.modes

import com.dpdpxray.app.data.Audience
import com.dpdpxray.app.ui.guide.Bi
import com.dpdpxray.app.ui.guide.GuideFlow
import com.dpdpxray.app.ui.guide.GuideStep
import com.dpdpxray.app.ui.guide.GuideTone
import com.dpdpxray.app.ui.guide.SetupKind
import com.dpdpxray.core.model.ScoreBand

/** Names of the two modes, shown in More, Settings and on the everyday Home. */
object ModeCopy {
    fun name(a: Audience) = when (a) {
        Audience.USER -> Bi("Everyday", "रोज़मर्रा")
        Audience.DEVELOPER -> Bi("Developer", "डेवलपर")
    }

    fun tagline(a: Audience) = when (a) {
        Audience.USER -> Bi("Protect my phone", "मेरे फ़ोन की सुरक्षा")
        Audience.DEVELOPER -> Bi("Test my app", "मेरे ऐप की टेस्टिंग")
    }

    fun title(a: Audience) = Bi("Mode: ${name(a).en}", "मोड: ${name(a).hi}")

    val note = Bi(
        "The mode changes Home and how a check starts. Your checks, reports and settings stay the same.",
        "मोड से होम और जाँच शुरू करने का तरीका बदलता है। आपकी जाँचें, रिपोर्ट और सेटिंग वैसी ही रहती हैं।",
    )
    val switchToDeveloper = Bi("Switch to developer mode", "डेवलपर मोड पर जाएँ")
}

/** Whether new-app alerts can reach the person, from the notification permission and the consent watcher. */
enum class AlertStatus { ON, WHILE_OPEN, BLOCKED }

/**
 * Every word of the everyday ("Protect my phone") Home, in English and Hindi. Plain words only: it describes what
 * TraceVeil can show and what to review, never whether an app is good or bad. Pure so it can be unit tested.
 */
object EverydayCopy {
    data class Card(val title: Bi, val body: Bi)

    val eyebrow = ModeCopy.tagline(Audience.USER)
    val heroTitle = Bi("Apps promise.\nTraceVeil checks.", "ऐप वादा करते हैं।\nTraceVeil जाँचता है।")
    val heroBody = Bi(
        "See what an app can do on your phone, and whether it listens when you say No.",
        "देखें कि कोई ऐप आपके फ़ोन पर क्या-क्या कर सकता है, और आपके 'ना' कहने पर वह मानता है या नहीं।",
    )
    val onPhone = Bi("Checks run on this phone", "जाँच इसी फ़ोन पर होती है")
    val readsOnly = Bi("Reads app files, never installs them", "ऐप फ़ाइल सिर्फ़ पढ़ता है, इंस्टॉल नहीं करता")

    val file = Card(
        Bi("Check a file someone sent me", "किसी की भेजी फ़ाइल जाँचें"),
        Bi("An app file (APK) from a chat, email or download. See what it could do before you install it.",
            "चैट, ईमेल या डाउनलोड से आई ऐप फ़ाइल (APK)। इंस्टॉल करने से पहले देखें कि यह क्या-क्या कर सकती है।"),
    )
    val apps = Card(
        Bi("Check my apps", "मेरे ऐप जाँचें"),
        Bi("A checkup of the apps on this phone: what each one is allowed to do, and what to review.",
            "इस फ़ोन के ऐप की जाँच: हर ऐप को क्या-क्या करने की अनुमति है, और किन बातों पर ध्यान दें।"),
    )
    val alerts = Card(
        Bi("New-app alerts", "नए ऐप की सूचना"),
        Bi("When a new app is installed, TraceVeil asks you to check it before you open it.",
            "कोई नया ऐप इंस्टॉल होने पर TraceVeil आपसे कहता है कि उसे खोलने से पहले जाँच लें।"),
    )
    val sayNo = Card(
        Bi("Does this app respect my 'No'?", "क्या यह ऐप मेरी 'ना' मानता है?"),
        Bi("Open an app with TraceVeil, say No to its consent popup, and see which addresses it still tries to reach.",
            "TraceVeil के साथ कोई ऐप खोलें, उसके कंसेंट पॉपअप पर 'ना' कहें, और देखें कि वह फिर भी किन पतों तक पहुँचने की कोशिश करता है।"),
    )
    val trust = Card(
        Bi("Why you can trust TraceVeil", "TraceVeil पर भरोसा क्यों करें"),
        Bi("What it looks at, what it never does, and where your results stay.",
            "यह क्या देखता है, क्या कभी नहीं करता, और आपके नतीजे कहाँ रहते हैं।"),
    )

    // Readiness for the "No" check: the same VPN + consent watcher gate as everywhere else, in one sentence.
    val ready = Bi("Ready", "तैयार")
    fun stepsFirst(n: Int) = if (n == 1) Bi("1 step first", "पहले 1 कदम") else Bi("$n steps first", "पहले $n कदम")
    val readiness = Bi(
        "First switch on two things: the VPN, so TraceVeil can see which addresses the app tries to reach, and the consent watcher, so it can see the popup.",
        "पहले दो चीज़ें चालू करें: VPN, ताकि TraceVeil देख सके कि ऐप किन पतों तक पहुँचने की कोशिश करता है, और कंसेंट वॉचर, ताकि वह पॉपअप देख सके।",
    )

    /** Titles and details for the setup rows inside the "No" card (only steps still to do are shown). */
    fun setupTitle(kind: SetupKind) = when (kind) {
        SetupKind.VPN -> Bi("Allow the VPN", "VPN की अनुमति दें")
        SetupKind.WATCHER -> Bi("Turn on the consent watcher", "कंसेंट वॉचर चालू करें")
        SetupKind.PRIVATE_DNS -> Bi("Turn off Private DNS", "Private DNS बंद करें")
        SetupKind.SHIZUKU -> Bi("Optional helper", "वैकल्पिक सहायक")
    }

    fun setupDetail(kind: SetupKind) = when (kind) {
        SetupKind.VPN -> Bi("Shows TraceVeil which addresses the checked app tries to reach. Only that app.",
            "TraceVeil को दिखाता है कि जाँचा जा रहा ऐप किन पतों तक पहुँचने की कोशिश करता है। सिर्फ़ वही ऐप।")
        SetupKind.WATCHER -> Bi("In Accessibility, switch on \"TraceVeil consent watcher\".",
            "Accessibility में \"TraceVeil consent watcher\" चालू करें।")
        SetupKind.PRIVATE_DNS -> Bi("While it's on, the check may miss some addresses.",
            "इसके चालू रहते जाँच कुछ पते चूक सकती है।")
        SetupKind.SHIZUKU -> Bi("Only for app testers.", "सिर्फ़ ऐप टेस्ट करने वालों के लिए।")
    }

    /** Alerts appear only with notifications allowed; Android keeps TraceVeil running in the background only while
     * its consent watcher is on, otherwise alerts come only while TraceVeil is open or recently used. */
    fun alertStatus(notificationsAllowed: Boolean, watcherOn: Boolean): AlertStatus = when {
        !notificationsAllowed -> AlertStatus.BLOCKED
        watcherOn -> AlertStatus.ON
        else -> AlertStatus.WHILE_OPEN
    }

    fun alertLabel(status: AlertStatus, readAloud: Boolean): Bi = when (status) {
        AlertStatus.ON -> if (readAloud) Bi("On · read aloud", "चालू · पढ़कर सुनाता है") else Bi("On", "चालू")
        AlertStatus.WHILE_OPEN -> Bi("Only while TraceVeil is open", "सिर्फ़ TraceVeil खुला होने पर")
        AlertStatus.BLOCKED -> Bi("Off: notifications are blocked", "बंद: नोटिफ़िकेशन बंद हैं")
    }

    val running = Bi("Watch the check", "जाँच देखें")

    val recentTitle = Bi("Recent checks", "हाल की जाँचें")
    val seeAll = Bi("See all", "सभी देखें")
    val emptyTitle = Bi("No checks yet", "अभी कोई जाँच नहीं")
    val emptyBody = Bi("Your checks will appear here. A sample shows what a result looks like.",
        "आपकी जाँचें यहाँ दिखेंगी। नमूने से पता चलता है कि नतीजा कैसा दिखता है।")
    val seeSample = Bi("See a sample", "नमूना देखें")
    val sample = Bi("Sample", "नमूना")
    val partial = Bi("Partial check", "अधूरी जाँच")

    /** Plain names for a result's band: what was seen, never a verdict on the app. */
    fun band(b: ScoreBand) = when (b) {
        ScoreBand.READY -> Bi("Nothing flagged in this check", "इस जाँच में कुछ नहीं मिला")
        ScoreBand.NEEDS_WORK -> Bi("Worth a look", "देखने लायक बातें")
        ScoreBand.AT_RISK -> Bi("Several things to review", "कई बातें देखनी हैं")
        ScoreBand.INCONCLUSIVE -> Bi("Not enough was seen", "पर्याप्त नहीं दिखा")
    }

    /** Everything on the everyday Home, for wording checks. */
    fun all(): List<Bi> = listOf(eyebrow, heroTitle, heroBody, onPhone, readsOnly) +
        listOf(file, apps, alerts, sayNo, trust).flatMap { listOf(it.title, it.body) } +
        listOf(ready, stepsFirst(1), stepsFirst(2), readiness, running, recentTitle, seeAll, emptyTitle, emptyBody, seeSample, sample, partial) +
        listOf(SetupKind.VPN, SetupKind.WATCHER, SetupKind.PRIVATE_DNS).flatMap { listOf(setupTitle(it), setupDetail(it)) } +
        AlertStatus.entries.flatMap { listOf(alertLabel(it, false), alertLabel(it, true)) } +
        ScoreBand.entries.map { band(it) } +
        Audience.entries.flatMap { listOf(ModeCopy.name(it), ModeCopy.tagline(it), ModeCopy.title(it)) } +
        listOf(ModeCopy.note, ModeCopy.switchToDeveloper)
}

/** The everyday check sheet: one Quick check that says No, with Full and clearing folded under Advanced. */
object EverydaySheetCopy {
    fun title(app: String) = Bi("Check $app", "$app की जाँच")

    /** Step 2 of the guided journey, like the developer sheet's banner. Spoken text never names the app. */
    fun guide(app: String) = GuideStep(
        "pick.setup.everyday", 2, GuideFlow.TOTAL, GuideTone.ACTION,
        Bi("Say No, then watch", "'ना' कहें, फिर देखें"),
        Bi("Tap Start check. When $app asks for consent, say No (Reject), or let TraceVeil tap it. Then it watches for 15 seconds.",
            "Start check दबाएँ। जब $app सहमति माँगे, 'ना' (Reject) कहें, या TraceVeil को दबाने दें। फिर यह 15 सेकंड तक देखता है।"),
        Bi("Tap Start check, then say No when the app asks.", "Start check दबाएँ, फिर ऐप पूछे तो 'ना' कहें।"),
    )

    val quickTitle = Bi("Quick check: say No", "जल्दी जाँच: 'ना' कहें")
    val quickDetail = Bi("Opens the app once, says No (Reject) to its consent popup, then watches for 15 seconds. The app keeps its data.",
        "ऐप एक बार खुलता है, कंसेंट पॉपअप पर 'ना' (Reject) कहा जाता है, फिर 15 सेकंड तक देखा जाता है। ऐप का डेटा नहीं हटता।")
    val agentTitle = Bi("Let TraceVeil tap No for me", "TraceVeil मेरे लिए 'ना' दबाए")
    val agentDetail = Bi("Off: you tap it yourself when the app asks.", "बंद: ऐप पूछे तो आप खुद दबाएँ।")
    val quickCaveat = Bi(
        "If the app asked you before, it may not ask again; then the result says your No wasn't tested. A quiet result covers only this short check.",
        "अगर ऐप पहले पूछ चुका है, तो शायद दोबारा न पूछे; तब नतीजे में लिखा होगा कि आपकी 'ना' की जाँच नहीं हुई। शांत नतीजा सिर्फ़ इस छोटी जाँच के बारे में है।",
    )
    val resetCaveat = Bi(
        "Starting clears this app's data before each run, as you allowed. Use it only for an app you test, never for a personal app.",
        "आपकी अनुमति से, शुरू करने पर हर रन से पहले इस ऐप का डेटा साफ़ होगा। इसे सिर्फ़ टेस्ट वाले ऐप पर इस्तेमाल करें, निजी ऐप पर कभी नहीं।",
    )
    val start = Bi("Start check", "जाँच शुरू करें")

    val advanced = Bi("Advanced", "उन्नत")
    val advancedSummary = Bi("Full check, clearing app data and more. For apps you test.",
        "पूरी जाँच, ऐप डेटा साफ़ करना और बाकी विकल्प। जिन ऐप की आप टेस्टिंग करते हैं, उनके लिए।")
    val fullTitle = Bi("Full check", "पूरी जाँच")
    val fullDetail = Bi("Three runs from a fresh start: no answer, No, then Yes, compared side by side. Clears the app's data before each run.",
        "नई शुरुआत से तीन रन: कोई जवाब नहीं, 'ना', फिर 'हाँ', और तीनों की तुलना। हर रन से पहले ऐप का डेटा साफ़ होता है।")
    val fullNeeds = Bi("Needs the Shizuku helper app and your OK to clear this app's data. Not for personal apps.",
        "इसके लिए Shizuku सहायक ऐप और इस ऐप का डेटा साफ़ करने की आपकी मंज़ूरी चाहिए। निजी ऐप के लिए नहीं।")
    val resetTitle = Bi("Allow clearing this app's data", "इस ऐप का डेटा साफ़ करने दें")
    val resetDetail = Bi("For your own test apps only. Removes its saved state, accounts and settings before each run.",
        "सिर्फ़ आपके अपने टेस्ट ऐप के लिए। हर रन से पहले उसकी सहेजी स्थिति, अकाउंट और सेटिंग हटाता है।")
    val childTitle = Bi("This app is made for children", "यह ऐप बच्चों के लिए है")
    val childDetail = Bi("Adds the children's check: no tracking or targeted ads.", "बच्चों वाली जाँच जोड़ता है: कोई ट्रैकिंग या टारगेटेड विज्ञापन नहीं।")
    val gmsTitle = Bi("Include Google Play services lookups", "Google Play services के लुकअप भी शामिल करें")
    val gmsDetail = Bi("Some apps send through Play services. These lookups may include other apps' traffic.",
        "कुछ ऐप Play services के ज़रिए भेजते हैं। इन लुकअप में दूसरे ऐप का ट्रैफ़िक भी हो सकता है।")
    val quarantine = Bi("Run with the internet cut off", "इंटरनेट काटकर चलाएँ")
    val quarantineNote = Bi("Cuts only this app's internet and records what it tries to reach and which permissions it asks for. It doesn't judge the app.",
        "सिर्फ़ इस ऐप का इंटरनेट काटता है और दर्ज करता है कि यह कहाँ पहुँचने की कोशिश करता है और कौन-सी अनुमतियाँ माँगता है। यह ऐप के बारे में फ़ैसला नहीं देता।")

    fun all(): List<Bi> = listOf(title("App"), guide("App").title, guide("App").body, guide("App").spoken, quickTitle, quickDetail,
        agentTitle, agentDetail, quickCaveat, resetCaveat, start, advanced, advancedSummary, fullTitle, fullDetail, fullNeeds,
        resetTitle, resetDetail, childTitle, childDetail, gmsTitle, gmsDetail, quarantine, quarantineNote)
}
