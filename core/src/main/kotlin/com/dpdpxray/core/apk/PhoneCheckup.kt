package com.dpdpxray.core.apk

/**
 * "My phone checkup": a plain count of installed apps that hold powerful capabilities right now, so a person knows
 * what to review. It reads only what Android reports (a permission that is granted, a special access that is switched
 * on, where an app was installed from). It never decides that an app is harmful or harmless, and anything Android did
 * not report stays unknown: it is counted as unknown, never as "no".
 *
 * All user-facing text lives in this file as [Bilingual] values (English and Hindi), so another language can be added
 * in one place.
 */

/** What the phone reported about one installed app. `null` means Android did not let TraceVeil read that fact. */
data class CheckupFacts(
    val packageName: String,
    val label: String?,
    /** Runtime permissions that are granted now (not just requested). */
    val grantedPermissions: Set<String>?,
    /** One of the app's accessibility services is switched on. */
    val accessibilityOn: Boolean?,
    /** The app has notification access switched on. */
    val notificationAccessOn: Boolean?,
    /** "Display over other apps" is allowed for the app. */
    val displayOverAppsAllowed: Boolean?,
    /** "Install unknown apps" is allowed for the app. */
    val installAppsAllowed: Boolean?,
    /** The app is an active device admin. */
    val deviceAdminActive: Boolean?,
    /** Where Android says the app was installed from. */
    val origin: InstallOrigin?,
)

