package com.dpdpxray.core.apk

/**
 * APK Guard's deterministic rules. Input is what Android shows about a package; output is a list of labelled signals
 * and a summary that is never a safety verdict. Severity says how much attention a capability deserves, not whether
 * the app misuses it. The rules are a static, versioned catalogue; no app-name shortcuts decide a capability signal.
 */
object ApkRiskRules {
    const val VERSION = 1

    private const val P = "android.permission."
    private val SMS_READ = listOf("READ_SMS", "RECEIVE_SMS", "RECEIVE_MMS", "RECEIVE_WAP_PUSH").map { P + it }
    private val SMS_SEND = listOf(P + "SEND_SMS")
    private val CALL_LOG = listOf("READ_CALL_LOG", "WRITE_CALL_LOG", "PROCESS_OUTGOING_CALLS").map { P + it }
    private val PHONE = listOf("READ_PHONE_STATE", "READ_PHONE_NUMBERS", "CALL_PHONE", "ANSWER_PHONE_CALLS").map { P + it }
    private val PHONE_ACTIVE = listOf("READ_PHONE_NUMBERS", "CALL_PHONE", "ANSWER_PHONE_CALLS").map { P + it }
    private val CONTACTS = listOf("READ_CONTACTS", "WRITE_CONTACTS").map { P + it }
    private const val FINE_LOCATION = P + "ACCESS_FINE_LOCATION"
    private const val BACKGROUND_LOCATION = P + "ACCESS_BACKGROUND_LOCATION"
    private const val CAMERA = P + "CAMERA"
    private const val MICROPHONE = P + "RECORD_AUDIO"
    private const val OVERLAY = P + "SYSTEM_ALERT_WINDOW"
    private const val INSTALL_PACKAGES = P + "REQUEST_INSTALL_PACKAGES"
    private const val ALL_FILES = P + "MANAGE_EXTERNAL_STORAGE"
    private const val QUERY_ALL_PACKAGES = P + "QUERY_ALL_PACKAGES"
    private val PHOTOS_AND_FILES = listOf("MANAGE_EXTERNAL_STORAGE", "READ_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO").map { P + it }

    private const val BASIS_PLAY_PROTECT = "Google Play Protect developer guidance treats SMS, notification-listener and accessibility access as high risk for apps sideloaded from the internet."
    private const val BASIS_CERT_IN = "CERT-In advisory CICA-2026-3492 (March 2026) on fake RTO/e-Challan apps: multi-stage install, SMS and call access, VPN request, OTP forwarding."
    private const val BASIS_SPYMAX = "Cyber Swachhta Kendra SpyMax alert (June 2025): fake apps that obtained contacts, SMS, OTPs and notification content."
    private const val BASIS_SPYLOAN = "McAfee Labs SpyLoan research (November 2024): loan apps that requested contacts and files and misused them for coercion."
    private const val BASIS_FILES = "Police and CERT-In advisories (2025-2026) describe fake challan, pension and wedding-invitation apps shared as APK files in messages."

    private fun bi(en: String, hi: String) = Bilingual(en, hi)

    fun evaluate(facts: ApkFacts): ApkGuardReport {
        val f = Ctx(facts)
        val signals = buildList {
            addAll(capabilities(f))
            add(installSource(f))
            addAll(combinations(f))
            nameResemblance(f)?.let { add(it) }
            addAll(identity(f))
        }.sortedWith(compareBy({ it.severity.ordinal }, { it.evidence.ordinal }))
        return ApkGuardReport(facts, signals, summary(signals))
    }

    /** Lookups shared by the rules. */
    private class Ctx(val facts: ApkFacts) {
        fun declared(perms: List<String>) = perms.filter { it in facts.permissions }
        fun has(perm: String) = perm in facts.permissions
        fun components(kind: ServiceKind) = facts.components.filter { it.kind == kind }
        fun hasService(kind: ServiceKind) = facts.components.any { it.kind == kind }
        val offPlay get() = facts.origin.source.offPlay
        val isFile get() = facts.origin.source == InstallSource.NOT_INSTALLED
    }

    // --- single capabilities ------------------------------------------------------------------------------------------

