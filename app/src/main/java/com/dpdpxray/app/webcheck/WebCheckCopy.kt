package com.dpdpxray.app.webcheck

import com.dpdpxray.app.ui.guide.Bi

/** Every word of the website check, in both guidance languages. Unit tested in WebCheckCopyTest. */
object WebCheckCopy {
    // ---- Entry points -----------------------------------------------------------------------------------------------
    val segment = Bi("Website", "वेबसाइट")
    val homeTitle = Bi("A website", "कोई वेबसाइट")
    val homeBody = Bi("Its cookie banner and every request it makes", "उसका कुकी बैनर और उसकी हर रिक्वेस्ट")

    // ---- Start screen -------------------------------------------------------------------------------------------------
    val title = Bi("Check a website", "वेबसाइट जाँचें")
    val lead = Bi(
        "TraceVeil opens the site in its own browser, refuses and accepts its cookie banner, and records every request the page makes, with the full address.",
        "TraceVeil साइट को अपने ब्राउज़र में खोलता है, उसके कुकी बैनर को मना और स्वीकार करता है, और पेज की हर रिक्वेस्ट को पूरे पते के साथ दर्ज करता है।",
    )
    val fieldLabel = Bi("Website address", "वेबसाइट का पता")
    val fieldHint = Bi("example.com", "example.com")

    /** The required disclosure of the new network path, shown above the start button. */
    val disclosure = Bi(
        "TraceVeil opens this site on your phone to check it; the site sees a normal visit. Nothing else is sent anywhere.",
        "जाँच के लिए TraceVeil यह साइट आपके फ़ोन पर खोलता है; साइट को यह एक सामान्य विज़िट दिखती है। और कुछ कहीं नहीं भेजा जाता।",
    )
    val addressesNote = Bi(
        "Afterwards it asks this phone's DNS resolver for the addresses of the servers the site contacted, for the server map.",
        "बाद में सर्वर मैप के लिए यह इस फ़ोन के DNS रिज़ॉल्वर से उन सर्वरों के पते पूछता है जिनसे साइट ने संपर्क किया।",
    )
    val upgraded = Bi(
        "This address starts with http. TraceVeil uses only encrypted connections, so it checks the same address over https.",
        "यह पता http से शुरू होता है। TraceVeil सिर्फ़ एन्क्रिप्टेड कनेक्शन इस्तेमाल करता है, इसलिए यही पता https पर जाँचा जाएगा।",
    )
    val tapsTitle = Bi("Let TraceVeil tap the banner", "बैनर पर TraceVeil को टैप करने दें")
    val tapsBody = Bi(
        "Off: you tap Reject and Accept yourself when asked. Either way the taps happen in TraceVeil's empty browser, not in your own.",
        "बंद: पूछे जाने पर Reject और Accept आप खुद टैप करें। दोनों तरह से टैप TraceVeil के खाली ब्राउज़र में होते हैं, आपके अपने ब्राउज़र में नहीं।",
    )
    val plansLine = Bi(
        "Three runs, each in an emptied browser: don't touch the banner (25 s), Reject (15 s after the tap), Accept (15 s after the tap). About 1 to 2 minutes.",
        "तीन रन, हर बार खाली किए गए ब्राउज़र में: बैनर को न छुएँ (25 सेकंड), Reject (टैप के 15 सेकंड बाद तक), Accept (टैप के 15 सेकंड बाद तक)। लगभग 1 से 2 मिनट।",
    )
    val start = Bi("Check this website", "यह वेबसाइट जाँचें")
    val limits = Bi(
        "TraceVeil's browser is not your own browser, and some sites detect it. Do not sign in unless you mean to check signed-in pages.",
        "TraceVeil का ब्राउज़र आपका अपना ब्राउज़र नहीं है, और कुछ साइटें इसे पहचान लेती हैं। साइन-इन न करें, जब तक आप साइन-इन वाले पेज जाँचना न चाहें।",
    )

    // ---- Live screen ----------------------------------------------------------------------------------------------------
    val live = Bi("Live · website check", "लाइव · वेबसाइट जाँच")
    val requests = Bi("requests", "रिक्वेस्ट")
    val toTrackers = Bi("to trackers", "ट्रैकरों को")
    val beforeChoice = Bi("before any choice", "किसी भी चुनाव से पहले")
    val caveat = Bi(
        "A request shows the page asked to contact that address, not what data was sent.",
        "रिक्वेस्ट दिखाती है कि पेज ने उस पते से संपर्क माँगा, यह नहीं कि कौन-सा डेटा भेजा गया।",
    )
    val bannerFound = Bi("Consent banner found", "कंसेंट बैनर मिला")
    val stop = Bi("Stop check", "जाँच रोकें")
    val stopTitle = Bi("Stop the website check?", "वेबसाइट जाँच रोकें?")
    val stopBody = Bi("Nothing is saved, and TraceVeil clears its browser.", "कुछ भी सेव नहीं होगा, और TraceVeil अपना ब्राउज़र साफ़ कर देगा।")
    val keepChecking = Bi("Keep checking", "जाँच जारी रखें")
    val stopped = Bi("The website check stopped", "वेबसाइट जाँच रुक गई")
    val backToCheck = Bi("Back", "वापस")