/** One capability the checkup looks at, in the order it is shown and summarised. */
enum class CheckupArea(
    /** Section title: what the apps listed under it can do. */
    val title: Bilingual,
    /** Exactly what was checked for this area. */
    val checks: Bilingual,
    /** What the person can review, and where. */
    val review: Bilingual,
    internal val phrase: Phrase,
) {
    SMS(
        Bilingual("Can read your SMS", "आपके SMS पढ़ सकते हैं"),
        Bilingual(
            "Apps that have permission to read or receive SMS, allowed (not just asked for).",
            "वे ऐप जिन्हें SMS पढ़ने या पाने की अनुमति मिली हुई है (सिर्फ़ माँगी नहीं गई)।",
        ),
        Bilingual(
            "SMS can carry one-time passwords (OTPs). Keep SMS access for your messaging app and apps you fully trust. You can switch it off in Android settings › Apps › the app › Permissions.",
            "SMS में OTP आ सकते हैं। SMS की अनुमति सिर्फ़ अपने मैसेज ऐप और पूरी तरह भरोसेमंद ऐप को दें। इसे Android सेटिंग्स › ऐप्स › वह ऐप › अनुमतियाँ में बंद कर सकते हैं।",
        ),
        Phrase("can read SMS", "can read SMS", "SMS पढ़ सकता है", "SMS पढ़ सकते हैं"),
    ),
    SCREEN_CONTROL(
        Bilingual("Can see and control your screen", "आपकी स्क्रीन देख और चला सकते हैं"),
        Bilingual(
            "Apps with an accessibility service switched on in Android settings.",
            "वे ऐप जिनकी ऐक्सेसिबिलिटी सेवा Android सेटिंग्स में चालू है।",
        ),
        Bilingual(
            "An accessibility service can read what is on the screen and tap for you. Keep it on only for apps that need it, such as a screen reader. You can switch it off in Android settings › Accessibility.",
            "ऐक्सेसिबिलिटी सेवा स्क्रीन पर लिखा पढ़ सकती है और आपकी जगह टैप कर सकती है। इसे सिर्फ़ उन ऐप के लिए चालू रखें जिन्हें इसकी ज़रूरत है, जैसे स्क्रीन रीडर। इसे Android सेटिंग्स › ऐक्सेसिबिलिटी में बंद कर सकते हैं।",
        ),
        Phrase("can control your screen", "can control your screen", "आपकी स्क्रीन नियंत्रित कर सकता है", "आपकी स्क्रीन नियंत्रित कर सकते हैं"),
    ),
    NOTIFICATIONS(
        Bilingual("Can read your notifications", "आपके नोटिफ़िकेशन पढ़ सकते हैं"),
        Bilingual(
            "Apps with notification access switched on in Android settings.",
            "वे ऐप जिनका नोटिफ़िकेशन ऐक्सेस Android सेटिंग्स में चालू है।",
        ),
        Bilingual(
            "Notification access lets an app read every notification, including OTPs and message previews. Keep it for apps you trust, such as a smartwatch app. You can switch it off in Android's notification access settings.",
            "नोटिफ़िकेशन ऐक्सेस से ऐप हर नोटिफ़िकेशन पढ़ सकता है, OTP और मैसेज की झलक भी। इसे सिर्फ़ भरोसेमंद ऐप के लिए रखें, जैसे स्मार्टवॉच ऐप। इसे Android की नोटिफ़िकेशन ऐक्सेस सेटिंग में बंद कर सकते हैं।",
        ),
        Phrase("can read your notifications", "can read your notifications", "आपके नोटिफ़िकेशन पढ़ सकता है", "आपके नोटिफ़िकेशन पढ़ सकते हैं"),
    ),
    DRAW_OVER_APPS(
        Bilingual("Can display over other apps", "दूसरे ऐप्स के ऊपर दिख सकते हैं"),
        Bilingual(
            "Apps allowed to \"display over other apps\" in Android settings.",
            "वे ऐप जिन्हें Android सेटिंग्स में \"दूसरे ऐप्स के ऊपर दिखाएँ\" की अनुमति है।",
        ),
        Bilingual(
            "These apps can show windows on top of other apps, such as chat bubbles or caller ID. The same power can cover another app's screen, so allow it only where you see why it is needed.",
            "ये ऐप दूसरे ऐप्स के ऊपर विंडो दिखा सकते हैं, जैसे चैट बबल या कॉलर ID। इसी अनुमति से किसी दूसरे ऐप की स्क्रीन ढकी भी जा सकती है, इसलिए यह अनुमति तभी दें जब इसकी वजह समझ आए।",
        ),
        Phrase("can display over other apps", "can display over other apps", "दूसरे ऐप्स के ऊपर दिख सकता है", "दूसरे ऐप्स के ऊपर दिख सकते हैं"),
    ),
    INSTALL_APPS(
        Bilingual("Can install other apps", "दूसरे ऐप इंस्टॉल कर सकते हैं"),
        Bilingual(
            "Apps allowed to \"install unknown apps\" in Android settings.",
            "वे ऐप जिन्हें Android सेटिंग्स में \"अनजान ऐप इंस्टॉल करें\" की अनुमति है।",
        ),
        Bilingual(
            "These apps can ask Android to install other apps. Allow it only for app stores and file managers you use on purpose, and switch it off again after use.",
            "ये ऐप Android से दूसरे ऐप इंस्टॉल करवा सकते हैं। यह अनुमति सिर्फ़ उन ऐप स्टोर और फ़ाइल मैनेजर को दें जिन्हें आप जान-बूझकर इस्तेमाल करते हैं, और काम के बाद इसे फिर बंद कर दें।",
        ),
        Phrase("can install other apps", "can install other apps", "दूसरे ऐप इंस्टॉल कर सकता है", "दूसरे ऐप इंस्टॉल कर सकते हैं"),
    ),
    DEVICE_ADMIN(
        Bilingual("Have device admin rights", "डिवाइस एडमिन अधिकार वाले ऐप"),
        Bilingual("Apps that are active device admins.", "वे ऐप जो चालू डिवाइस एडमिन हैं।"),
        Bilingual(
            "Device admin rights can lock the phone, wipe it or make an app harder to uninstall. Work phones use them for company rules. If you do not recognise the app, switch it off under device admin apps in Android's security settings.",
            "डिवाइस एडमिन अधिकार से फ़ोन लॉक या मिटाया जा सकता है, या ऐप को हटाना मुश्किल किया जा सकता है। ऑफ़िस के फ़ोन में कंपनी के नियमों के लिए इसका इस्तेमाल होता है। अगर आप ऐप को नहीं पहचानते, तो Android की सुरक्षा सेटिंग में डिवाइस एडमिन ऐप में जाकर इसे बंद करें।",
        ),
        Phrase("has device admin rights", "have device admin rights", "के पास डिवाइस एडमिन अधिकार है", "के पास डिवाइस एडमिन अधिकार हैं"),
    ),
    OUTSIDE_STORE(
        Bilingual("Came from outside a known app store", "किसी जाने-माने ऐप स्टोर के बाहर से आए"),
        Bilingual(
            "Where Android says each app was installed from: an APK file, or another app that is not on TraceVeil's list of known app stores.",
            "Android के अनुसार हर ऐप कहाँ से इंस्टॉल हुआ: APK फ़ाइल से, या किसी ऐसे ऐप से जो TraceVeil की जाने-माने ऐप स्टोर की सूची में नहीं है।",
        ),
        Bilingual(
            "Apps from outside an app store skip that store's checks. Make sure you know where each one came from and that you still need it.",
            "ऐप स्टोर के बाहर से आए ऐप उस स्टोर की जाँच से नहीं गुज़रते। पक्का करें कि आप जानते हैं हर ऐप कहाँ से आया और क्या आपको अब भी उसकी ज़रूरत है।",
        ),
        Phrase("came from outside a known app store", "came from outside a known app store", "किसी जाने-माने ऐप स्टोर के बाहर से आया है", "किसी जाने-माने ऐप स्टोर के बाहर से आए हैं"),
    ),
    ;

    /** How a count is said in a summary line, with singular and plural verbs. */
    internal class Phrase(val oneEn: String, val manyEn: String, val oneHi: String, val manyHi: String)
}

