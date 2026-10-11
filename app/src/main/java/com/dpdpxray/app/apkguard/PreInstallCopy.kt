package com.dpdpxray.app.apkguard

import com.dpdpxray.core.apk.Bilingual
import com.dpdpxray.core.model.Category

/** Text of the "check before install" part of an APK file's result, in English and simple Hindi. */
object PreInstallCopy {
    val sectionTitle = Bilingual("Before you install", "इंस्टॉल करने से पहले")
    val decide = Bilingual(
        "Install only if you trust where this file came from. TraceVeil installs exactly the file it checked, and Android asks you to confirm.",
        "इसे तभी इंस्टॉल करें जब आपको भरोसा हो कि फ़ाइल कहाँ से आई। TraceVeil वही फ़ाइल इंस्टॉल करता है जिसकी उसने जाँच की, और Android आपसे पुष्टि माँगता है।",
    )
    val blocked = Bilingual(
        "TraceVeil will not hand this file to Android's installer, because Android would refuse it.",
        "TraceVeil यह फ़ाइल Android के इंस्टॉलर को नहीं देगा, क्योंकि Android इसे इंस्टॉल नहीं करेगा।",
    )
    val installAnyway = Bilingual("Install anyway", "फिर भी इंस्टॉल करें")
    val dontInstall = Bilingual("Don't install", "इंस्टॉल न करें")
    val preparing = Bilingual("Handing the checked file to Android's installer…", "जाँची गई फ़ाइल Android के इंस्टॉलर को दी जा रही है…")
    val waiting = Bilingual(
        "Android is asking you to confirm. Nothing is installed until you tap Install there.",
        "Android आपसे पुष्टि माँग रहा है। जब तक आप वहाँ Install नहीं दबाते, कुछ इंस्टॉल नहीं होता।",
    )
    val needsPermission = Bilingual(
        "Android needs your permission once: allow TraceVeil under Install unknown apps. TraceVeil cannot switch this on by itself. Then come back and tap Install anyway again.",
        "Android को एक बार आपकी अनुमति चाहिए: अनजान ऐप इंस्टॉल करें में TraceVeil को अनुमति दें। TraceVeil इसे ख़ुद चालू नहीं कर सकता। फिर वापस आकर Install anyway दोबारा दबाएँ।",
    )
    val permissionGranted = Bilingual("Allowed. Tap Install anyway again to continue.", "अनुमति मिल गई। आगे बढ़ने के लिए Install anyway फिर से दबाएँ।")
    val openSetting = Bilingual("Open the setting", "सेटिंग खोलें")
    val installed = Bilingual(
        "Installed. A consent check shows what the app contacts when you accept or refuse its consent screen.",
        "इंस्टॉल हो गया। सहमति जाँच दिखाती है कि सहमति स्क्रीन पर हाँ या ना चुनने पर ऐप किससे संपर्क करता है।",
    )
    val runConsentCheck = Bilingual("Run a consent check", "सहमति जाँच चलाएँ")
    val seeInstalled = Bilingual("See it in APK Guard", "APK Guard में देखें")
    val cancelled = Bilingual(
        "Not installed: you cancelled in Android's installer. You can still decide.",
        "इंस्टॉल नहीं हुआ: आपने Android के इंस्टॉलर में रद्द किया। आप अब भी तय कर सकते हैं।",
    )
    val declined = Bilingual(
        "Not installed. TraceVeil deleted its checked copy; the original file is still where you found it.",
        "इंस्टॉल नहीं हुआ। TraceVeil ने अपनी जाँची हुई कॉपी हटा दी; असली फ़ाइल वहीं है जहाँ आपको मिली थी।",
    )
    val footnote = Bilingual(
        "TraceVeil installs only the exact file it checked, and only after you confirm in Android's installer. It never runs the file to check it.",
        "TraceVeil सिर्फ़ वही फ़ाइल इंस्टॉल करता है जिसकी उसने जाँच की, और वह भी Android के इंस्टॉलर में आपकी पुष्टि के बाद। जाँच के लिए यह फ़ाइल को कभी चलाता नहीं।",
    )