    private fun capabilities(f: Ctx): List<ApkSignal> = buildList {
        f.declared(SMS_READ).takeIf { it.isNotEmpty() }?.let { perms ->
            add(ApkSignal(
                "sms_read", ApkSeverity.REVIEW_CAREFULLY, ApkEvidence.OBSERVED,
                bi("Can read your text messages (SMS)", "आपके SMS संदेश पढ़ सकता है"),
                bi("It asks for permission to read or receive SMS. Banks send one-time passwords (OTPs) by SMS, so an app with this access could see them.",
                    "यह SMS पढ़ने या पाने की अनुमति माँगता है। बैंक OTP SMS से भेजते हैं, इसलिए यह अनुमति मिलने पर ऐप उन्हें देख सकता है।"),
                bi("Allow SMS access only to your main messaging app or an app you fully trust. Never share an OTP with anyone. You can switch it off in Settings > Apps > this app > Permissions.",
                    "SMS की अनुमति सिर्फ़ अपने मैसेज ऐप या पूरी तरह भरोसेमंद ऐप को दें। OTP किसी को न बताएँ। इसे सेटिंग्स > ऐप्स > यह ऐप > अनुमतियाँ में बंद कर सकते हैं।"),
                perms, allowedState(f, perms), BASIS_PLAY_PROTECT,
            ))
        }
        f.declared(SMS_SEND).takeIf { it.isNotEmpty() }?.let { perms ->
            add(ApkSignal(
                "sms_send", ApkSeverity.WORTH_KNOWING, ApkEvidence.OBSERVED,
                bi("Can send text messages (SMS)", "SMS भेज सकता है"),
                bi("It asks to send SMS from your number. SMS can cost money and can be used to register your number with a service.",
                    "यह आपके नंबर से SMS भेजने की अनुमति माँगता है। SMS में पैसे लग सकते हैं और इससे आपका नंबर किसी सेवा पर रजिस्टर किया जा सकता है।"),
                bi("Allow it only if you understand why the app must send SMS.", "अनुमति तभी दें जब समझ आए कि ऐप को SMS भेजने की ज़रूरत क्यों है।"),
                perms, allowedState(f, perms),
            ))
        }
        f.declared(CALL_LOG).takeIf { it.isNotEmpty() }?.let { perms ->
            add(ApkSignal(
                "call_log", ApkSeverity.WORTH_KNOWING, ApkEvidence.OBSERVED,
                bi("Can read your call history", "आपकी कॉल हिस्ट्री पढ़ सकता है"),
                bi("It asks for access to your call history (who called whom, and when) or to the numbers you dial.",
                    "यह आपकी कॉल हिस्ट्री (किसने किसे कब कॉल किया) या आपके डायल किए नंबरों तक पहुँच माँगता है।"),
                bi("Deny it unless this is a dialer or caller-ID app you chose.", "जब तक यह आपका चुना हुआ डायलर या कॉलर-ID ऐप न हो, अनुमति न दें।"),
                perms, allowedState(f, perms), BASIS_CERT_IN,
            ))
        }
        f.declared(PHONE).takeIf { it.isNotEmpty() }?.let { perms ->
            val parts = perms.map { phonePart(it) }
            add(ApkSignal(
                "phone", if (perms.any { it in PHONE_ACTIVE }) ApkSeverity.WORTH_KNOWING else ApkSeverity.INFO, ApkEvidence.OBSERVED,
                bi("Can use phone features", "फ़ोन की सुविधाएँ इस्तेमाल कर सकता है"),
                bi("It asks to: ${parts.joinToString("; ") { it.en }}.", "यह इनकी अनुमति माँगता है: ${parts.joinToString("; ") { it.hi }}।"),
                bi("Deny phone access if the app has no clear calling feature.", "अगर ऐप में कॉल से जुड़ा कोई साफ़ काम नहीं है, तो फ़ोन की अनुमति न दें।"),
                perms, allowedState(f, perms),
            ))
        }
        f.components(ServiceKind.ACCESSIBILITY).takeIf { it.isNotEmpty() }?.let { cs ->
            add(ApkSignal(
                "accessibility", ApkSeverity.REVIEW_CAREFULLY, ApkEvidence.OBSERVED,
                bi("Includes an accessibility service", "इसमें Accessibility सर्विस है"),
                bi("If you switch it on, the app can read what is on your screen and tap or type for you in other apps, including banking apps.",
                    "अगर आप इसे चालू करते हैं, तो ऐप आपकी स्क्रीन पर लिखी बातें पढ़ सकता है और दूसरे ऐप्स में, बैंकिंग ऐप्स समेत, आपकी जगह टैप या टाइप कर सकता है।"),
                bi("Switch it on only for an app you trust and only if you know why it needs it (for example, a screen reader). Check Settings > Accessibility to see what is switched on.",
                    "इसे सिर्फ़ भरोसेमंद ऐप के लिए और तभी चालू करें जब पता हो कि क्यों चाहिए (जैसे स्क्रीन रीडर)। सेटिंग्स > Accessibility में देखें कि क्या चालू है।"),
                cs.map { proof(it) }, switchState(cs), BASIS_PLAY_PROTECT,
            ))
        }
        f.components(ServiceKind.NOTIFICATION_LISTENER).takeIf { it.isNotEmpty() }?.let { cs ->
            add(ApkSignal(
                "notification_listener", ApkSeverity.REVIEW_CAREFULLY, ApkEvidence.OBSERVED,
                bi("Can ask to read all your notifications", "आपकी सभी नोटिफ़िकेशन पढ़ने की अनुमति माँग सकता है"),
                bi("If you allow notification access, the app can read every notification, including OTPs, bank alerts and chat previews.",
                    "नोटिफ़िकेशन ऐक्सेस देने पर ऐप हर नोटिफ़िकेशन पढ़ सकता है, जिसमें OTP, बैंक अलर्ट और चैट के मैसेज भी शामिल हैं।"),
                bi("Allow notification access only to apps you trust, such as a smartwatch app. Review it in Settings > Notifications > Notification access (the name varies by phone).",
                    "नोटिफ़िकेशन ऐक्सेस सिर्फ़ भरोसेमंद ऐप्स (जैसे स्मार्टवॉच ऐप) को दें। इसे सेटिंग्स > नोटिफ़िकेशन > नोटिफ़िकेशन ऐक्सेस में देखें (फ़ोन के हिसाब से नाम अलग हो सकता है)।"),
                cs.map { proof(it) }, switchState(cs), BASIS_SPYMAX,
            ))
        }
        f.components(ServiceKind.VPN).takeIf { it.isNotEmpty() }?.let { cs ->
            add(ApkSignal(
                "vpn", ApkSeverity.WORTH_KNOWING, ApkEvidence.OBSERVED,
                bi("Includes a VPN service", "इसमें VPN सर्विस है"),
                bi("If you accept its VPN request, the app can route your phone's internet traffic through itself.",
                    "अगर आप इसका VPN अनुरोध मानते हैं, तो ऐप आपके फ़ोन का इंटरनेट ट्रैफ़िक अपने ज़रिए भेज सकता है।"),
                bi("Accept a VPN request only from a VPN or privacy app you chose. A key icon in the status bar shows when a VPN is active.",
                    "VPN अनुरोध सिर्फ़ उसी VPN या प्राइवेसी ऐप का मानें जिसे आपने ख़ुद चुना हो। स्टेटस बार में चाबी का निशान बताता है कि VPN चालू है।"),
                cs.map { proof(it) }, switchState(cs), BASIS_CERT_IN,
            ))
        }
        f.components(ServiceKind.DEVICE_ADMIN).takeIf { it.isNotEmpty() }?.let { cs ->
            add(ApkSignal(
                "device_admin", ApkSeverity.REVIEW_CAREFULLY, ApkEvidence.OBSERVED,
                bi("Can ask to become a device admin", "डिवाइस एडमिन बनने की अनुमति माँग सकता है"),
                bi("Depending on what it declares, a device admin app can lock the screen, enforce screen-lock rules or erase the phone. It must be removed as an admin before you can uninstall it.",
                    "जो वह घोषित करता है उसके हिसाब से, डिवाइस एडमिन ऐप स्क्रीन लॉक कर सकता है, स्क्रीन-लॉक के नियम लागू कर सकता है या फ़ोन का डेटा मिटा सकता है। इसे अनइंस्टॉल करने से पहले एडमिन से हटाना पड़ता है।"),
                bi("Do not activate device admin unless your employer or school asked you to. Check Settings > Security > Device admin apps.",
                    "जब तक आपकी कंपनी या स्कूल ने न कहा हो, डिवाइस एडमिन चालू न करें। सेटिंग्स > सुरक्षा > डिवाइस एडमिन ऐप्स में जाँचें।"),
                cs.map { proof(it) }, switchState(cs),
            ))
        }
        f.components(ServiceKind.INPUT_METHOD).takeIf { it.isNotEmpty() }?.let { cs ->
            add(ApkSignal(
                "input_method", ApkSeverity.WORTH_KNOWING, ApkEvidence.OBSERVED,
                bi("Includes a keyboard", "इसमें कीबोर्ड है"),
                bi("If you choose it as your keyboard, it sees everything you type, including passwords and card numbers.",
                    "अगर आप इसे अपना कीबोर्ड बनाते हैं, तो यह आपकी टाइप की हुई हर बात देख सकता है, पासवर्ड और कार्ड नंबर भी।"),
                bi("Use a keyboard only from a developer you trust. Check Settings > System > Keyboard.",
                    "कीबोर्ड सिर्फ़ भरोसेमंद डेवलपर का इस्तेमाल करें। सेटिंग्स > सिस्टम > कीबोर्ड में जाँचें।"),
                cs.map { proof(it) }, switchState(cs),
            ))
        }
        f.components(ServiceKind.AUTOFILL).takeIf { it.isNotEmpty() }?.let { cs ->
            add(ApkSignal(
                "autofill", ApkSeverity.WORTH_KNOWING, ApkEvidence.OBSERVED,
                bi("Includes an autofill service", "इसमें ऑटोफ़िल सर्विस है"),
                bi("If you choose it as your autofill service, it can see and fill in saved passwords, addresses and card details in other apps.",
                    "अगर आप इसे ऑटोफ़िल सर्विस चुनते हैं, तो यह दूसरे ऐप्स में सेव पासवर्ड, पते और कार्ड की जानकारी देख और भर सकता है।"),
                bi("Choose only a password manager you trust. Check Settings > Passwords & accounts (the name varies by phone).",
                    "सिर्फ़ भरोसेमंद पासवर्ड मैनेजर चुनें। सेटिंग्स > पासवर्ड और अकाउंट में जाँचें (फ़ोन के हिसाब से नाम अलग हो सकता है)।"),
                cs.map { proof(it) }, switchState(cs),
            ))
        }
        if (f.has(OVERLAY)) add(ApkSignal(
            "overlay", ApkSeverity.WORTH_KNOWING, ApkEvidence.OBSERVED,
            bi("Can draw over other apps", "दूसरे ऐप्स के ऊपर दिख सकता है"),
            bi("With this special access, the app can show windows on top of other apps. Such a window could cover or imitate another app's screen.",
                "इस ख़ास अनुमति से ऐप दूसरे ऐप्स के ऊपर अपनी विंडो दिखा सकता है। ऐसी विंडो किसी दूसरे ऐप की स्क्रीन को ढक सकती है या उसकी नकल कर सकती है।"),
            bi("Allow \"Display over other apps\" only to apps that clearly need it, such as chat bubbles. You can turn it off in Settings > Apps > Special app access.",
                "\"दूसरे ऐप्स के ऊपर दिखाएँ\" की अनुमति सिर्फ़ उन्हीं ऐप्स को दें जिन्हें सच में ज़रूरत हो, जैसे चैट बबल। इसे सेटिंग्स > ऐप्स > ख़ास ऐप ऐक्सेस में बंद कर सकते हैं।"),
            listOf(OVERLAY),
        ))
        if (f.has(INSTALL_PACKAGES)) add(ApkSignal(
            "install_packages", ApkSeverity.WORTH_KNOWING, ApkEvidence.OBSERVED,
            bi("Can ask to install other apps", "दूसरे ऐप इंस्टॉल करने की अनुमति माँग सकता है"),
            bi("If you allow \"Install unknown apps\" for it, this app can offer to install further apps on your phone.",
                "अगर आप इसे \"अनजान ऐप इंस्टॉल करें\" की अनुमति देते हैं, तो यह आपके फ़ोन पर और ऐप इंस्टॉल करने को कह सकता है।"),
            bi("Keep \"Install unknown apps\" off for this app unless it is an app store you chose.",
                "जब तक यह आपका चुना हुआ ऐप स्टोर न हो, इसके लिए \"अनजान ऐप इंस्टॉल करें\" बंद रखें।"),
            listOf(INSTALL_PACKAGES), basis = BASIS_CERT_IN,
        ))
        f.declared(CONTACTS).takeIf { it.isNotEmpty() }?.let { perms ->
            add(ApkSignal(
                "contacts", ApkSeverity.WORTH_KNOWING, ApkEvidence.OBSERVED,
                bi("Can read your contacts", "आपके कॉन्टैक्ट पढ़ सकता है"),
                bi("It asks to read or change your contact list: the names and numbers of people you know.",
                    "यह आपकी कॉन्टैक्ट लिस्ट पढ़ने या बदलने की अनुमति माँगता है: आपके जान-पहचान वालों के नाम और नंबर।"),
                bi("Deny contacts unless the app's main job needs them, as a messaging app does.",
                    "जब तक ऐप के मुख्य काम के लिए ज़रूरी न हो (जैसे मैसेजिंग ऐप), कॉन्टैक्ट की अनुमति न दें।"),
                perms, allowedState(f, perms), BASIS_SPYLOAN,
            ))
        }
        if (f.has(FINE_LOCATION)) {
            val background = f.has(BACKGROUND_LOCATION)
            val perms = listOfNotNull(FINE_LOCATION, BACKGROUND_LOCATION.takeIf { background })
            add(ApkSignal(
                "precise_location", ApkSeverity.WORTH_KNOWING, ApkEvidence.OBSERVED,
                bi("Can ask for your precise location", "आपकी सटीक लोकेशन माँग सकता है"),
                bi("It asks for precise (GPS-level) location." + if (background) " It also asks for location while you are not using it." else "",
                    "यह सटीक (GPS जैसी) लोकेशन माँगता है।" + if (background) " यह ऐप इस्तेमाल न करते समय भी लोकेशन माँगता है।" else ""),
                bi("When Android asks, choose \"Only while using the app\" or \"Approximate\" unless precise location is essential.",
                    "Android पूछे तो \"सिर्फ़ ऐप इस्तेमाल करते समय\" या \"अनुमानित\" चुनें, जब तक सटीक लोकेशन ज़रूरी न हो।"),
                perms, allowedState(f, perms),
            ))
        }
        listOf(CAMERA, MICROPHONE).filter { f.has(it) }.takeIf { it.isNotEmpty() }?.let { perms ->
            val parts = perms.map { if (it == CAMERA) bi("camera", "कैमरा") else bi("microphone", "माइक्रोफ़ोन") }
            add(ApkSignal(
                "camera_microphone", ApkSeverity.WORTH_KNOWING, ApkEvidence.OBSERVED,
                bi("Can use the camera or microphone", "कैमरा या माइक्रोफ़ोन इस्तेमाल कर सकता है"),
                bi("It asks for: ${parts.joinToString(", ") { it.en }}.", "यह माँगता है: ${parts.joinToString(", ") { it.hi }}।"),
                bi("Choose \"Only this time\" or \"While using the app\". On Android 12 and later, a green dot shows while the camera or microphone is in use.",
                    "\"सिर्फ़ इस बार\" या \"ऐप इस्तेमाल करते समय\" चुनें। Android 12 और उसके बाद, कैमरा या माइक चालू होने पर हरा बिंदु दिखता है।"),
                perms, allowedState(f, perms),
            ))
        }
        if (f.has(ALL_FILES)) add(ApkSignal(
            "all_files", ApkSeverity.WORTH_KNOWING, ApkEvidence.OBSERVED,
            bi("Can ask for access to all files", "सभी फ़ाइलों का ऐक्सेस माँग सकता है"),
            bi("With \"All files access\", the app can read and change every file in your phone's shared storage, including photos, documents and downloads.",
                "\"सभी फ़ाइलों का ऐक्सेस\" मिलने पर ऐप आपके फ़ोन की शेयर्ड स्टोरेज की हर फ़ाइल पढ़ और बदल सकता है, जैसे फ़ोटो, डॉक्यूमेंट और डाउनलोड।"),
            bi("Allow it only to a file manager or backup app you chose.", "यह अनुमति सिर्फ़ अपने चुने हुए फ़ाइल मैनेजर या बैकअप ऐप को दें।"),
            listOf(ALL_FILES), basis = BASIS_SPYLOAN,
        ))
        if (f.has(QUERY_ALL_PACKAGES)) add(ApkSignal(
            "query_all_packages", ApkSeverity.INFO, ApkEvidence.OBSERVED,
            bi("Can see which apps you have installed", "देख सकता है कि आपके फ़ोन में कौन-कौन से ऐप हैं"),
            bi("It can list every installed app. That list can reveal, for example, which banking apps you use.",
                "यह फ़ोन में इंस्टॉल हर ऐप की सूची देख सकता है। इससे पता चल सकता है कि आप कौन-से बैंकिंग ऐप इस्तेमाल करते हैं।"),
            bi("There is no switch for this; Android grants it at install. Keep it in mind together with the other signals.",
                "इसके लिए कोई स्विच नहीं है; Android इसे इंस्टॉल के समय दे देता है। इसे बाकी संकेतों के साथ ध्यान में रखें।"),
            listOf(QUERY_ALL_PACKAGES),
        ))
    }

