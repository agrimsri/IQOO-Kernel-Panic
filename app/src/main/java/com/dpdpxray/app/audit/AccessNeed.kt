package com.dpdpxray.app.audit

import com.dpdpxray.app.control.Preflight
import com.dpdpxray.app.ui.guide.Bi

/** A permission or helper a check cannot run without. Each has a reason in plain words and one place to switch it on. */
enum class AccessNeed { OTHER_VPN, VPN, WATCHER, SHIZUKU_RUNNING, SHIZUKU_PERMISSION }

/** A check waiting on [need]; [resume] runs by itself once it is met. */
class AccessPrompt(val need: AccessNeed, val resume: (() -> Unit)? = null)

/** Thrown inside a running check when it hits a missing [need]; the live screen then shows the reason and the switch. */
class AccessNeededException(val need: AccessNeed, message: String) : IllegalStateException(message)

object AccessNeeds {
    /** What a plain check lacks right now, first thing first, or null when it can start. */
    fun forCheck(pf: Preflight): AccessNeed? = when {
        pf.otherVpnActive -> AccessNeed.OTHER_VPN
        !pf.vpnReady -> AccessNeed.VPN
        !pf.accessibilityOn -> AccessNeed.WATCHER
        else -> null
    }

    /** Whether [need] no longer stands in the way, judged from a fresh [Preflight]. */
    fun met(need: AccessNeed, pf: Preflight): Boolean = when (need) {
        AccessNeed.OTHER_VPN -> !pf.otherVpnActive
        AccessNeed.VPN -> pf.vpnReady
        AccessNeed.WATCHER -> pf.accessibilityOn
        AccessNeed.SHIZUKU_RUNNING -> pf.shizukuRunning
        AccessNeed.SHIZUKU_PERMISSION -> pf.shizukuGranted
    }

    /** The Shizuku step a check that restarts the app is missing, or null when Shizuku is ready. */
    fun shizuku(running: Boolean, granted: Boolean): AccessNeed? = when {
        !running -> AccessNeed.SHIZUKU_RUNNING
        !granted -> AccessNeed.SHIZUKU_PERMISSION
        else -> null
    }
}

/** Why a [AccessNeed] matters and what the button does, in both guidance languages. */
object AccessCopy {
    fun title(n: AccessNeed) = when (n) {
        AccessNeed.OTHER_VPN -> Bi("Another VPN is on", "दूसरा VPN चालू है")
        AccessNeed.VPN -> Bi("Allow the VPN", "VPN की अनुमति दें")
        AccessNeed.WATCHER -> Bi("Turn on the consent watcher", "कंसेंट वॉचर चालू करें")
        AccessNeed.SHIZUKU_RUNNING -> Bi("Start Shizuku", "Shizuku शुरू करें")
        AccessNeed.SHIZUKU_PERMISSION -> Bi("Allow Shizuku", "Shizuku की अनुमति दें")
    }

    fun reason(n: AccessNeed) = when (n) {
        AccessNeed.OTHER_VPN -> Bi(
            "Android runs one VPN at a time, and TraceVeil needs its own to see which servers the app contacts. Turn your VPN off, then come back. TraceVeil never switches it off for you.",
            "Android एक समय में एक ही VPN चलाता है, और TraceVeil को ऐप के संपर्क वाले सर्वर देखने के लिए अपना VPN चाहिए। अपना VPN बंद करें, फिर वापस आएँ। TraceVeil उसे खुद बंद नहीं करता।",
        )
        AccessNeed.VPN -> Bi(
            "TraceVeil uses a private VPN on this phone only, to see which servers the app contacts. Nothing leaves your phone.",
            "TraceVeil केवल इसी फ़ोन पर एक निजी VPN इस्तेमाल करता है, ताकि देख सके कि ऐप किन सर्वरों से संपर्क करता है। कुछ भी फ़ोन से बाहर नहीं जाता।",
        )
        AccessNeed.WATCHER -> Bi(
            "TraceVeil reads the checked app's consent screen through Android's Accessibility service, and taps Accept or Reject only when you ask it to. Find TraceVeil in the list, switch it on, then come back.",
            "TraceVeil जाँचे जा रहे ऐप की सहमति स्क्रीन Android की एक्सेसिबिलिटी सेवा से पढ़ता है, और Accept या Reject तभी दबाता है जब आप कहें। सूची में TraceVeil ढूँढें, उसे चालू करें, फिर वापस आएँ।",
        )
        AccessNeed.SHIZUKU_RUNNING -> Bi(
            "Full check starts the app fresh each time, which needs the Shizuku helper app to be running. Open Shizuku, start it, then come back.",
            "पूरी जाँच हर बार ऐप को नए सिरे से शुरू करती है, जिसके लिए Shizuku सहायक ऐप का चालू होना ज़रूरी है। Shizuku खोलें, उसे शुरू करें, फिर वापस आएँ।",
        )
        AccessNeed.SHIZUKU_PERMISSION -> Bi(
            "Full check starts the app fresh each time, which needs Shizuku's permission. Shizuku is running; allow TraceVeil to use it.",
            "पूरी जाँच हर बार ऐप को नए सिरे से शुरू करती है, जिसके लिए Shizuku की अनुमति चाहिए। Shizuku चल रहा है; TraceVeil को इसे इस्तेमाल करने दें।",
        )
    }

    fun button(n: AccessNeed) = when (n) {
        AccessNeed.OTHER_VPN -> Bi("Open VPN settings", "VPN सेटिंग खोलें")
        AccessNeed.VPN -> Bi("Allow VPN", "VPN की अनुमति दें")
        AccessNeed.WATCHER -> Bi("Open Accessibility settings", "एक्सेसिबिलिटी सेटिंग खोलें")
        AccessNeed.SHIZUKU_RUNNING -> Bi("Open Shizuku", "Shizuku खोलें")
        AccessNeed.SHIZUKU_PERMISSION -> Bi("Allow Shizuku", "Shizuku की अनुमति दें")
    }

    val notNow = Bi("Not now", "अभी नहीं")
    val continuesBySelf = Bi("The check starts by itself once this is on.", "यह चालू होते ही जाँच अपने आप शुरू हो जाएगी।")
}