data class CheckupApp(val packageName: String, val label: String)

/**
 * One area's result. [apps] hold the capability now; [unknown] apps could not be read for this area; [checked] apps
 * were read (yes or no).
 */
data class CheckupSection(val area: CheckupArea, val apps: List<CheckupApp>, val unknown: Int, val checked: Int) {
    val count: Int get() = apps.size
    /** False when Android reported this area for none of the apps, so its zero means "unknown", not "none". */
    val readable: Boolean get() = checked > 0
}

data class PhoneCheckupResult(
    /** Installed apps that were looked at (apps that came with the phone and TraceVeil itself are not). */
    val appsChecked: Int,
    /** Every [CheckupArea], always in the same order. */
    val sections: List<CheckupSection>,
    val headline: Bilingual,
    val notificationTitle: Bilingual,
    val notificationText: Bilingual,
    /** What was checked and what was not. */
    val scope: Bilingual,
    /** Areas Android reported for none of the apps; null when every area could be read. */
    val notRead: Bilingual?,
) {
    /** Distinct apps listed in at least one area. */
    val appsToReview: Int get() = sections.flatMap { s -> s.apps.map { it.packageName } }.toSet().size

    /** A notification is worth posting only when there is something to review. */
    val shouldNotify: Boolean get() = appsToReview > 0

    /** The one-line form, for example "Phone checkup: 2 apps can read SMS. Review →". */
    fun line(hindi: Boolean): String =
        if (hindi) "${notificationTitle.hi}: ${notificationText.hi}" else "${notificationTitle.en}: ${notificationText.en}"
}

object PhoneCheckup {
    /** Granted permissions that let an app read incoming or stored SMS. */
    val SMS_PERMISSIONS = setOf("android.permission.READ_SMS", "android.permission.RECEIVE_SMS")

    /** At most this many areas are named in a summary line; the rest are counted. */
    private const val NAMED_AREAS = 3

    val TITLE = Bilingual("Phone checkup", "फ़ोन जाँच")
    private val REVIEW = Bilingual("Review →", "देखें →")

    val SCOPE = Bilingual(
        "What was checked, for each app you installed: SMS permission allowed (read or receive), accessibility switched on, " +
            "notification access switched on, display over other apps allowed, install unknown apps allowed, device admin active, " +
            "and where Android says it was installed from. Not checked: apps that came with the phone, TraceVeil itself, other " +
            "permissions such as camera, microphone, location and contacts, and what any app actually does with its access. " +
            "This checkup is not an antivirus; it lists capabilities you may want to review.",
        "आपके इंस्टॉल किए हर ऐप के लिए ये जाँचा गया: SMS की अनुमति (पढ़ना या पाना), चालू ऐक्सेसिबिलिटी सेवा, चालू नोटिफ़िकेशन ऐक्सेस, " +
            "दूसरे ऐप्स के ऊपर दिखने की अनुमति, अनजान ऐप इंस्टॉल करने की अनुमति, चालू डिवाइस एडमिन, और Android के अनुसार ऐप कहाँ से इंस्टॉल हुआ। " +
            "नहीं जाँचा गया: फ़ोन के साथ आए ऐप, ख़ुद TraceVeil, कैमरा, माइक्रोफ़ोन, लोकेशन और कॉन्टैक्ट जैसी दूसरी अनुमतियाँ, और कोई ऐप " +
            "अपनी अनुमति से असल में क्या करता है। यह जाँच एंटीवायरस नहीं है; यह उन क्षमताओं की सूची है जिन्हें आप देखना चाहें।",
    )