    /** For a build made without "Install anyway" (-Ptraceveil.installHandoff=false). */
    val continueWithInstaller = Bilingual(
        "This build of TraceVeil does not install apps. If, after this check, you still trust the file: go back to the app where you found it (for example Files or Chrome's downloads), tap the file again and choose Android's package installer. Android may ask you to allow installs from that app; you can switch that off again in Settings › Apps › Special app access › Install unknown apps.",
        "TraceVeil का यह बिल्ड ऐप इंस्टॉल नहीं करता। अगर इस जाँच के बाद भी आपको फ़ाइल पर भरोसा है: उस ऐप में वापस जाएँ जहाँ फ़ाइल मिली थी (जैसे Files या Chrome की डाउनलोड), फ़ाइल पर फिर टैप करें और Android का पैकेज इंस्टॉलर चुनें। Android उस ऐप से इंस्टॉल की अनुमति माँग सकता है; बाद में इसे सेटिंग्स › ऐप्स › ख़ास ऐप ऐक्सेस › अनजान ऐप इंस्टॉल करें में फिर से बंद कर सकते हैं।",
    )

    /** An app bundle (.xapk, .apks, .apkm) with [apks] APK files: checked, not installed by TraceVeil. */
    fun bundle(apks: Int) = Bilingual(
        "This is an app bundle with $apks APK files (the app plus configuration splits). TraceVeil checked the base APK's permissions and signer and the code of all $apks. " +
            "It does not install bundles: Android's own installer cannot open them either, so they are installed by a bundle installer, usually the store app that offered the file.",
        "यह $apks APK फ़ाइलों वाला ऐप बंडल है (ऐप और कॉन्फ़िगरेशन स्प्लिट)। TraceVeil ने मूल APK की अनुमतियाँ और साइनर, और सभी $apks का कोड जाँचा। " +
            "यह बंडल इंस्टॉल नहीं करता: Android का अपना इंस्टॉलर भी इन्हें नहीं खोल पाता, इसलिए इन्हें बंडल इंस्टॉलर, आम तौर पर फ़ाइल देने वाला स्टोर ऐप, इंस्टॉल करता है।",
    )

    // --- SDKs named in the code -------------------------------------------------------------------------------------

    val codeTitle = Bilingual("SDKs named in its code", "इसके कोड में SDK के नाम")
    val codeFooter = Bilingual(
        "Static: class names found in the app's code files, without running it. Present in the code does not prove an SDK runs or sends anything; not found does not prove it is absent, because names can be shortened.",
        "स्थिर जाँच: ऐप को चलाए बिना उसकी कोड फ़ाइलों में मिले क्लास के नाम। कोड में होने से यह साबित नहीं होता कि SDK चलता है या कुछ भेजता है; न मिलने से यह साबित नहीं होता कि वह नहीं है, क्योंकि नाम छोटे किए जा सकते हैं।",
    )
    val codeNone = Bilingual("None of the SDKs TraceVeil knows by name was found", "TraceVeil जिन SDK को नाम से जानता है, उनमें से कोई नहीं मिला")
    val codeRead = Bilingual("Read its code", "इसका कोड पढ़ें")
    val codeReading = Bilingual("Reading its code…", "इसका कोड पढ़ा जा रहा है…")
    val codeUnavailable = Bilingual(
        "Its code could not be read on this phone, so which SDKs it bundles is not established.",
        "इसका कोड इस फ़ोन पर पढ़ा नहीं जा सका, इसलिए इसमें कौन-से SDK हैं, पता नहीं।",
    )
    val codePartial = Bilingual(
        "Some code files were too large or unreadable and were skipped, so this list may be incomplete.",
        "कुछ कोड फ़ाइलें बहुत बड़ी या पढ़ने लायक नहीं थीं और छोड़ दी गईं, इसलिए यह सूची अधूरी हो सकती है।",
    )

    fun categoryLabel(category: Category?): Bilingual = when (category) {
        Category.ANALYTICS -> Bilingual("Tracking: analytics", "ट्रैकिंग: एनालिटिक्स")
        Category.ADVERTISING -> Bilingual("Tracking: advertising", "ट्रैकिंग: विज्ञापन")
        Category.ATTRIBUTION -> Bilingual("Tracking: install attribution", "ट्रैकिंग: इंस्टॉल एट्रिब्यूशन")
        Category.SOCIAL -> Bilingual("Tracking: social", "ट्रैकिंग: सोशल")
        Category.ENGAGEMENT -> Bilingual("Tracking: marketing messages", "ट्रैकिंग: मार्केटिंग संदेश")
        Category.CRASH_REPORTING -> Bilingual("Crash reporting", "क्रैश रिपोर्टिंग")
        else -> Bilingual("Not catalogued", "सूची में नहीं")
    }

    fun all(): List<Bilingual> = listOf(
        sectionTitle, decide, blocked, installAnyway, dontInstall, preparing, waiting, needsPermission, permissionGranted,
        openSetting, installed, runConsentCheck, seeInstalled, cancelled, declined, footnote, continueWithInstaller,
        codeTitle, codeFooter, codeNone, codeRead, codeReading, codeUnavailable, codePartial, bundle(3),
    )
}
