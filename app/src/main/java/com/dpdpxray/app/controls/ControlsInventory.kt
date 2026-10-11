package com.dpdpxray.app.controls

import com.dpdpxray.app.autoguard.UpdateWatchCopy
import com.dpdpxray.app.data.Audience
import com.dpdpxray.app.data.XraySettings
import com.dpdpxray.app.ui.guide.Bi
import com.dpdpxray.app.ui.guide.SetupItem
import com.dpdpxray.app.ui.guide.SetupKind

/*
 * "What TraceVeil uses · switch off any time": the permissions and background behaviour TraceVeil uses, with each one's
 * state on this phone, what it does, and how to switch it off. Pure, so it is unit tested (ControlsInventoryTest); the
 * screens pass in what they read (ControlsReader) and render the list (ControlsUi).
 *
 * One home per control: the check VPN, the consent watcher, Private DNS and Shizuku live in Home's Setup sheet
 * (SetupSteps and its Fix / Allow / Manage buttons), so they are not rows here. This inventory adds what the sheet does
 * not cover (notifications, the microphone, TraceVeil's own switches, saved checks) and is shown inside that same sheet
 * and on the Permissions & background page. [ControlsInventory.setupStates] lets the one summary line count both.
 *
 * Adding a row (for example a new background switch): add a [ControlId] in display order, the reading it needs to
 * [ControlReadings] (with a default, so other callers keep compiling), one line in [ControlsInventory.build], its words
 * in [ControlsText], and, for a TraceVeil switch, its store in [ControlsInventory.storeOf] (plus
 * [ControlsInventory.settingsAfter] when it lives in the settings file). Home and the full page pick it up by themselves.
 */

/** Where a row sits: Android's other permissions, TraceVeil's own switches, or what TraceVeil keeps. */
enum class ControlGroup { PHONE, TRACEVEIL, DATA }

/** Every row, in the order it is shown. */
enum class ControlId(val group: ControlGroup) {
    NOTIFICATIONS(ControlGroup.PHONE),
    MICROPHONE(ControlGroup.PHONE),
    INSTALL_UNKNOWN_APPS(ControlGroup.PHONE),
    NEW_APP_CHECK(ControlGroup.TRACEVEIL),
    UPDATE_ALERTS(ControlGroup.TRACEVEIL),
    SPEAK_ALERTS(ControlGroup.TRACEVEIL),
    WEEKLY_CHECKUP(ControlGroup.TRACEVEIL),
    WAKE_WORD(ControlGroup.TRACEVEIL),
    AUTO_LOAD_AI(ControlGroup.TRACEVEIL),
    ONLINE_VOICE(ControlGroup.TRACEVEIL),
    RUNNER(ControlGroup.TRACEVEIL),
    SIGNED_EXPORTS(ControlGroup.TRACEVEIL),
    SAVED_CHECKS(ControlGroup.DATA),
}

/**
 * A row's state, shown as a dot and one word. [NOT_REPORTED] means Android did not say (or TraceVeil chose not to ask):
 * it is never counted as off. [INFO] is a behaviour with no switch.
 */
enum class ControlState { ON, OFF, NOT_GRANTED, NOT_RUNNING, NOT_REPORTED, INFO }

/** The Android screens a row can open. TraceVeil never grants or revokes these itself. */
enum class SystemScreen {
    /** TraceVeil's notification settings. */
    NOTIFICATIONS,
    /** TraceVeil's App info, where runtime permissions such as the microphone are changed. */
    APP_PERMISSIONS,
    /** "Install unknown apps" for TraceVeil. */
    INSTALL_UNKNOWN_APPS,
}

/** Where a TraceVeil switch keeps its value. The existing code that reads that value reacts by itself. */
enum class SwitchStore {
    /** The main settings file (SettingsStore); Auto-guard's job sync follows it. */
    SETTINGS,
    /** Developer automation (AutomationStore). */
    RUNNER,
    /** The online voice's own off switch (OnlineVoiceSwitch), read by ApiEnv. */
    ONLINE_VOICE,
}

/** What a row lets the person do. */
sealed interface ControlAction {
    /** A TraceVeil switch, flipped in place. [enabled] false: shown, but it depends on another switch that is off. */
    data class Toggle(val on: Boolean, val enabled: Boolean = true) : ControlAction