    private fun phonePart(perm: String): Bilingual = when (perm.removePrefix(P)) {
        "READ_PHONE_STATE" -> bi("see phone status and network details", "फ़ोन की स्थिति और नेटवर्क की जानकारी देखना")
        "READ_PHONE_NUMBERS" -> bi("read your phone number", "आपका फ़ोन नंबर पढ़ना")
        "CALL_PHONE" -> bi("make phone calls", "फ़ोन कॉल करना")
        "ANSWER_PHONE_CALLS" -> bi("answer incoming calls", "आने वाली कॉल उठाना")
        else -> bi(perm.removePrefix(P), perm.removePrefix(P) + " (फ़ोन)")
    }

    private fun kindLabel(kind: ServiceKind): Bilingual = when (kind) {
        ServiceKind.ACCESSIBILITY -> bi("Accessibility service", "Accessibility सर्विस")
        ServiceKind.NOTIFICATION_LISTENER -> bi("Notification listener", "नोटिफ़िकेशन पढ़ने वाली सर्विस")
        ServiceKind.VPN -> bi("VPN service", "VPN सर्विस")
        ServiceKind.DEVICE_ADMIN -> bi("Device admin receiver", "डिवाइस एडमिन")
        ServiceKind.INPUT_METHOD -> bi("Keyboard (input method)", "कीबोर्ड")
        ServiceKind.AUTOFILL -> bi("Autofill service", "ऑटोफ़िल सर्विस")
    }

