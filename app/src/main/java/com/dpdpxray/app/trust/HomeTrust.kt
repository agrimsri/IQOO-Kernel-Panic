package com.dpdpxray.app.trust

import com.dpdpxray.app.ui.guide.Bi

/**
 * One statement on the Home trust card: [short] for the folded chip, [title] and [detail] when open. [holds] false marks
 * an exception on this install that the person should know about.
 */
data class TrustFact(val short: Bi, val title: Bi, val detail: Bi, val holds: Boolean = true)

/** One permission or special access a person can switch on or off. [on] null = Android did not report it. */
data class PermissionState(val label: Bi, val on: Boolean?)

data class PermissionSummary(val items: List<PermissionState>) {
    val on: Int get() = items.count { it.on == true }
    val total: Int get() = items.size
    val unknown: Int get() = items.count { it.on == null }
    val line: Bi get() = HomeTrust.permissionsLine(on, total, unknown)
}

/**
 * The compact "Why you can trust TraceVeil" card on both Home screens. Every fact is a short restatement of something
 * HomeTrustTest checks against the code (the per-app VPN builder, the watcher's early return, private storage and the
 * manifest's backup flag, the network-code scan). A fact that does not hold on this install is replaced by the exception,
 * never shown as true. Nothing here reads the phone; the card passes in what it read.
 */
object HomeTrust {
    val title = Bi(TrustText.TITLE, "TraceVeil पर भरोसा क्यों करें")
    val seeWhy = Bi("see why", "क्यों, देखें")
    val open = Bi("Open the full trust page", "पूरा भरोसा पेज खोलें")
    val more = Bi("What each one means", "हर बात का मतलब")
    val less = Bi("Hide", "छिपाएँ")

    private val account = TrustFact(
        Bi("No account", "कोई अकाउंट नहीं"),
        Bi("No account, no TraceVeil server", "कोई अकाउंट नहीं, TraceVeil का कोई सर्वर नहीं"),
        Bi(
            "There is no sign-in and no server of TraceVeil's own, and the app contains no analytics, advertising or crash-reporting library. " +
                "A website check you start opens only the site you type, in TraceVeil's own browser.",
            "कोई साइन-इन नहीं, TraceVeil का अपना कोई सर्वर नहीं, और ऐप में कोई एनालिटिक्स, विज्ञापन या क्रैश-रिपोर्टिंग लाइब्रेरी नहीं है। " +
                "आपकी शुरू की हुई वेबसाइट जाँच सिर्फ़ आपकी लिखी साइट को TraceVeil के अपने ब्राउज़र में खोलती है।",
        ),
    )
    private val vpn = TrustFact(
        Bi("VPN: only the checked app", "VPN: सिर्फ़ जाँचा गया ऐप"),
        Bi("VPN sees only the app you check", "VPN सिर्फ़ उसी ऐप को देखता है जिसकी आप जाँच करते हैं"),
        Bi(
            "During a check, Android sends only that app's DNS lookups (and Google Play services', if you include them) through TraceVeil's local VPN. Nothing is decrypted. A quarantine run cuts only that app's internet.",
            "जाँच के दौरान Android सिर्फ़ उसी ऐप के DNS लुकअप (और अगर आप शामिल करें तो Google Play services के) TraceVeil के लोकल VPN से भेजता है। कुछ भी डिक्रिप्ट नहीं होता। क्वारंटीन रन सिर्फ़ उसी ऐप का इंटरनेट काटता है।",
        ),
    )
    private val screen = TrustFact(
        Bi("Screen: only during checks", "स्क्रीन: सिर्फ़ जाँच के दौरान"),
        Bi("Reads the screen only during a check", "स्क्रीन सिर्फ़ जाँच के दौरान पढ़ता है"),
        Bi(
            "Android sends the consent watcher events from every app while it is switched on. TraceVeil ignores them unless a check or quarantine run you started is going, and then reads only the app you chose and Android's pop-ups over it.",
            "चालू होने पर Android हर ऐप की घटनाएँ कंसेंट वॉचर को भेजता है। TraceVeil उन्हें तब तक अनदेखा करता है जब तक आपकी शुरू की हुई जाँच या क्वारंटीन रन न चल रही हो, और तब सिर्फ़ आपका चुना ऐप और उसके ऊपर Android के पॉप-अप पढ़ता है।",
        ),
    )
    private val stays = TrustFact(
        Bi("Results stay here", "नतीजे यहीं रहते हैं"),
        Bi("Your results stay on this phone", "आपके नतीजे इसी फ़ोन पर रहते हैं"),
        Bi(
            "Checks and screenshots are saved in TraceVeil's private storage, and Android's cloud backup is off for TraceVeil. They leave the phone only when you share, save or back them up yourself.",
            "जाँच और स्क्रीनशॉट TraceVeil की निजी स्टोरेज में सेव होते हैं, और TraceVeil के लिए Android का क्लाउड बैकअप बंद है। ये फ़ोन से तभी बाहर जाते हैं जब आप खुद शेयर, सेव या बैकअप करें।",
        ),
    )
    private val voiceOn = TrustFact(
        Bi("Online voice on", "ऑनलाइन आवाज़ चालू"),
        Bi("Online voice is on for this phone", "इस फ़ोन पर ऑनलाइन आवाज़ चालू है"),
        Bi(
            "Each spoken answer's text (not your audio) is sent to Sarvam AI to make speech. Everything else stays on this phone.",
            "हर बोले गए जवाब का टेक्स्ट (आपकी आवाज़ नहीं) आवाज़ बनाने के लिए Sarvam AI को भेजा जाता है। बाकी सब इसी फ़ोन पर रहता है।",
        ),
        holds = false,
    )
    private val backupOn = TrustFact(
        Bi("Android backup on", "Android बैकअप चालू"),
        Bi("Android backup is on for TraceVeil", "TraceVeil के लिए Android बैकअप चालू है"),
        Bi(
            "Android may copy TraceVeil's saved checks to your backup.",
            "Android TraceVeil की सेव की गई जाँचें आपके बैकअप में कॉपी कर सकता है।",
        ),
        holds = false,
    )
    private val backupUnknown = TrustFact(
        Bi("Backup not reported", "बैकअप की जानकारी नहीं"),
        Bi("Android did not report its backup setting", "Android ने बैकअप की सेटिंग नहीं बताई"),
        Bi(
            "Checks are saved in TraceVeil's private storage; whether Android backs them up is not known on this phone.",
            "जाँच TraceVeil की निजी स्टोरेज में सेव होती हैं; Android इनका बैकअप लेता है या नहीं, इस फ़ोन पर पता नहीं।",
        ),
        holds = false,
    )

