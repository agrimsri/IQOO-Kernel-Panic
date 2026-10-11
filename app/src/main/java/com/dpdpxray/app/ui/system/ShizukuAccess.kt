package com.dpdpxray.app.ui.system

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.dpdpxray.app.ui.guide.Bi
import com.dpdpxray.app.ui.guide.guideLang

/** Words for the "Full check needs Shizuku" notice, in both guidance languages. */
object ShizukuCopy {
    val needsPermission = Bi(
        "Full check starts the app fresh each time, which needs Shizuku's permission. Shizuku is running; allow TraceVeil to use it.",
        "पूरी जाँच हर बार ऐप को नए सिरे से शुरू करती है, जिसके लिए Shizuku की अनुमति चाहिए। Shizuku चल रहा है; TraceVeil को इसे इस्तेमाल करने दें।",
    )
    val allow = Bi("Allow Shizuku", "Shizuku की अनुमति दें")
    val notRunning = Bi(
        "Full check starts the app fresh each time, which needs the Shizuku helper app to be running. Open Shizuku, start it, then come back.",
        "पूरी जाँच हर बार ऐप को नए सिरे से शुरू करती है, जिसके लिए Shizuku सहायक ऐप का चालू होना ज़रूरी है। Shizuku खोलें, उसे शुरू करें, फिर वापस आएँ।",
    )
    val open = Bi("Open Shizuku", "Shizuku खोलें")
}

/**
 * Shown where the Full check is offered but Shizuku is not ready: says what is missing and gives the one button that
 * fixes it (ask for Shizuku's permission when it is running, otherwise open the Shizuku app). The sheet re-checks the
 * state while it is open, so Full becomes selectable as soon as the permission is granted.
 */
@Composable
fun ShizukuAccessNotice(running: Boolean, onAllow: () -> Unit, onOpenShizuku: () -> Unit, modifier: Modifier = Modifier) {
    val lang = guideLang()
    val text = (if (running) ShizukuCopy.needsPermission else ShizukuCopy.notRunning).pick(lang)
    val action = (if (running) ShizukuCopy.allow else ShizukuCopy.open).pick(lang) to (if (running) onAllow else onOpenShizuku)
    Notice(text, modifier, kind = NoticeKind.CAUTION, action = action)
}

/** What the check sheets need to help someone get Shizuku ready: its running state and the two fixing actions. */
data class ShizukuHelp(val running: Boolean, val onAllow: () -> Unit, val onOpenShizuku: () -> Unit)