    /** Android owns this switch: open [screen]. [label] is the button text. */
    data class Open(val screen: SystemScreen, val label: Bi) : ControlAction

    /** Saved checks: back them up to a file the person picks, or delete them all after a confirmation. */
    data class Checks(val saved: Int, val canBackUp: Boolean, val canDelete: Boolean) : ControlAction

    /** Nothing to switch here (information only). */
    data object None : ControlAction
}

/** One row: [title] in the current mode, [what] it does (one line), and [hint]: how to change it from this state. */
data class Control(val id: ControlId, val title: Bi, val what: Bi, val hint: Bi, val state: ControlState, val action: ControlAction) {
    val group: ControlGroup get() = id.group
}

/** Everything the inventory needs, read by the screen. Android readings are nullable where Android may not report them. */
data class ControlReadings(
    val settings: XraySettings,
    /** Every permission TraceVeil's manifest (and its libraries) requests. */
    val requestedPermissions: Set<String>,
    val notificationsOn: Boolean?,
    val micGranted: Boolean?,
    val installUnknownAllowed: Boolean?,
    /** "Hey TraceVeil" is listening right now. */
    val wakeListening: Boolean = false,
    /** This build includes the optional online voice (BuildConfig.ONLINE_VOICE_AVAILABLE). */
    val onlineVoiceInBuild: Boolean = false,
    /** api.env on this phone turns the online voice on, before the person's own switch. */
    val onlineVoiceSetUp: Boolean = false,
    /** The person's own online-voice switch. */
    val onlineVoiceAllowed: Boolean = true,
    /** Developer automation (TraceVeil Runner) is allowed. */
    val runnerOn: Boolean = false,
    val savedChecks: Int = 0,
    /** A check is running; its files must not be deleted under it. */
    val checkRunning: Boolean = false,
)

/** The one line on Home: how many are on and off; rows Android did not report are counted apart, never as off. */
data class ControlsSummary(val on: Int, val off: Int, val notReported: Int) {
    val line: Bi
        get() {
            val unknownEn = if (notReported > 0) " · $notReported not reported" else ""
            val unknownHi = if (notReported > 0) " · $notReported की जानकारी नहीं" else ""
            return Bi("$on on · $off off$unknownEn", "$on चालू · $off बंद$unknownHi")
        }
}

object ControlsInventory {
    const val MICROPHONE = "android.permission.RECORD_AUDIO"
    const val NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    const val INSTALL_PACKAGES = "android.permission.REQUEST_INSTALL_PACKAGES"