    private fun proof(c: DeclaredComponent) = "${kindLabel(c.kind).en}: ${c.className}"

    /** Runtime permission state, only when Android reported grants (an installed app). */
    private fun allowedState(f: Ctx, perms: List<String>): Bilingual? {
        val granted = f.facts.grantedPermissions ?: return null
        val allowed = perms.filter { it in granted }
        return when {
            allowed.isEmpty() -> bi("Not allowed now", "अभी अनुमति नहीं है")
            allowed.size == perms.size -> bi("Allowed now", "अभी अनुमति है")
            else -> {
                val names = allowed.joinToString(", ") { it.removePrefix(P) }
                bi("Partly allowed now: $names", "कुछ अनुमतियाँ अभी हैं: $names")
            }
        }
    }

    /** Service state, only when Android reported it for every declared component of that kind (or any is on). */
    private fun switchState(cs: List<DeclaredComponent>): Bilingual? = when {
        cs.any { it.enabled == true } -> bi("Switched on now", "अभी चालू है")
        cs.all { it.enabled == false } -> bi("Not switched on now", "अभी चालू नहीं है")
        else -> null
    }

    // --- install source -----------------------------------------------------------------------------------------------

    private fun installSource(f: Ctx): ApkSignal {
        val o = f.facts.origin
        val pkg = o.installerPackage
        val installerProof = listOfNotNull(pkg?.let { "Installer: $it" })
        return when (o.source) {
            InstallSource.PLAY_STORE -> ApkSignal(
                "install_source", ApkSeverity.INFO, ApkEvidence.OBSERVED,
                bi("Installed by Google Play", "Google Play से इंस्टॉल हुआ"),
                bi("Android reports Google Play as the installer. Google Play reviews apps, but being on a store is not a guarantee.",
                    "Android के अनुसार इसे Google Play ने इंस्टॉल किया। Google Play ऐप्स की जाँच करता है, पर स्टोर पर होना कोई गारंटी नहीं है।"),
                bi("Keep Google Play Protect on and keep the app updated.", "Google Play Protect चालू रखें और ऐप अपडेट रखें।"),
                installerProof,
            )
            InstallSource.OTHER_STORE -> {
                val store = o.storeName ?: pkg ?: "another store"
                ApkSignal(
                    "install_source", ApkSeverity.INFO, ApkEvidence.OBSERVED,
                    bi("Installed from another app store: $store", "दूसरे ऐप स्टोर से इंस्टॉल हुआ: $store"),
                    bi("Android reports $store${pkg?.let { " ($it)" }.orEmpty()} as the installer. Each store reviews apps in its own way; being on a store is not a guarantee.",
                        "Android के अनुसार इसे $store${pkg?.let { " ($it)" }.orEmpty()} ने इंस्टॉल किया। हर स्टोर अपने तरीक़े से ऐप्स की जाँच करता है; स्टोर पर होना कोई गारंटी नहीं है।"),
                    bi("Check that the publisher shown in the store is the company you expect.", "देखें कि स्टोर में दिखाया गया प्रकाशक वही कंपनी है जिसकी आप उम्मीद करते हैं।"),
                    installerProof,
                )
            }
            InstallSource.FILE -> ApkSignal(
                "install_source", ApkSeverity.WORTH_KNOWING, ApkEvidence.OBSERVED,
                bi("Installed from a file, not from an app store", "फ़ाइल से इंस्टॉल हुआ, ऐप स्टोर से नहीं"),
                bi("Android reports that this app was installed from an APK file. Apps installed from files, for example ones shared on WhatsApp or websites, skip app-store review.",
                    "Android के अनुसार यह ऐप एक APK फ़ाइल से इंस्टॉल हुआ। फ़ाइल से इंस्टॉल होने वाले ऐप, जैसे WhatsApp या वेबसाइट से मिले ऐप, ऐप स्टोर की जाँच से नहीं गुज़रते।"),
                bi("If someone sent you this app, check with them and with the official website. Get banking and government apps from Google Play or the official website.",
                    "अगर किसी ने आपको यह ऐप भेजा है, तो उनसे और आधिकारिक वेबसाइट से पुष्टि करें। बैंक और सरकारी ऐप Google Play या आधिकारिक वेबसाइट से ही लें।"),
                installerProof, basis = BASIS_FILES,
            )
            InstallSource.OTHER_APP -> {
                val who = pkg ?: "another app"
                ApkSignal(
                    "install_source", ApkSeverity.WORTH_KNOWING, ApkEvidence.OBSERVED,
                    bi("Installed by another app: $who", "किसी दूसरे ऐप ने इंस्टॉल किया: $who"),
                    bi("Android reports $who as the installer. It is not a known app store or Android's own installer.",
                        "Android के अनुसार इसे $who ने इंस्टॉल किया। यह कोई जाना-पहचाना ऐप स्टोर या Android का अपना इंस्टॉलर नहीं है।"),
                    bi("Check what $who is. If you did not knowingly install this app, open its Android settings and consider uninstalling it.",
                        "देखें कि $who क्या है। अगर आपने यह ऐप जान-बूझकर इंस्टॉल नहीं किया, तो इसकी Android सेटिंग खोलें और इसे हटाने पर विचार करें।"),
                    installerProof, basis = BASIS_CERT_IN,
                )
            }
            InstallSource.PREINSTALLED -> ApkSignal(
                "install_source", ApkSeverity.INFO, ApkEvidence.OBSERVED,
                bi("Came with the phone", "फ़ोन के साथ आया"),
                bi("Android marks this as a system app that is part of the phone's software.", "Android इसे सिस्टम ऐप बताता है, जो फ़ोन के सॉफ़्टवेयर का हिस्सा है।"),
                bi("System apps are updated by the phone maker or Google Play. You can still review its permissions in Settings.",
                    "सिस्टम ऐप फ़ोन कंपनी या Google Play से अपडेट होते हैं। फिर भी आप सेटिंग्स में इसकी अनुमतियाँ देख सकते हैं।"),
                installerProof,
            )
            InstallSource.UNKNOWN -> ApkSignal(
                "install_source", ApkSeverity.INFO, ApkEvidence.NOT_ESTABLISHED,
                bi("Install source not reported", "कहाँ से इंस्टॉल हुआ, पता नहीं"),
                bi("Android did not report which app installed this one. Apps installed from a computer with developer tools often show no installer.",
                    "Android ने नहीं बताया कि यह ऐप किसने इंस्टॉल किया। कंप्यूटर से डेवलपर टूल द्वारा इंस्टॉल किए गए ऐप में अक्सर इंस्टॉलर नहीं दिखता।"),
                bi("If you do not recognise this app, review it in Android app settings.", "अगर आप इस ऐप को नहीं पहचानते, तो Android ऐप सेटिंग में इसे देखें।"),
                installerProof,
            )
            InstallSource.NOT_INSTALLED -> ApkSignal(
                "install_source", ApkSeverity.WORTH_KNOWING, ApkEvidence.OBSERVED,
                bi("This is an APK file, not an app-store install", "यह APK फ़ाइल है, ऐप स्टोर से नहीं"),
                bi("Installing from a file skips app-store review. Nothing has been installed: TraceVeil only read the file.",
                    "फ़ाइल से इंस्टॉल करने पर ऐप स्टोर की जाँच नहीं होती। कुछ भी इंस्टॉल नहीं हुआ है: TraceVeil ने सिर्फ़ फ़ाइल पढ़ी है।"),
                bi("Prefer Google Play or the official website. Do not install app files sent by unknown numbers, even if they look official.",
                    "Google Play या आधिकारिक वेबसाइट से लेना बेहतर है। अनजान नंबरों से आई ऐप फ़ाइलें इंस्टॉल न करें, चाहे वे आधिकारिक दिखें।"),
                emptyList(), basis = BASIS_FILES,
            )
        }
    }

