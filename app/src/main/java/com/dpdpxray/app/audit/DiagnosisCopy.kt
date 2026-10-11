package com.dpdpxray.app.audit

import com.dpdpxray.app.ui.guide.Bi
import com.dpdpxray.core.rules.VisibilityDiagnosis.Action
import com.dpdpxray.core.rules.VisibilityDiagnosis.Cause
import com.dpdpxray.core.rules.VisibilityDiagnosis.Item

/**
 * Words for the result's "Why there's no score" group, in both guidance languages. The English body is the core
 * diagnosis sentence itself (the same words as the report); Hindi gives the same cause without the run's numbers, which
 * stay in the evidence.
 */
object DiagnosisCopy {
    val heading = Bi("Why there's no score", "स्कोर क्यों नहीं है")

    val restart = Bi("Check again with a restart", "रीस्टार्ट करके फिर से जाँचें")
    val restartNote = Bi(
        "A restart closes and reopens the app before each run, so it makes its lookups again where TraceVeil can see them. Your data stays.",
        "रीस्टार्ट हर रन से पहले ऐप को बंद करके फिर खोलता है, ताकि वह अपने लुकअप फिर से करे और TraceVeil उन्हें देख सके। आपका डेटा बना रहता है।",
    )
    val reset = Bi("Check again with a reset", "रीसेट करके फिर से जाँचें")
    fun resetConfirmTitle(app: String) = Bi("Clear all of $app's data?", "$app का पूरा डेटा मिटाएँ?")
    fun resetConfirmBody(app: String) = Bi(
        "This clears $app's data before each run: accounts, logins, saved state, settings and anything not yet uploaded. " +
            "This cannot be undone. Use it only for your own test app that you can set up again.",
        "यह हर रन से पहले $app का डेटा मिटाता है: अकाउंट, लॉगिन, सहेजी स्थिति, सेटिंग और जो कुछ अभी अपलोड नहीं हुआ। " +
            "इसे वापस नहीं लाया जा सकता। इसे सिर्फ़ अपने ऐसे टेस्ट ऐप पर इस्तेमाल करें जिसे आप फिर से सेट कर सकें।",
    )
    val resetConfirm = Bi("Reset and check", "रीसेट करके जाँचें")
    val cancel = Bi("Cancel", "रद्द करें")

    fun title(c: Cause): Bi = when (c) {
        Cause.OTHER_VPN -> Bi("Another VPN was on", "दूसरा VPN चालू था")
        Cause.CAPTURE_STOPPED -> Bi("The capture stopped", "कैप्चर रुक गया")
        Cause.NO_NETWORK -> Bi("No internet connection", "इंटरनेट कनेक्शन नहीं था")
        Cause.PRIVATE_DNS -> Bi("Private DNS was on", "Private DNS चालू था")
        Cause.PHONE_LOCKED -> Bi("The phone was locked", "फ़ोन लॉक था")
        Cause.APP_CRASHED -> Bi("The app stopped with an error", "ऐप गड़बड़ी के साथ बंद हुआ")
        Cause.NO_INTERNET_PERMISSION -> Bi("The app has no internet access", "ऐप को इंटरनेट की अनुमति नहीं है")
        Cause.ALREADY_RUNNING -> Bi("The app was already running", "ऐप पहले से चल रहा था")
        Cause.OWN_DNS -> Bi("The app may use its own DNS", "ऐप शायद अपना DNS इस्तेमाल करता है")
        Cause.APP_QUIET -> Bi("The app stayed offline", "ऐप ऑफ़लाइन रहा")
        Cause.WINDOW_TOO_SHORT -> Bi("The watch was short", "देखने का समय छोटा था")
        Cause.UNEXPLAINED -> Bi("Cause not known", "कारण पता नहीं")
        Cause.GATED -> Bi("A sign-in or passcode screen was in the way", "साइन-इन या पासकोड स्क्रीन रास्ते में थी")
        Cause.QUIET_WATCH -> Bi("Almost no connections while watched", "देखते समय लगभग कोई कनेक्शन नहीं")
    }