    /**
     * The rows for this phone and mode. Android permissions appear only when this install requests them; the online
     * voice only in builds that include it. Everyday mode uses plainer names, leaves out the signed-exports note, and
     * shows the laptop runner only while it is on, so it can be switched off.
     */
    fun build(r: ControlReadings, audience: Audience): List<Control> {
        val dev = audience == Audience.DEVELOPER
        val s = r.settings
        fun row(id: ControlId, what: Bi, hint: Bi, state: ControlState, action: ControlAction) =
            Control(id, ControlsText.title(id, dev), what, hint, state, action)
        fun switch(id: ControlId, on: Boolean, what: Bi = ControlsText.what(id), enabled: Boolean = true): Control {
            val effective = on && enabled
            val hint = if (!enabled) ControlsText.needsNewAppCheck else ControlsText.switchHint(id, effective)
            return row(id, what, hint, if (effective) ControlState.ON else ControlState.OFF, ControlAction.Toggle(effective, enabled))
        }
        fun open(screen: SystemScreen, label: Bi = ControlsCopy.openSetting) = ControlAction.Open(screen, label)

        return buildList {
            // ---- Android's other permissions: TraceVeil opens the right screen ------------------------------------
            if (NOTIFICATIONS in r.requestedPermissions) add(
                row(
                    ControlId.NOTIFICATIONS, ControlsText.what(ControlId.NOTIFICATIONS),
                    if (r.notificationsOn == false) ControlsText.notificationsOn else ControlsText.notificationsOff,
                    when (r.notificationsOn) { true -> ControlState.ON; false -> ControlState.OFF; null -> ControlState.NOT_REPORTED },
                    open(SystemScreen.NOTIFICATIONS),
                ),
            )
            if (MICROPHONE in r.requestedPermissions) add(
                row(
                    ControlId.MICROPHONE, ControlsText.what(ControlId.MICROPHONE),
                    if (r.micGranted == false) ControlsText.micAsked else ControlsText.micOff,
                    when (r.micGranted) { true -> ControlState.ON; false -> ControlState.NOT_GRANTED; null -> ControlState.NOT_REPORTED },
                    open(SystemScreen.APP_PERMISSIONS),
                ),
            )
            if (INSTALL_PACKAGES in r.requestedPermissions) add(
                row(
                    ControlId.INSTALL_UNKNOWN_APPS, ControlsText.what(ControlId.INSTALL_UNKNOWN_APPS),
                    if (r.installUnknownAllowed == false) ControlsText.installAsk else ControlsText.installOff,
                    when (r.installUnknownAllowed) { true -> ControlState.ON; false -> ControlState.OFF; null -> ControlState.NOT_REPORTED },
                    open(SystemScreen.INSTALL_UNKNOWN_APPS),
                ),
            )

            // ---- TraceVeil's own switches: flipped in place ---------------------------------------------------------
            add(switch(ControlId.NEW_APP_CHECK, s.autoCheckNewApps))
            // Update alerts and spoken alerts ride on the new-app check's job and live receiver.
            add(switch(ControlId.UPDATE_ALERTS, s.alertCheckedAppUpdates, enabled = s.autoCheckNewApps))
            add(switch(ControlId.SPEAK_ALERTS, s.speakApkGuardAlerts, enabled = s.autoCheckNewApps))
            add(switch(ControlId.WEEKLY_CHECKUP, s.weeklyCheckup))
            add(switch(ControlId.WAKE_WORD, s.heyTraceVeil, what = if (s.heyTraceVeil && r.wakeListening) ControlsText.wakeListening else ControlsText.what(ControlId.WAKE_WORD)))
            add(switch(ControlId.AUTO_LOAD_AI, s.autoLoadModel))
            if (r.onlineVoiceInBuild) add(
                if (r.onlineVoiceSetUp) switch(ControlId.ONLINE_VOICE, r.onlineVoiceAllowed)
                else row(ControlId.ONLINE_VOICE, ControlsText.voiceNotSetUp, ControlsText.voiceNotSetUpHint, ControlState.OFF, ControlAction.None),
            )
            if (dev || r.runnerOn) add(switch(ControlId.RUNNER, r.runnerOn))
            if (dev) add(row(ControlId.SIGNED_EXPORTS, ControlsText.what(ControlId.SIGNED_EXPORTS), ControlsText.signedHint, ControlState.INFO, ControlAction.None))

            // ---- What TraceVeil keeps ---------------------------------------------------------------------------------
            val n = r.savedChecks
            add(
                row(
                    ControlId.SAVED_CHECKS, ControlsText.saved(n), ControlsText.checksHint,
                    ControlState.INFO, ControlAction.Checks(n, canBackUp = n > 0, canDelete = n > 0 && !r.checkRunning),
                ),
            )
        }
    }

    /** The groups present in [controls], in display order. */
    fun groups(controls: List<Control>): List<ControlGroup> = ControlGroup.entries.filter { g -> controls.any { it.group == g } }

    /**
     * The Setup sheet's items as states, for the one summary line: the check VPN, the consent watcher and Shizuku.
     * Private DNS is the phone's own setting, not something TraceVeil uses, so it is not counted. With another app's VPN
     * on, TraceVeil does not ask Android about its own VPN (asking could stop that VPN), so it is not reported, never off.
     */
    fun setupStates(items: List<SetupItem>, otherVpnActive: Boolean): List<ControlState> = items.mapNotNull { item ->
        when (item.kind) {
            SetupKind.VPN -> when {
                otherVpnActive -> ControlState.NOT_REPORTED
                item.done -> ControlState.ON
                else -> ControlState.NOT_GRANTED
            }
            SetupKind.WATCHER -> if (item.done) ControlState.ON else ControlState.OFF
            SetupKind.SHIZUKU -> when {
                item.done -> ControlState.ON
                item.fixable -> ControlState.NOT_GRANTED
                else -> ControlState.NOT_RUNNING
            }
            SetupKind.PRIVATE_DNS -> null
        }
    }