    // --- combinations ---------------------------------------------------------------------------------------------------

    private fun sourcePhrase(f: Ctx) = if (f.isFile) bi("This APK file is not from Google Play", "यह APK फ़ाइल Google Play से नहीं है")
    else bi("Android does not report Google Play as this app's source", "Android के अनुसार यह ऐप Google Play से नहीं आया")

    private val otpAdvice = bi(
        "Do not switch on these accesses unless you are sure the app is genuine. If you already did, switch them off in Settings. If you shared an OTP or lost money, call your bank; in India you can also call 1930 or report at cybercrime.gov.in.",
        "जब तक ऐप के असली होने का पूरा भरोसा न हो, ये अनुमतियाँ चालू न करें। अगर चालू कर दी हैं, तो सेटिंग्स में बंद करें। अगर OTP बताया है या पैसे कटे हैं, तो अपने बैंक को कॉल करें; भारत में 1930 पर कॉल करें या cybercrime.gov.in पर शिकायत करें।",
    )

    private fun combinations(f: Ctx): List<ApkSignal> = buildList {
        if (!f.offPlay) return@buildList
        val src = sourcePhrase(f)
        val sms = f.declared(SMS_READ)
        val listeners = f.components(ServiceKind.NOTIFICATION_LISTENER)
        val accessibility = f.components(ServiceKind.ACCESSIBILITY)
        val admins = f.components(ServiceKind.DEVICE_ADMIN)
        val overlay = f.has(OVERLAY)

        val otp = listOfNotNull(bi("to read SMS", "SMS पढ़ने की अनुमति").takeIf { sms.isNotEmpty() }, bi("to read notifications", "नोटिफ़िकेशन पढ़ने की अनुमति").takeIf { listeners.isNotEmpty() })
        val control = listOfNotNull(bi("an accessibility service", "Accessibility सर्विस").takeIf { accessibility.isNotEmpty() }, bi("drawing over other apps", "दूसरे ऐप्स के ऊपर दिखने की अनुमति").takeIf { overlay })
        if (otp.isNotEmpty() && control.isNotEmpty()) add(ApkSignal(
            "combo_otp_screen_control", ApkSeverity.REVIEW_CAREFULLY, ApkEvidence.OBSERVED,
            bi("Combination: can see OTPs and act on or cover your screen", "मेल: OTP देख सकता है और स्क्रीन पर काम कर सकता है या उसे ढक सकता है"),
            bi("${src.en}, and it asks ${otp.joinToString(" and ") { it.en }} together with ${control.joinToString(" and ") { it.en }}. Together, these could let an app read a one-time password and act on or cover your screen. Public cyber-safety advisories about fake bank and challan apps describe this combination. This does not prove this app does so.",
                "${src.hi}, और यह ${otp.joinToString(" और ") { it.hi }} के साथ ${control.joinToString(" और ") { it.hi }} माँगता है। ये मिलकर किसी ऐप को OTP पढ़ने और आपकी स्क्रीन पर काम करने या उसे ढकने दे सकते हैं। नकली बैंक और चालान ऐप्स पर सरकारी साइबर-सुरक्षा चेतावनियों में यही मेल बताया गया है। इससे यह साबित नहीं होता कि यह ऐप ऐसा करता है।"),
            otpAdvice,
            sms + listeners.map { proof(it) } + accessibility.map { proof(it) } + listOfNotNull(OVERLAY.takeIf { overlay }),
            basis = "$BASIS_PLAY_PROTECT $BASIS_CERT_IN",
        ))

        if (sms.isNotEmpty() && listeners.isNotEmpty()) add(ApkSignal(
            "combo_sms_and_notifications", ApkSeverity.REVIEW_CAREFULLY, ApkEvidence.OBSERVED,
            bi("Combination: two ways to see OTPs", "मेल: OTP देखने के दो रास्ते"),
            bi("${src.en}, and it asks both to read SMS and to read notifications. Either one can expose one-time passwords; together they cover most ways an OTP arrives. This does not prove the app reads them.",
                "${src.hi}, और यह SMS पढ़ने और नोटिफ़िकेशन पढ़ने, दोनों की अनुमति माँगता है। दोनों में से कोई भी OTP दिखा सकता है; साथ में ये OTP आने के ज़्यादातर रास्ते ढक लेते हैं। इससे यह साबित नहीं होता कि ऐप उन्हें पढ़ता है।"),
            otpAdvice,
            sms + listeners.map { proof(it) },
            basis = "$BASIS_PLAY_PROTECT $BASIS_SPYMAX",
        ))

        val takeover = listOfNotNull(bi("an accessibility service", "Accessibility सर्विस").takeIf { accessibility.isNotEmpty() }, bi("device admin", "डिवाइस एडमिन").takeIf { admins.isNotEmpty() })
        if (f.has(INSTALL_PACKAGES) && takeover.isNotEmpty()) add(ApkSignal(
            "combo_installer_control", ApkSeverity.REVIEW_CAREFULLY, ApkEvidence.OBSERVED,
            bi("Combination: can install other apps and control the phone", "मेल: दूसरे ऐप इंस्टॉल कर सकता है और फ़ोन नियंत्रित कर सकता है"),
            bi("${src.en}, and it asks to install other apps together with ${takeover.joinToString(" and ") { it.en }}. CERT-In has described fake challan apps that used a first app to install a second one. This does not prove this app does so.",
                "${src.hi}, और यह दूसरे ऐप इंस्टॉल करने की अनुमति के साथ ${takeover.joinToString(" और ") { it.hi }} माँगता है। CERT-In ने ऐसे नकली चालान ऐप्स के बारे में बताया है जिन्होंने पहले ऐप से दूसरा ऐप इंस्टॉल कराया। इससे यह साबित नहीं होता कि यह ऐप ऐसा करता है।"),
            bi("Keep \"Install unknown apps\" off for this app, and do not switch on accessibility or device admin for it unless you are sure it is genuine.",
                "इस ऐप के लिए \"अनजान ऐप इंस्टॉल करें\" बंद रखें, और जब तक इसके असली होने का पूरा भरोसा न हो, इसके लिए Accessibility या डिवाइस एडमिन चालू न करें।"),
            listOf(INSTALL_PACKAGES) + accessibility.map { proof(it) } + admins.map { proof(it) },
            basis = BASIS_CERT_IN,
        ))

        val contacts = f.declared(CONTACTS)
        val files = f.declared(PHOTOS_AND_FILES)
        if (contacts.isNotEmpty() && files.isNotEmpty()) {
            val what = if (ALL_FILES in files) bi("access to all files", "सभी फ़ाइलों का ऐक्सेस") else bi("your photos and media", "आपकी फ़ोटो और मीडिया")
            add(ApkSignal(
                "combo_contacts_files", ApkSeverity.WORTH_KNOWING, ApkEvidence.OBSERVED,
                bi("Combination: your contacts and your photos or files", "मेल: आपके कॉन्टैक्ट और आपकी फ़ोटो या फ़ाइलें"),
                bi("${src.en}, and it asks for your contacts together with ${what.en}. Research on predatory loan apps describes misuse of exactly this kind of access. This does not prove this app does so.",
                    "${src.hi}, और यह आपके कॉन्टैक्ट के साथ ${what.hi} माँगता है। लोन ऐप्स पर हुई रिसर्च में ठीक इसी तरह की अनुमतियों के ग़लत इस्तेमाल का ज़िक्र है। इससे यह साबित नहीं होता कि यह ऐप ऐसा करता है।"),
                bi("Deny contacts and file access unless the app's main job needs them. Do not let anyone pressure you into granting access for a loan.",
                    "जब तक ऐप के मुख्य काम के लिए ज़रूरी न हो, कॉन्टैक्ट और फ़ाइल की अनुमति न दें। लोन के लिए किसी के दबाव में अनुमति न दें।"),
                contacts + files,
                basis = BASIS_SPYLOAN,
            ))
        }
    }