    // Steps (the live screen shows the step; plan names come from the app's live screen)
    val preparing = Bi("Getting the browser ready…", "ब्राउज़र तैयार हो रहा है…")
    val clearing = Bi("Emptying TraceVeil's browser…", "TraceVeil का ब्राउज़र खाली हो रहा है…")
    val opening = Bi("Opening the site…", "साइट खुल रही है…")
    val lookingForBanner = Bi("Looking for the consent banner…", "कंसेंट बैनर ढूँढ रहा है…")
    val silent = Bi("Not touching anything; watching requests", "कुछ नहीं छू रहा; रिक्वेस्ट देख रहा है")
    val silentBanner = Bi("Banner found; not touching it, watching requests", "बैनर मिला; उसे नहीं छू रहा, रिक्वेस्ट देख रहा है")
    val tappingReject = Bi("Tapping Reject…", "Reject टैप कर रहा है…")
    val tappingAccept = Bi("Tapping Accept…", "Accept टैप कर रहा है…")
    val tapReject = Bi("Tap Reject (or Reject all) on the banner below", "नीचे बैनर पर Reject (या Reject all) टैप करें")
    val tapAccept = Bi("Tap Accept on the banner below", "नीचे बैनर पर Accept टैप करें")
    val watching = Bi("Watching what the page does after the choice", "चुनाव के बाद पेज क्या करता है, देख रहा है")
    val saving = Bi("Looking up server addresses and saving…", "सर्वर के पते देखकर सेव कर रहा है…")

    // Messages
    val alreadyRunning = Bi("A website check is already running.", "एक वेबसाइट जाँच पहले से चल रही है।")
    val appCheckRunning = Bi("An app check is running. Let it finish first.", "एक ऐप जाँच चल रही है। पहले उसे पूरा होने दें।")
    val failed = Bi("The website check could not finish. Nothing was saved.", "वेबसाइट जाँच पूरी नहीं हो सकी। कुछ सेव नहीं हुआ।")
    val notCleared = Bi(
        "TraceVeil could not empty its browser before a run, so the check stopped. Nothing was saved; try again.",
        "रन से पहले TraceVeil अपना ब्राउज़र खाली नहीं कर सका, इसलिए जाँच रुक गई। कुछ सेव नहीं हुआ; फिर से कोशिश करें।",
    )
    val pageStopped = Bi(
        "The page stopped working in TraceVeil's browser, so the check stopped. Nothing was saved.",
        "पेज TraceVeil के ब्राउज़र में काम करना बंद कर गया, इसलिए जाँच रुक गई। कुछ सेव नहीं हुआ।",
    )

    // Notes saved with a plan (English: saved audit text)
    fun noBannerNote(seconds: Long) = Bi(
        "No consent banner was found in the parts of the page TraceVeil can read within $seconds seconds.",
        "$seconds सेकंड में पेज के उन हिस्सों में कोई कंसेंट बैनर नहीं मिला जिन्हें TraceVeil पढ़ सकता है।",
    )
    val noTapNote = Bi("No Accept or Reject tap was seen while TraceVeil waited.", "TraceVeil के इंतज़ार में कोई Accept या Reject टैप नहीं दिखा।")
    val noChoiceNote = Bi("The plan's choice was not made.", "इस रन का चुनाव नहीं हुआ।")
    val notLoadedNote = Bi("The page did not load.", "पेज लोड नहीं हुआ।")
    val stillShownNote = Bi(
        "The banner was still on screen 3 seconds after the tap; the page may not have taken the tap.",
        "टैप के 3 सेकंड बाद भी बैनर स्क्रीन पर था; हो सकता है पेज ने टैप न लिया हो।",
    )
    fun frameTapsNote(host: String?) = Bi(
        "The banner is inside a frame" + (host?.let { " from $it" } ?: "") + "; TraceVeil cannot see taps inside it, so a choice made there is not recorded.",
        "बैनर एक फ़्रेम के अंदर है" + (host?.let { " ($it)" } ?: "") + "; TraceVeil उसके अंदर के टैप नहीं देख सकता, इसलिए वहाँ किया गया चुनाव दर्ज नहीं होता।",
    )

    // ---- Result ---------------------------------------------------------------------------------------------------------
    val evidenceTitle = Bi("Website evidence", "वेबसाइट के सबूत")
    val evidenceSummary = Bi("Requests, cookies, storage and the banner, per run", "हर रन की रिक्वेस्ट, कुकी, स्टोरेज और बैनर")
    val notice = Bi(
        "Website check: TraceVeil's own browser saw every request with its full address. A request is not proof of what data was sent.",
        "वेबसाइट जाँच: TraceVeil के अपने ब्राउज़र ने हर रिक्वेस्ट को पूरे पते के साथ देखा। रिक्वेस्ट इस बात का सबूत नहीं कि कौन-सा डेटा भेजा गया।",
    )
    val mapSummary = Bi("Which countries the contacted servers' addresses are in, and who runs them", "संपर्क किए गए सर्वरों के पते किन देशों में हैं, और उन्हें कौन चलाता है")
    val mapNote = Bi(
        "Addresses were looked up by TraceVeil after the check; the browser may have used different ones.",
        "पते जाँच के बाद TraceVeil ने देखे; हो सकता है ब्राउज़र ने अलग पते इस्तेमाल किए हों।",
    )
    val timelineSummary = Bi("Each run's requests, with the moment of the consent choice marked", "हर रन की रिक्वेस्ट, कंसेंट चुनाव के पल के निशान के साथ")
}