    /** On and off counts over the switchable rows and [setup]; information and data rows are not counted. */
    fun summary(controls: List<Control>, setup: List<ControlState> = emptyList()): ControlsSummary {
        val states = controls.filter { it.group != ControlGroup.DATA }.map { it.state } + setup
        return ControlsSummary(
            on = states.count { it == ControlState.ON },
            off = states.count { it in setOf(ControlState.OFF, ControlState.NOT_GRANTED, ControlState.NOT_RUNNING) },
            notReported = states.count { it == ControlState.NOT_REPORTED },
        )
    }

    /** Where a TraceVeil switch writes; null for Android's permissions and information rows. */
    fun storeOf(id: ControlId): SwitchStore? = when (id) {
        ControlId.NEW_APP_CHECK, ControlId.UPDATE_ALERTS, ControlId.SPEAK_ALERTS, ControlId.WEEKLY_CHECKUP,
        ControlId.WAKE_WORD, ControlId.AUTO_LOAD_AI -> SwitchStore.SETTINGS
        ControlId.RUNNER -> SwitchStore.RUNNER
        ControlId.ONLINE_VOICE -> SwitchStore.ONLINE_VOICE
        else -> null
    }

    /** The settings after switching [id] to [on]: exactly the same keys Settings writes. Null when [id] is not stored there. */
    fun settingsAfter(id: ControlId, on: Boolean, s: XraySettings): XraySettings? = when (id) {
        ControlId.NEW_APP_CHECK -> s.copy(autoCheckNewApps = on)
        ControlId.UPDATE_ALERTS -> s.copy(alertCheckedAppUpdates = on)
        ControlId.SPEAK_ALERTS -> s.copy(speakApkGuardAlerts = on)
        ControlId.WEEKLY_CHECKUP -> s.copy(weeklyCheckup = on)
        ControlId.WAKE_WORD -> s.copy(heyTraceVeil = on)
        ControlId.AUTO_LOAD_AI -> s.copy(autoLoadModel = on)
        else -> null
    }

    /** Switching the wake word off stops a listening session first, as Settings does; switching it on never starts one. */
    fun stopsListening(id: ControlId, on: Boolean): Boolean = id == ControlId.WAKE_WORD && !on
}

/** The words around the rows, in both guidance languages. */
object ControlsCopy {
    val title = Bi("What TraceVeil uses", "TraceVeil क्या इस्तेमाल करता है")
    val switchOffAnyTime = Bi("Switch off any time", "कभी भी बंद करें")
    val manage = Bi("Manage", "प्रबंधित करें")
    val reading = Bi("Reading this phone's settings…", "इस फ़ोन की सेटिंग पढ़ी जा रही हैं…")
    val pageTitle = Bi("Permissions & background", "अनुमतियाँ और बैकग्राउंड")
    val pageRow = Bi("See and switch off what TraceVeil uses", "देखें और बंद करें कि TraceVeil क्या इस्तेमाल करता है")
    val pageIntro = Bi(
        "Everything TraceVeil uses on this phone, read again each time you come back. Switch any of it off at any time.",
        "इस फ़ोन पर TraceVeil जो कुछ इस्तेमाल करता है, सब यहाँ है; हर बार लौटने पर फिर से पढ़ा जाता है। इनमें से कुछ भी कभी भी बंद करें।",
    )
    val installTime = Bi(
        "Internet access and the list of installed apps come with the install and cannot be switched off one by one. The trust page lists every permission.",
        "इंटरनेट और इंस्टॉल किए गए ऐप की सूची इंस्टॉल के साथ मिलती हैं और इन्हें अलग-अलग बंद नहीं किया जा सकता। भरोसा पेज पर हर अनुमति की सूची है।",
    )
    val openTrust = Bi("Open the trust page", "भरोसा पेज खोलें")

    val openSetting = Bi("Open setting", "सेटिंग खोलें")
    val backUp = Bi("Back up", "बैकअप लें")
    val deleteAll = Bi("Delete all", "सब डिलीट करें")
    val cancel = Bi("Cancel", "रद्द करें")

    fun groupTitle(g: ControlGroup): Bi = when (g) {
        ControlGroup.PHONE -> Bi("Other phone permissions", "फ़ोन की दूसरी अनुमतियाँ")
        ControlGroup.TRACEVEIL -> Bi("TraceVeil's own switches", "TraceVeil के अपने स्विच")
        ControlGroup.DATA -> Bi("Your data", "आपका डेटा")
    }