    // --- name resemblance (INFERRED) ----------------------------------------------------------------------------------

    private fun nameResemblance(f: Ctx): ApkSignal? {
        if (!f.offPlay) return null
        val m = ServiceNameHeuristic.match(f.facts.label, f.facts.packageName) ?: return null
        val kind = when (m.category) {
            ServiceCategory.BANK_OR_PAYMENT -> bi("a bank or payment service", "किसी बैंक या पेमेंट सेवा")
            ServiceCategory.GOVERNMENT -> bi("a government service", "किसी सरकारी सेवा")
        }
        return ApkSignal(
            "name_resembles_service", ApkSeverity.WORTH_KNOWING, ApkEvidence.INFERRED,
            bi("The name looks like ${kind.en}", "नाम ${kind.hi} जैसा लगता है"),
            bi("Its name or package contains \"${m.keyword}\". TraceVeil cannot tell whether this is the official app; it only noticed the name, and it is not from Google Play.",
                "इसके नाम या पैकेज में \"${m.keyword}\" है। TraceVeil यह नहीं बता सकता कि यह आधिकारिक ऐप है या नहीं; इसने सिर्फ़ नाम देखा है, और यह Google Play से नहीं है।"),
            bi("Verify it on the official website of the bank or department, or install the app from Google Play. Be careful with app files sent by message, even if they look official.",
                "बैंक या विभाग की आधिकारिक वेबसाइट पर इसकी पुष्टि करें, या ऐप Google Play से इंस्टॉल करें। मैसेज में आई ऐप फ़ाइलों से सावधान रहें, चाहे वे आधिकारिक दिखें।"),
            listOf("Matched word: ${m.keyword}"),
            basis = "Name-similarity heuristic. $BASIS_FILES",
        )
    }