    fun evaluate(apps: List<CheckupFacts>): PhoneCheckupResult {
        val unique = apps.distinctBy { it.packageName }
        val sections = CheckupArea.entries.map { area -> section(area, unique) }
        val headline = headline(unique.size, sections)
        val notRead = sections.filter { !it.readable && unique.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { unread ->
            Bilingual(
                "Android did not let TraceVeil read these, so they are unknown, not zero: ${unread.joinToString("; ") { it.area.title.en }}.",
                "Android ने ये पढ़ने नहीं दिए, इसलिए ये अज्ञात हैं, शून्य नहीं: ${unread.joinToString("; ") { it.area.title.hi }}।",
            )
        }
        return PhoneCheckupResult(
            appsChecked = unique.size,
            sections = sections,
            headline = headline,
            notificationTitle = TITLE,
            notificationText = Bilingual("${headline.en} ${REVIEW.en}", "${headline.hi} ${REVIEW.hi}"),
            scope = SCOPE,
            notRead = notRead,
        )
    }

    /** true = holds the capability, false = does not, null = Android did not report it. */
    private fun reading(area: CheckupArea, f: CheckupFacts): Boolean? = when (area) {
        CheckupArea.SMS -> f.grantedPermissions?.any { it in SMS_PERMISSIONS }
        CheckupArea.SCREEN_CONTROL -> f.accessibilityOn
        CheckupArea.NOTIFICATIONS -> f.notificationAccessOn
        CheckupArea.DRAW_OVER_APPS -> f.displayOverAppsAllowed
        CheckupArea.INSTALL_APPS -> f.installAppsAllowed
        CheckupArea.DEVICE_ADMIN -> f.deviceAdminActive
        CheckupArea.OUTSIDE_STORE -> outsideStore(f.origin)
    }

    /** An APK file or an app that is not a known store counts; a store or the phone itself does not; no answer is unknown. */
    fun outsideStore(origin: InstallOrigin?): Boolean? = when (origin?.source) {
        InstallSource.FILE, InstallSource.OTHER_APP -> true
        InstallSource.PLAY_STORE, InstallSource.OTHER_STORE, InstallSource.PREINSTALLED -> false
        InstallSource.UNKNOWN, InstallSource.NOT_INSTALLED, null -> null
    }

    private fun section(area: CheckupArea, apps: List<CheckupFacts>): CheckupSection {
        val readings = apps.map { it to reading(area, it) }
        val holding = readings.filter { it.second == true }.map { (f, _) -> CheckupApp(f.packageName, displayName(f)) }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
        return CheckupSection(area, holding, unknown = readings.count { it.second == null }, checked = readings.count { it.second != null })
    }

    private fun displayName(f: CheckupFacts) = f.label?.trim()?.takeIf { it.isNotEmpty() } ?: f.packageName

    private fun headline(checked: Int, sections: List<CheckupSection>): Bilingual {
        if (checked == 0) return Bilingual("No apps you installed were found to check.", "जाँचने के लिए आपके इंस्टॉल किए हुए कोई ऐप नहीं मिले।")
        val found = sections.filter { it.count > 0 }
        if (found.isEmpty()) {
            val partly = sections.any { it.unknown > 0 }
            val appsEn = if (checked == 1) "1 app checked" else "$checked apps checked"
            val appsHi = if (checked == 1) "1 ऐप जाँचा गया" else "$checked ऐप जाँचे गए"
            return if (partly) Bilingual(
                "No app needs review in the checks Android let TraceVeil read ($appsEn).",
                "Android ने जो बातें पढ़ने दीं, उनमें किसी ऐप को देखने की ज़रूरत नहीं मिली ($appsHi)।",
            ) else Bilingual(
                "No app needs review in these checks ($appsEn).",
                "इन जाँचों में किसी ऐप को देखने की ज़रूरत नहीं मिली ($appsHi)।",
            )
        }
        val named = found.take(NAMED_AREAS)
        val more = found.size - named.size
        val en = named.mapIndexed { i, s ->
            val n = s.count
            val verb = if (n == 1) s.area.phrase.oneEn else s.area.phrase.manyEn
            if (i == 0) "$n ${if (n == 1) "app" else "apps"} $verb" else "$n $verb"
        }.toMutableList()
        val hi = named.map { s -> "${s.count} ऐप ${if (s.count == 1) s.area.phrase.oneHi else s.area.phrase.manyHi}" }.toMutableList()
        if (more > 0) {
            en += "$more more ${if (more == 1) "thing" else "things"} to review"
            hi += "देखने लायक $more और ${if (more == 1) "बात है" else "बातें हैं"}"
        }
        return Bilingual(joinList(en, ", ", " and ") + ".", joinList(hi, ", ", " और ") + "।")
    }

    private fun joinList(parts: List<String>, comma: String, and: String): String =
        if (parts.size <= 1) parts.joinToString("") else parts.dropLast(1).joinToString(comma) + and + parts.last()
}