    fun groupFooter(g: ControlGroup): Bi? = when (g) {
        ControlGroup.PHONE -> Bi(
            "Android keeps these switches. TraceVeil opens the right screen and never changes them itself.",
            "ये स्विच Android के पास हैं। TraceVeil सही स्क्रीन खोलता है और इन्हें खुद कभी नहीं बदलता।",
        )
        ControlGroup.TRACEVEIL -> Bi("A change here takes effect at once.", "यहाँ किया बदलाव तुरंत लागू होता है।")
        ControlGroup.DATA -> Bi(
            "Checks stay in TraceVeil's private storage until you back them up, share or delete them.",
            "जाँचें TraceVeil की निजी स्टोरेज में रहती हैं, जब तक आप उनका बैकअप, शेयर या डिलीट न करें।",
        )
    }

    /** The state chip's word. */
    fun state(s: ControlState): Bi = when (s) {
        ControlState.ON -> Bi("On", "चालू")
        ControlState.OFF -> Bi("Off", "बंद")
        ControlState.NOT_GRANTED -> Bi("Not granted", "अनुमति नहीं")
        ControlState.NOT_RUNNING -> Bi("Not running", "चल नहीं रहा")
        ControlState.NOT_REPORTED -> Bi("Not reported", "जानकारी नहीं")
        ControlState.INFO -> Bi("No switch", "कोई स्विच नहीं")
    }

    fun deleteTitle(n: Int) = if (n == 1) Bi("Delete 1 saved check?", "1 सेव की गई जाँच डिलीट करें?")
    else Bi("Delete $n saved checks?", "$n सेव की गई जाँचें डिलीट करें?")

    fun deleteBody(n: Int) = Bi(
        "This removes ${if (n == 1) "1 saved check" else "$n saved checks"} and their screenshots from this phone. It cannot be undone. Back up first if you want to keep a copy.",
        "इससे इस फ़ोन से $n सेव की गई जाँच और उनके स्क्रीनशॉट हट जाएँगे। इसे वापस नहीं किया जा सकता। कॉपी रखनी हो तो पहले बैकअप लें।",
    )
}

/** Each row's name (developer and everyday), what it does and the how-to-change lines, in both languages. */
internal object ControlsText {
    private class Names(val developer: Bi, val everyday: Bi = developer)

    private val names = mapOf(
        ControlId.NOTIFICATIONS to Names(Bi("Notifications", "नोटिफ़िकेशन")),
        ControlId.MICROPHONE to Names(Bi("Microphone", "माइक्रोफ़ोन")),
        ControlId.INSTALL_UNKNOWN_APPS to Names(Bi("Install unknown apps", "अनजान ऐप इंस्टॉल करना"), Bi("Install apps from files", "फ़ाइल से ऐप इंस्टॉल करना")),
        ControlId.NEW_APP_CHECK to Names(Bi("Auto-guard new-app check", "ऑटो-गार्ड: नए ऐप की जाँच"), Bi("Check new apps automatically", "नए ऐप अपने-आप जाँचें")),
        ControlId.UPDATE_ALERTS to Names(UpdateWatchCopy.switchTitle),
        ControlId.SPEAK_ALERTS to Names(Bi("Speak APK Guard alerts", "APK Guard सूचनाएँ बोलकर सुनाएँ"), Bi("Read new-app alerts aloud", "नए ऐप की सूचना बोलकर सुनाएँ")),
        ControlId.WEEKLY_CHECKUP to Names(Bi("Weekly phone checkup", "साप्ताहिक फ़ोन जाँच")),
        ControlId.WAKE_WORD to Names(Bi("“Hey TraceVeil” wake word", "“Hey TraceVeil” वेक वर्ड"), Bi("Start with “Hey TraceVeil”", "“Hey TraceVeil” बोलकर शुरू करें")),
        ControlId.AUTO_LOAD_AI to Names(Bi("Load Gemma when a check starts", "जाँच शुरू होने पर Gemma लोड करें"), Bi("Load on-device AI for each check", "हर जाँच के लिए फ़ोन पर AI लोड करें")),
        ControlId.ONLINE_VOICE to Names(Bi("Online voice (Sarvam AI)", "ऑनलाइन आवाज़ (Sarvam AI)"), Bi("Online voice for spoken answers", "बोले गए जवाबों के लिए ऑनलाइन आवाज़")),
        ControlId.RUNNER to Names(Bi("Allow TraceVeil Runner", "TraceVeil Runner की अनुमति"), Bi("Let a laptop start checks", "लैपटॉप को जाँच शुरू करने दें")),
        ControlId.SIGNED_EXPORTS to Names(Bi("Signed exports", "हस्ताक्षर वाले एक्सपोर्ट")),
        ControlId.SAVED_CHECKS to Names(Bi("Your checks", "आपकी जाँचें")),
    )

