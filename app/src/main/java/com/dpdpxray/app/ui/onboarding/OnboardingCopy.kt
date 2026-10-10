package com.dpdpxray.app.ui.onboarding

import com.dpdpxray.app.ui.guide.Bi

data class OnboardingPage(val key: String, val title: Bi, val body: Bi, val spoken: Bi)

/** One permission, the one reason it is needed, and whether a check can run without it. */
data class OnboardingNeed(val key: String, val title: Bi, val required: Boolean, val body: Bi)

data class OnboardingChoice(val key: String, val title: Bi, val detail: Bi)

/** Every word of first-run onboarding, in English and Hindi. Pure so it can be checked by unit tests. */
object OnboardingCopy {
    val pages = listOf(
        OnboardingPage(
            "promise",
            Bi("Apps promise.\nTraceVeil checks.", "ऐप वादा करते हैं।\nTraceVeil जाँचता है।"),
            Bi("When an app asks for consent, TraceVeil watches which addresses it tries to reach before and after you choose, right here on your phone.",
                "जब कोई ऐप सहमति माँगता है, TraceVeil देखता है कि आपके चुनने से पहले और बाद में वह किन पतों तक पहुँचने की कोशिश करता है, यहीं आपके फ़ोन पर।"),
            Bi("Apps promise. TraceVeil checks. It watches which addresses an app tries to reach before and after you choose.",
                "ऐप वादा करते हैं। TraceVeil जाँचता है। यह देखता है कि आपके चुनने से पहले और बाद में ऐप किन पतों तक पहुँचने की कोशिश करता है।"),
        ),
        OnboardingPage(
            "how",
            Bi("How a check works", "जाँच कैसे होती है"),
            Bi("A Full check runs the app three times: once without touching it, once tapping Reject, once tapping Accept. Then it compares what each run tried to reach.",
                "Full जाँच ऐप को तीन बार चलाती है: एक बार बिना छुए, एक बार Reject दबाकर, एक बार Accept दबाकर। फिर तुलना करती है कि हर बार ऐप ने किन पतों तक पहुँचने की कोशिश की।"),
            Bi("A Full check runs the app three times: without touching it, tapping Reject, and tapping Accept. Then it compares the runs.",
                "Full जाँच ऐप को तीन बार चलाती है: बिना छुए, Reject दबाकर, और Accept दबाकर। फिर तीनों की तुलना करती है।"),
        ),
        OnboardingPage(
            "needs",
            Bi("What we need and why", "हमें क्या चाहिए और क्यों"),
            Bi("Three things, each for one reason.", "तीन चीज़ें, हर एक की एक वजह।"),
            Bi("TraceVeil needs a VPN to see one app's lookups, and the consent watcher to see its popup. Shizuku is optional. Results stay on this phone.",
                "TraceVeil को एक ऐप के लुकअप देखने के लिए VPN, और उसका पॉपअप देखने के लिए कंसेंट वॉचर चाहिए। Shizuku वैकल्पिक है। नतीजे इसी फ़ोन पर रहते हैं।"),
        ),
        OnboardingPage(
            "try",
            Bi("Try it safely", "सुरक्षित तरीके से आज़माएँ"),
            Bi("Start with a sample or the demo app. Avoid banking, payment and chat apps.", "नमूने या डेमो ऐप से शुरू करें। बैंकिंग, पेमेंट और चैट ऐप से बचें।"),
            Bi("Try it safely. See a sample, or check the demo app.", "सुरक्षित तरीके से आज़माएँ। नमूना देखें, या डेमो ऐप की जाँच करें।"),
        ),
    )

    val needs = listOf(
        OnboardingNeed("vpn", Bi("VPN", "VPN"), required = true,
            Bi("Sees the DNS lookups of the one app you check. It doesn't decrypt what the app sends.",
                "जिस एक ऐप की आप जाँच करते हैं, सिर्फ़ उसके DNS लुकअप देखता है। ऐप जो भेजता है, उसे डिक्रिप्ट नहीं करता।")),
        OnboardingNeed("watcher", Bi("Consent watcher", "कंसेंट वॉचर"), required = true,
            Bi("Sees that app's consent popup, only during a check, so TraceVeil knows when Accept or Reject is tapped.",
                "सिर्फ़ जाँच के दौरान उस ऐप का कंसेंट पॉपअप देखता है, ताकि TraceVeil जाने कि Accept या Reject कब दबाया गया।")),
        OnboardingNeed("shizuku", Bi("Shizuku", "Shizuku"), required = false,
            Bi("Gives your own test apps a fresh start between runs. Used only when you approve it for that app.",
                "आपके अपने टेस्ट ऐप को हर रन से पहले नई शुरुआत देता है। सिर्फ़ तब, जब आप उस ऐप के लिए मंज़ूरी दें।")),
    )

    val required = Bi("Required", "ज़रूरी")
    val optional = Bi("Optional", "वैकल्पिक")
    val privacy = Bi("Your results stay on this phone. TraceVeil uploads nothing by default.",
        "आपके नतीजे इसी फ़ोन पर रहते हैं। TraceVeil डिफ़ॉल्ट रूप से कुछ भी अपलोड नहीं करता।")

    val lanes = listOf(Bi("Don't touch", "बिना छुए"), Bi("Reject", "Reject"), Bi("Accept", "Accept"))
    val exampleCaption = Bi("Example only. Each dot is a lookup: an attempt to reach an address, not proof that data was sent.",
        "सिर्फ़ उदाहरण। हर बिंदु एक लुकअप है: किसी पते तक पहुँचने की कोशिश, डेटा भेजे जाने का सबूत नहीं।")

    val sample = OnboardingChoice("sample", Bi("See a sample", "नमूना देखें"),
        Bi("No setup needed. Made-up data, clearly labelled.", "कोई सेटअप नहीं चाहिए। बनावटी डेटा, साफ़ लेबल के साथ।"))
    val demo = OnboardingChoice("demo", Bi("Check LeakyShop", "LeakyShop जाँचें"),
        Bi("Our demo app, built to break consent rules on purpose. Needs the VPN and consent watcher.",
            "हमारा डेमो ऐप, जो जानबूझकर कंसेंट नियम तोड़ता है। VPN और कंसेंट वॉचर चाहिए।"))
    val apk = OnboardingChoice("apk", Bi("Check an APK file", "APK फ़ाइल जाँचें"),
        Bi("Pick an app file on this phone to inspect.", "जाँचने के लिए इस फ़ोन पर कोई ऐप फ़ाइल चुनें।"))

    val skip = Bi("Skip", "छोड़ें")
    val next = Bi("Next", "आगे")
    val back = Bi("Back", "पीछे")
    val done = Bi("Go to Home", "होम पर जाएँ")
    val readAloud = Bi("Read aloud", "पढ़कर सुनाएँ")
}