    /** The four facts, with the results fact replaced by the exception that applies on this install. */
    fun facts(onlineVoiceOn: Boolean, backupAllowed: Boolean?): List<TrustFact> = listOf(
        account, vpn, screen,
        when {
            onlineVoiceOn -> voiceOn
            backupAllowed == true -> backupOn
            backupAllowed == null -> backupUnknown
            else -> stays
        },
    )

    private val microphone = Bi("Microphone", "माइक्रोफ़ोन")
    private val notifications = Bi("Notifications", "नोटिफ़िकेशन")
    private val checkVpn = Bi("Check VPN", "जाँच VPN")
    private val watcher = Bi("Consent watcher", "कंसेंट वॉचर")

    /**
     * The permissions a person can see and change: microphone and notifications (only when this install requests them),
     * the check VPN and the consent watcher. Install-time permissions such as Internet are on the full trust page.
     */
    fun permissionSummary(held: List<HeldPermission>, vpnAllowed: Boolean?, watcherOn: Boolean?): PermissionSummary {
        val byName = held.associateBy { it.name }
        val items = buildList {
            byName["android.permission.RECORD_AUDIO"]?.let { add(PermissionState(microphone, it.granted)) }
            byName["android.permission.POST_NOTIFICATIONS"]?.let { add(PermissionState(notifications, it.granted)) }
            add(PermissionState(checkVpn, vpnAllowed))
            add(PermissionState(watcher, watcherOn))
        }
        return PermissionSummary(items)
    }

    /** "2 of 4 permissions on", plus how many Android did not report. */
    fun permissionsLine(on: Int, total: Int, unknown: Int): Bi {
        val en = "$on of $total permissions on" + if (unknown > 0) " · $unknown not reported" else ""
        val hi = "$total में से $on अनुमतियाँ चालू" + if (unknown > 0) " · $unknown की जानकारी नहीं" else ""
        return Bi(en, hi)
    }
}