    fun title(id: ControlId, developer: Boolean): Bi = names.getValue(id).let { if (developer) it.developer else it.everyday }

    private val whats = mapOf(
        ControlId.NOTIFICATIONS to Bi(
            "Shows new-app, update and weekly checkup alerts.",
            "नए ऐप, अपडेट और साप्ताहिक जाँच की सूचनाएँ दिखाता है।",
        ),
        ControlId.MICROPHONE to Bi(
            "Used only by the voice copilot and “Hey TraceVeil”, after you start them.",
            "सिर्फ़ वॉइस कोपायलट और “Hey TraceVeil” के लिए, जब आप उन्हें शुरू करें।",
        ),
        ControlId.INSTALL_UNKNOWN_APPS to Bi(
            "Lets TraceVeil hand an app file to Android's installer. Android still asks you.",
            "TraceVeil को ऐप फ़ाइल Android के इंस्टॉलर को देने देता है। Android फिर भी आपसे पूछता है।",
        ),
        ControlId.NEW_APP_CHECK to Bi(
            "Looks for newly installed apps about every 15 minutes, even when TraceVeil is closed.",
            "लगभग हर 15 मिनट में नए इंस्टॉल हुए ऐप देखता है, TraceVeil बंद होने पर भी।",
        ),
        ControlId.UPDATE_ALERTS to Bi(
            "One notification when an app you checked gets a new version.",
            "जाँचे गए ऐप का नया वर्ज़न आने पर एक सूचना।",
        ),
        ControlId.SPEAK_ALERTS to Bi(
            "Reads new-app alerts aloud with an offline voice already on this phone.",
            "नए ऐप की सूचना फ़ोन में पहले से मौजूद ऑफ़लाइन आवाज़ से बोलकर सुनाता है।",
        ),
        ControlId.WEEKLY_CHECKUP to Bi(
            "Once a week, counts apps with powerful access and notifies you if any need review.",
            "हफ़्ते में एक बार ताक़तवर पहुँच वाले ऐप गिनता है और समीक्षा ज़रूरी हो तो बताता है।",
        ),
        ControlId.WAKE_WORD to Bi(
            "The microphone starts only when you press Start in Settings.",
            "माइक्रोफ़ोन सिर्फ़ सेटिंग में Start दबाने पर शुरू होता है।",
        ),
        ControlId.AUTO_LOAD_AI to Bi(
            "Only when a model is already on this phone. It never downloads one.",
            "सिर्फ़ तब, जब मॉडल पहले से इस फ़ोन पर हो। यह कभी डाउनलोड नहीं करता।",
        ),
        ControlId.ONLINE_VOICE to Bi(
            "Sends each spoken answer's text (not your audio) to Sarvam AI to make speech.",
            "हर बोले गए जवाब का टेक्स्ट (आपकी आवाज़ नहीं) आवाज़ बनाने के लिए Sarvam AI को भेजता है।",
        ),
        ControlId.RUNNER to Bi(
            "A laptop or CI connected over USB (adb) can start a check on this phone.",
            "USB (adb) से जुड़ा लैपटॉप या CI इस फ़ोन पर जाँच शुरू कर सकता है।",
        ),
        ControlId.SIGNED_EXPORTS to Bi(
            "When you export audit data, a key kept in this phone's hardware signs the file. Nothing is sent.",
            "जब आप ऑडिट डेटा एक्सपोर्ट करते हैं, इस फ़ोन के हार्डवेयर में रखी एक कुंजी फ़ाइल पर हस्ताक्षर करती है। कुछ भी भेजा नहीं जाता।",
        ),
    )

    fun what(id: ControlId): Bi = whats.getValue(id)

    val wakeListening = Bi(
        "Listening now, on this phone only. Switching off stops it.",
        "अभी सुन रहा है, सिर्फ़ इसी फ़ोन पर। बंद करते ही रुक जाएगा।",
    )
    val voiceNotSetUp = Bi(
        "Not set up on this phone. Spoken answers use the phone's own voice.",
        "इस फ़ोन पर सेट नहीं है। जवाब फ़ोन की अपनी आवाज़ में बोले जाते हैं।",
    )
    val voiceNotSetUpHint = Bi("Nothing to switch off: nothing is sent.", "बंद करने को कुछ नहीं: कुछ भी भेजा नहीं जाता।")