    // --- identity, signer and package facts ------------------------------------------------------------------------------

    private fun identity(f: Ctx): List<ApkSignal> = buildList {
        val facts = f.facts
        add(ApkSignal(
            "official_identity", ApkSeverity.INFO, ApkEvidence.NOT_ESTABLISHED,
            bi("Whether this is the official app", "क्या यह आधिकारिक ऐप है"),
            bi("Not established. TraceVeil has no list of official publishers' signing certificates, so it cannot confirm who made this app. A familiar name or icon is not proof.",
                "पता नहीं। TraceVeil के पास आधिकारिक प्रकाशकों के साइनिंग सर्टिफ़िकेट की सूची नहीं है, इसलिए यह पक्का नहीं कर सकता कि यह ऐप किसने बनाया। जाना-पहचाना नाम या आइकन सबूत नहीं है।"),
            bi("Find the app through the company's official website or Google Play and compare the publisher. The certificate fingerprint above lets someone technical compare two copies.",
                "कंपनी की आधिकारिक वेबसाइट या Google Play से ऐप ढूँढें और प्रकाशक मिलाएँ। ऊपर दिया सर्टिफ़िकेट फ़िंगरप्रिंट किसी जानकार को दो कॉपी मिलाने में मदद करता है।"),
        ))
        add(ApkSignal(
            "behaviour", ApkSeverity.INFO, ApkEvidence.NOT_ESTABLISHED,
            bi("What the app actually does", "ऐप असल में क्या करता है"),
            bi("Not established. This check reads what the app asks for, not what it does. A permission is a capability, not proof of misuse.",
                "पता नहीं। यह जाँच सिर्फ़ यह पढ़ती है कि ऐप क्या माँगता है, यह नहीं कि वह क्या करता है। अनुमति एक क्षमता है, ग़लत इस्तेमाल का सबूत नहीं।"),
            if (f.isFile) bi("TraceVeil can audit an app only after it is installed, and it never installs apps itself.",
                "TraceVeil किसी ऐप का ऑडिट उसके इंस्टॉल होने के बाद ही कर सकता है, और यह ख़ुद कभी ऐप इंस्टॉल नहीं करता।")
            else bi("Run a TraceVeil privacy audit to observe which internet hosts it looks up while you use it.",
                "इस्तेमाल के दौरान यह किन इंटरनेट होस्ट को खोजता है, यह देखने के लिए TraceVeil प्राइवेसी ऑडिट चलाएँ।"),
        ))

        facts.installedTwin?.let { twin ->
            val label = twin.label
            val mine = facts.certSha256.map { it.uppercase() }.toSet()
            val theirs = twin.certSha256.map { it.uppercase() }.toSet()
            add(when {
                mine.isEmpty() || theirs.isEmpty() -> ApkSignal(
                    "same_package_installed", ApkSeverity.INFO, ApkEvidence.NOT_ESTABLISHED,
                    bi("Compared with your installed \"$label\"", "आपके इंस्टॉल \"$label\" से तुलना"),
                    bi("An app with the same package name is already installed, but a signing certificate could not be read on one side, so whether they share a signer is not established.",
                        "इसी पैकेज नाम का ऐप पहले से इंस्टॉल है, पर एक तरफ़ का साइनिंग सर्टिफ़िकेट पढ़ा नहीं जा सका, इसलिए दोनों का साइनर एक है या नहीं, पता नहीं।"),
                    bi("Update the app from where you first installed it.", "ऐप को वहीं से अपडेट करें जहाँ से पहली बार इंस्टॉल किया था।"),
                    listOf("Package: ${facts.packageName}"),
                )
                mine == theirs -> ApkSignal(
                    "same_package_installed", ApkSeverity.INFO, ApkEvidence.OBSERVED,
                    bi("Same signer as your installed \"$label\"", "आपके इंस्टॉल \"$label\" जैसा ही सर्टिफ़िकेट"),
                    bi("An app with the same package name is already installed and signed with the same certificate. Installing this file would update it.",
                        "इसी पैकेज नाम का ऐप पहले से इंस्टॉल है और उसी सर्टिफ़िकेट से साइन है। यह फ़ाइल इंस्टॉल करने पर वह अपडेट हो जाएगा।"),
                    bi("A matching certificate shows the same signing key, not that this update is trustworthy. Prefer updates from the store you installed it from.",
                        "एक जैसा सर्टिफ़िकेट सिर्फ़ एक ही साइनिंग चाबी दिखाता है, यह नहीं कि यह अपडेट भरोसेमंद है। अपडेट उसी स्टोर से लें जहाँ से इंस्टॉल किया था।"),
                    listOf("Package: ${facts.packageName}"),
                )
                else -> ApkSignal(
                    "same_package_installed", ApkSeverity.REVIEW_CAREFULLY, ApkEvidence.OBSERVED,
                    bi("Signed differently from your installed \"$label\"", "आपके इंस्टॉल \"$label\" से अलग सर्टिफ़िकेट"),
                    bi("An app with the same package name is already installed, but this file is signed with a different certificate, so it was not signed with the same key as your installed app. Android will not install it as an update.",
                        "इसी पैकेज नाम का ऐप पहले से इंस्टॉल है, पर यह फ़ाइल किसी दूसरे सर्टिफ़िकेट से साइन है, यानी इसे आपके इंस्टॉल ऐप वाली चाबी से साइन नहीं किया गया। Android इसे अपडेट के रूप में इंस्टॉल नहीं करेगा।"),
                    bi("Do not uninstall your existing app to make room for this file. Update the app from where you first installed it.",
                        "इस फ़ाइल के लिए अपना मौजूदा ऐप न हटाएँ। ऐप को वहीं से अपडेट करें जहाँ से पहली बार इंस्टॉल किया था।"),
                    listOf("Package: ${facts.packageName}"),
                )
            })
        }

        if (facts.signerSubjects.any { it.contains("CN=Android Debug", ignoreCase = true) }) add(ApkSignal(
            "debug_certificate", ApkSeverity.WORTH_KNOWING, ApkEvidence.OBSERVED,
            bi("Signed with a development certificate", "डेवलपमेंट सर्टिफ़िकेट से साइन किया गया"),
            bi("The signing certificate is Android's standard debug certificate, which is meant for testing. Apps released by companies are normally signed with their own release key.",
                "साइनिंग सर्टिफ़िकेट Android का सामान्य डीबग सर्टिफ़िकेट है, जो टेस्टिंग के लिए होता है। कंपनियाँ अपने ऐप आम तौर पर अपनी रिलीज़ चाबी से साइन करती हैं।"),
            bi("Expect this only for test builds that you or your team made.", "ऐसा सिर्फ़ उन टेस्ट बिल्ड में होना चाहिए जो आपने या आपकी टीम ने बनाए हों।"),
            facts.signerSubjects,
        ))
        if (facts.certSha256.isEmpty()) add(ApkSignal(
            "signer_unknown", ApkSeverity.INFO, ApkEvidence.NOT_ESTABLISHED,
            bi("Signing certificate could not be read", "साइनिंग सर्टिफ़िकेट पढ़ा नहीं जा सका"),
            bi("Android did not return a signing certificate, so who signed this package is not established.",
                "Android ने साइनिंग सर्टिफ़िकेट नहीं दिया, इसलिए इस पैकेज को किसने साइन किया, पता नहीं।"),
            bi("Get the app from the official source instead of relying on this copy.", "इस कॉपी पर निर्भर रहने के बजाय ऐप आधिकारिक स्रोत से लें।"),
        ))

        if (facts.hasLauncherIcon == false && facts.origin.source != InstallSource.PREINSTALLED && !f.isFile) add(ApkSignal(
            "no_launcher_icon", ApkSeverity.WORTH_KNOWING, ApkEvidence.OBSERVED,
            bi("Has no screen to open from your app list", "ऐप लिस्ट से खोलने के लिए इसकी कोई स्क्रीन नहीं"),
            bi("This app declares no launcher screen. Depending on the phone, your app list shows no icon for it or a placeholder that only opens app info. Keyboards, plug-ins and companion apps often work this way, but an app you did not expect is easy to miss.",
                "यह ऐप कोई लॉन्चर स्क्रीन घोषित नहीं करता। फ़ोन के हिसाब से, ऐप लिस्ट में इसका आइकन नहीं दिखता या ऐसा आइकन दिखता है जो सिर्फ़ ऐप की जानकारी खोलता है। कीबोर्ड, प्लग-इन और साथी ऐप अक्सर ऐसे ही होते हैं, पर जिस ऐप की आपको उम्मीद नहीं थी, वह आसानी से छूट सकता है।"),
            bi("If you do not recognise it, open its Android app settings and uninstall it.", "अगर आप इसे नहीं पहचानते, तो इसकी Android ऐप सेटिंग खोलकर इसे अनइंस्टॉल करें।"),
        ))

        facts.targetSdk?.let { sdk ->
            val advice = bi("Prefer an up-to-date version from the official source.", "आधिकारिक स्रोत से नया वर्ज़न लेना बेहतर है।")
            when {
                sdk < 23 -> add(ApkSignal(
                    "old_target_sdk", ApkSeverity.WORTH_KNOWING, ApkEvidence.OBSERVED,
                    bi("Built for a very old Android version (API $sdk)", "बहुत पुराने Android वर्ज़न के लिए बना (API $sdk)"),
                    bi("Apps built for Android 5.1 or older use the old permission model, and Android 14 and later refuse to install them.",
                        "Android 5.1 या उससे पुराने के लिए बने ऐप पुराने अनुमति तरीक़े का इस्तेमाल करते हैं, और Android 14 व उसके बाद के वर्ज़न इन्हें इंस्टॉल नहीं होने देते।"),
                    advice, listOf("targetSdkVersion $sdk"),
                ))
                sdk < 29 -> add(ApkSignal(
                    "old_target_sdk", ApkSeverity.INFO, ApkEvidence.OBSERVED,
                    bi("Built for an older Android version (API $sdk)", "पुराने Android वर्ज़न के लिए बना (API $sdk)"),
                    bi("Some newer Android privacy protections apply fully only to apps built for newer versions.",
                        "Android की कुछ नई प्राइवेसी सुरक्षाएँ पूरी तरह सिर्फ़ नए वर्ज़न के लिए बने ऐप्स पर लागू होती हैं।"),
                    advice, listOf("targetSdkVersion $sdk"),
                ))
            }
        }
    }