    fun body(item: Item): Bi = Bi(item.sentence, when (item.cause) {
        Cause.OTHER_VPN -> "जाँच के दौरान दूसरा VPN चालू था। Android एक समय में एक ही VPN चलाता है, इसलिए TraceVeil ऐप के लुकअप नहीं देख सका।"
        Cause.CAPTURE_STOPPED -> "जाँच के दौरान TraceVeil का कैप्चर रुक गया (जैसे VPN बंद हो गया), इसलिए बाद के लुकअप नहीं दिखे।"
        Cause.NO_NETWORK -> "जाँच के दौरान फ़ोन पर चालू इंटरनेट नहीं था, इसलिए ऐप कहीं नहीं पहुँच सका।"
        Cause.PRIVATE_DNS -> "Private DNS चालू था, इसलिए Android ऐप के लुकअप एन्क्रिप्ट करके TraceVeil से बाहर भेज सकता है। Settings में इसे बंद करके फिर जाँचें।"
        Cause.PHONE_LOCKED -> "जाँच के दौरान फ़ोन लॉक हुआ या स्क्रीन बंद हुई, इसलिए ऐप रुक गया हो सकता है। फ़ोन अनलॉक रखकर फिर जाँचें।"
        Cause.APP_CRASHED -> "जाँच के दौरान ऐप गड़बड़ी के साथ बंद हुआ (Android ने \"बार-बार बंद हो रहा है\" या \"जवाब नहीं दे रहा\" दिखाया), इसलिए देखने को कम था।"
        Cause.NO_INTERNET_PERMISSION -> "ऐप Android से इंटरनेट की अनुमति नहीं माँगता, इसलिए वह खुद ऑनलाइन नहीं जा सकता। TraceVeil के देखने लायक कुछ नहीं था।"
        Cause.ALREADY_RUNNING -> "ऐप पहले से चल रहा था या हो सकता है, और TraceVeil उसे रीस्टार्ट नहीं कर सका, इसलिए उसने जाँच से पहले के पते फिर इस्तेमाल किए होंगे। रीस्टार्ट करके फिर जाँचें: आपका डेटा बना रहता है। बैकग्राउंड में ज़्यादा देर देखने के लिए रीस्टार्ट की ज़रूरत नहीं।"
        Cause.OWN_DNS -> "ऐप ने नेटवर्क इस्तेमाल किया, पर TraceVeil को लगभग कोई लुकअप नहीं दिखा, इसलिए वह शायद अपना एन्क्रिप्टेड DNS (DNS over HTTPS) या तय पते इस्तेमाल करता है।"
        Cause.APP_QUIET -> "TraceVeil ने ऐप को रीस्टार्ट किया और जाँच के दौरान उसने नेटवर्क इस्तेमाल नहीं किया, इसलिए परखने को कुछ नहीं था। बैकग्राउंड में काम करने वाला ऐप शायद हर कुछ मिनट पर ही ऑनलाइन जाता है।"
        Cause.WINDOW_TOO_SHORT -> "TraceVeil ने बहुत कम समय तक देखा, इसलिए ऐप के थोड़ी देर बाद के लुकअप नहीं दिखे। सामान्य Quick जाँच ज़्यादा देर देखती है।"
        Cause.UNEXPLAINED -> "TraceVeil को लगभग कोई लुकअप नहीं दिखा और दर्ज किसी चीज़ से इसका कारण पता नहीं चलता। ऐप शायद खुलते समय ऑनलाइन नहीं जाता, या अपना DNS इस्तेमाल करता है; यह इस फ़ोन पर पता नहीं।"
        Cause.GATED -> "ऐप की सहमति स्क्रीन से पहले साइन-इन, पासकोड या ऐसी ही स्क्रीन आई और पार नहीं हुई, इसलिए जाँच ऐप में आगे नहीं बढ़ सकी। ऐसे ऐप शायद सिर्फ़ बैकग्राउंड में, हर कुछ मिनट पर ऑनलाइन जाते हैं, जिसे बैकग्राउंड निगरानी बिना साइन-इन के देख सकती है।"
        Cause.QUIET_WATCH -> "TraceVeil ने ऐप को बैकग्राउंड में देखा और उसे लगभग कोई कनेक्शन बनाते नहीं देखा। यह इसी निगरानी में देखा गया: ऐप शायद इससे कम बार ऑनलाइन जाता है, या निगरानी से पहले खोला गया कनेक्शन इस्तेमाल करता है, जिसे TraceVeil नहीं देख सकता।"
    })

    /**
     * The label of the one-tap action for [a]; null when nothing on the phone would help. A background watch names [app]
     * and its length in [minutes].
     */
    fun action(a: Action, app: String = "the app", minutes: Int = com.dpdpxray.core.model.BackgroundWatches.DEFAULT_MINUTES): Bi? = when (a) {
        Action.CHECK_AGAIN_WITH_RESTART -> restart
        Action.TURN_OFF_PRIVATE_DNS -> Bi("Open Private DNS settings", "Private DNS सेटिंग खोलें")
        Action.TURN_OFF_OTHER_VPN -> Bi("Open VPN settings", "VPN सेटिंग खोलें")
        Action.CONNECT_TO_INTERNET -> Bi("Open internet settings", "इंटरनेट सेटिंग खोलें")
        Action.CHECK_AGAIN -> Bi("Check again", "फिर से जाँचें")
        Action.RUN_WITH_INTERNET_CUT_OFF -> Bi("Run with the internet cut off", "इंटरनेट काटकर चलाएँ")
        Action.WATCH_LONGER -> WatchCopy.action(app, minutes)
        Action.NONE -> null
    }
}