    fun saved(n: Int): Bi = when (n) {
        0 -> Bi("None saved yet", "अभी कोई सेव नहीं")
        1 -> Bi("1 saved on this phone", "इस फ़ोन पर 1 सेव")
        else -> Bi("$n saved on this phone", "इस फ़ोन पर $n सेव")
    }

    val checksHint = Bi(
        "Back up to a file you pick, or delete them all (it asks first).",
        "अपनी चुनी फ़ाइल में बैकअप लें, या सब डिलीट करें (पहले पूछा जाएगा)।",
    )

    // ---- how to change each Android permission, from its current state -----------------------------------------------
    val notificationsOff = Bi(
        "To switch off: open the setting and turn notifications off.",
        "बंद करने के लिए: सेटिंग खोलें और नोटिफ़िकेशन बंद करें।",
    )
    val notificationsOn = Bi("Alerts cannot appear while this is off.", "यह बंद रहने पर सूचनाएँ नहीं दिखेंगी।")
    val micOff = Bi(
        "To switch off: App info › Permissions › Microphone › Don't allow.",
        "बंद करने के लिए: ऐप इन्फ़ो › अनुमतियाँ › माइक्रोफ़ोन › अनुमति न दें।",
    )
    val micAsked = Bi("Asked only when you first use voice.", "पहली बार आवाज़ इस्तेमाल करने पर ही पूछा जाता है।")
    val installOff = Bi(
        "To switch off: open the setting and turn Allow from this source off.",
        "बंद करने के लिए: सेटिंग खोलें और Allow from this source बंद करें।",
    )
    val installAsk = Bi("Off. Android asks before any install.", "बंद। किसी भी इंस्टॉल से पहले Android पूछता है।")

    // ---- TraceVeil's own switches -------------------------------------------------------------------------------------
    val needsNewAppCheck = Bi(
        "Works only while the new-app check is on.",
        "यह सिर्फ़ नए ऐप की जाँच चालू रहने पर काम करता है।",
    )
    val signedHint = Bi("No switch: it runs only when you export.", "कोई स्विच नहीं: यह सिर्फ़ एक्सपोर्ट करने पर चलता है।")

    fun switchHint(id: ControlId, on: Boolean): Bi = when (id) {
        ControlId.NEW_APP_CHECK, ControlId.WEEKLY_CHECKUP ->
            if (on) Bi("Switching off cancels its background job at once.", "बंद करते ही इसका बैकग्राउंड काम तुरंत रद्द हो जाता है।")
            else Bi("Off: nothing runs in the background for this.", "बंद: इसके लिए बैकग्राउंड में कुछ नहीं चलता।")
        ControlId.WAKE_WORD ->
            if (on) Bi("Switching off stops listening at once.", "बंद करते ही सुनना तुरंत रुक जाता है।")
            else Bi("Off by default. Switching on never starts the microphone.", "डिफ़ॉल्ट रूप से बंद। चालू करने से माइक्रोफ़ोन शुरू नहीं होता।")
        ControlId.ONLINE_VOICE ->
            if (on) Bi(
                "Switching off applies from the next time the voice copilot opens; then nothing is sent.",
                "बंद करना अगली बार वॉइस कोपायलट खुलने से लागू होगा; फिर कुछ नहीं भेजा जाएगा।",
            )
            else Bi("Off: answers use the phone's own voice and nothing is sent.", "बंद: जवाब फ़ोन की अपनी आवाज़ में होते हैं और कुछ नहीं भेजा जाता।")
        ControlId.RUNNER ->
            if (on) Bi("Switching off refuses run requests from a laptop at once.", "बंद करते ही लैपटॉप से आए अनुरोध तुरंत मना हो जाते हैं।")
            else Bi("Off: run requests from a laptop are refused.", "बंद: लैपटॉप से आए अनुरोध मना कर दिए जाते हैं।")
        else ->
            if (on) Bi("Switching off takes effect at once.", "बंद करते ही तुरंत लागू होता है।")
            else Bi("Off. Switch on here at any time.", "बंद। यहाँ कभी भी चालू करें।")
    }
}