    // --- summary ----------------------------------------------------------------------------------------------------------

    val DISCLAIMER = bi(
        "APK Guard explains what an app can do, using only what Android shows about its package. It is not an antivirus and cannot tell whether an app is harmful. Keep Google Play Protect switched on.",
        "APK Guard सिर्फ़ उसी जानकारी से बताता है कि ऐप क्या-क्या कर सकता है, जो Android उसके पैकेज के बारे में दिखाता है। यह एंटीवायरस नहीं है और यह नहीं बता सकता कि ऐप नुकसान करेगा या नहीं। Google Play Protect चालू रखें।",
    )

    fun summary(signals: List<ApkSignal>): ApkSummary {
        val review = signals.count { it.severity == ApkSeverity.REVIEW_CAREFULLY }
        val worth = signals.count { it.severity == ApkSeverity.WORTH_KNOWING }
        val headline = when {
            review > 0 -> bi(
                "$review ${if (review == 1) "thing" else "things"} to review carefully",
                "ध्यान से जाँचने लायक $review ${if (review == 1) "बात" else "बातें"}",
            )
            worth > 0 -> bi(
                "Nothing to review carefully; $worth ${if (worth == 1) "thing" else "things"} worth knowing. This is not a safety guarantee.",
                "ध्यान से जाँचने लायक कुछ नहीं मिला; $worth ${if (worth == 1) "बात जानने लायक है" else "बातें जानने लायक हैं"}। यह सुरक्षा की गारंटी नहीं है।",
            )
            else -> bi(
                "Nothing unusual in what Android shows us — this is not a safety guarantee.",
                "Android जो दिखाता है उसमें कुछ असामान्य नहीं मिला — यह सुरक्षा की गारंटी नहीं है।",
            )
        }
        return ApkSummary(review, worth, headline, DISCLAIMER)
    }
}
