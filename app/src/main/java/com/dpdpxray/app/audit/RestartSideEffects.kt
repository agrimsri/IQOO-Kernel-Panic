package com.dpdpxray.app.audit

import com.dpdpxray.app.ui.guide.Bi
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.RestartChoice

/**
 * Things the audited app runs that stopping it (`am force-stop`, before each plan) would switch off or interrupt.
 * Android removes a force-stopped app's accessibility service from the enabled list, so the person would have to turn it
 * on again; the others are interrupted until the app runs again. Saved by name in PlanConditions.restartSideEffects.
 */
enum class RestartSideEffect { ACCESSIBILITY, VPN, KEYBOARD, HOME, SMS, DEVICE_ADMIN, FOREGROUND_SERVICE, NOTIFICATION_LISTENER }

/** One thing found, with a short detail where the phone gives one (what a foreground service is doing). */
data class FoundSideEffect(val kind: RestartSideEffect, val detail: String? = null)

/** How a check treats an app a restart would disturb. AUTO: the safe default (do not restart it). */
enum class RestartPolicy { AUTO, SKIP, ALWAYS }

/**
 * What the phone says about one app, read without changing anything. Each reading is null when this phone or this
 * build cannot read it, and an unreadable reading never counts as "found" or as "none".
 */
interface SideEffectProbe {
    /** Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, as stored: "pkg/.Service:pkg2/.Service". */
    fun enabledAccessibilityServices(): String?

    /** Packages of the services AccessibilityManager lists as enabled. */
    fun accessibilityPackages(): Set<String>?

    /** Settings.Secure.DEFAULT_INPUT_METHOD: "pkg/.KeyboardService". */
    fun defaultInputMethod(): String?

    /** The package that answers the Home intent by default. */
    fun homePackage(): String?

    /** The default SMS app. */
    fun smsPackage(): String?

    /** Packages of the active device admins. */
    fun deviceAdminPackages(): Set<String>?

    /** Packages allowed to read notifications. */
    fun notificationListenerPackages(): Set<String>?

    /** The always-on VPN app (read through the Shizuku shell). */
    suspend fun alwaysOnVpnPackage(): String?

    /** `dumpsys activity services <pkg>` (read through the Shizuku shell). */
    suspend fun runningServices(pkg: String): String?
}

/** Reads what a restart of an app would disturb. Pure apart from the [SideEffectProbe], so it is tested on the JVM. */
object RestartSideEffects {
    suspend fun detect(pkg: String, probe: SideEffectProbe): List<FoundSideEffect> {
        val found = mutableListOf<FoundSideEffect>()
        val a11y = componentsOf(probe.enabledAccessibilityServices(), pkg).isNotEmpty() || probe.accessibilityPackages()?.contains(pkg) == true
        if (a11y) found += FoundSideEffect(RestartSideEffect.ACCESSIBILITY)
        val services = parseServices(probe.runningServices(pkg), pkg)
        if (probe.alwaysOnVpnPackage()?.trim() == pkg || services?.vpnRunning == true) found += FoundSideEffect(RestartSideEffect.VPN)
        if (packageOf(probe.defaultInputMethod()) == pkg) found += FoundSideEffect(RestartSideEffect.KEYBOARD)
        if (probe.homePackage() == pkg) found += FoundSideEffect(RestartSideEffect.HOME)
        if (probe.smsPackage() == pkg) found += FoundSideEffect(RestartSideEffect.SMS)
        if (probe.deviceAdminPackages()?.contains(pkg) == true) found += FoundSideEffect(RestartSideEffect.DEVICE_ADMIN)
        services?.foregroundTypes?.takeIf { it.isNotEmpty() }?.let { types ->
            found += FoundSideEffect(RestartSideEffect.FOREGROUND_SERVICE, types.firstNotNullOfOrNull(::foregroundKind))
        }
        if (probe.notificationListenerPackages()?.contains(pkg) == true) found += FoundSideEffect(RestartSideEffect.NOTIFICATION_LISTENER)
        return found
    }

    /** Components of [pkg] in a colon-separated component list ("pkg/.Svc:other/.Svc"). */
    fun componentsOf(raw: String?, pkg: String): List<String> =
        raw.orEmpty().split(':').map { it.trim() }.filter { packageOf(it) == pkg }

    /** "pkg/.Service" or "pkg/pkg.Service" to "pkg"; null for anything else. */
    fun packageOf(component: String?): String? = component?.trim()?.substringBefore('/', "")?.takeIf { it.isNotEmpty() }

    /** What a services dump says about [pkg]'s running services: a running VPN service, and foreground service type masks. */
    data class Services(val vpnRunning: Boolean, val foregroundTypes: List<Int>)

    fun parseServices(dump: String?, pkg: String): Services? {
        if (dump == null) return null
        val records = dump.split(Regex("(?m)^\\s*\\* ServiceRecord\\{")).drop(1).filter { r -> r.substringBefore('\n').contains(" $pkg/") }
        val running = records.filter { r -> Regex("(?m)^\\s*app=ProcessRecord\\{").containsMatchIn(r) }
        val vpn = running.any { it.contains("permission=android.permission.BIND_VPN_SERVICE") }
        val fg = running.mapNotNull { r ->
            Regex("isForeground=true[^\\n]*?types=0x([0-9a-fA-F]+)").find(r)?.groupValues?.get(1)?.toLongOrNull(16)?.toInt()
                ?: if (r.contains("isForeground=true")) 0 else null
        }
        return Services(vpn, fg)
    }

    /** Android's foreground service types, as the person would say them. */
    fun foregroundKind(types: Int): String? = when {
        types and 0x2 != 0 -> "playing media"
        types and 0x8 != 0 -> "using location, for example navigation"
        types and 0x4 != 0 -> "on a call"
        types and 0x80 != 0 -> "using the microphone"
        types and 0x10 != 0 -> "connected to a device"
        else -> null
    }
}

/** The decision before each plan, in one place so the orchestrator, the sheet and the tests agree. */
object RestartDecision {
    /** [stop]: force-stop the app before the plan. [reason]: why it was left running (for the plan's conditions). */
    data class Decision(val stop: Boolean, val choice: RestartChoice?, val reason: String?, val note: String?)

    fun decide(policy: RestartPolicy, effects: List<FoundSideEffect>, resetApproved: Boolean, app: String): Decision {
        if (effects.isEmpty()) return Decision(stop = true, choice = null, reason = null, note = null)
        val what = RestartSafeguardCopy.whatList(effects)
        return when {
            resetApproved -> Decision(true, RestartChoice.RESTARTED_BY_PERSON, null,
                "Reset as you approved, although it switches off $app's $what; turn ${RestartSafeguardCopy.it(effects)} on again afterwards.")
            policy == RestartPolicy.ALWAYS -> Decision(true, RestartChoice.RESTARTED_BY_PERSON, null,
                "Restarted as you chose, although it switches off $app's $what; " + RestartSafeguardCopy.whereToTurnOn(effects) + ".")
            policy == RestartPolicy.SKIP -> Decision(false, RestartChoice.SKIPPED_BY_PERSON, "restarting it would switch off its $what",
                "Not restarted before this plan: you chose to check without restarting, because restarting $app would switch off its $what.")
            else -> Decision(false, RestartChoice.SKIPPED_BY_DEFAULT, "restarting it would switch off its $what",
                "Not restarted before this plan: restarting $app would switch off its $what, so TraceVeil left it running (the safe default).")
        }
    }
}

/** What a saved check recorded about restarts, for its result screen and its "Check again". */
object RestartReminder {
    /** Everything the check found a restart would disturb, in order. */
    fun recorded(sessions: List<AuditSession>): List<RestartSideEffect> = sessions
        .flatMap { it.conditions?.restartSideEffects.orEmpty() }
        .mapNotNull { n -> RestartSideEffect.entries.firstOrNull { it.name == n } }.distinct()

    /** What the person should turn back on: only after a restart they chose, and only things that stay off. */
    fun toTurnBackOn(sessions: List<AuditSession>): List<RestartSideEffect> {
        val restarted = sessions.filter { it.conditions?.restartChoice == RestartChoice.RESTARTED_BY_PERSON }
        return recorded(restarted).filter { it in STAYS_OFF }
    }

    private val STAYS_OFF = setOf(RestartSideEffect.ACCESSIBILITY, RestartSideEffect.VPN, RestartSideEffect.KEYBOARD,
        RestartSideEffect.NOTIFICATION_LISTENER, RestartSideEffect.FOREGROUND_SERVICE)
}

/** The words for the restart safeguard, in both guidance languages. */
object RestartSafeguardCopy {
    val title = Bi("Before you start", "शुरू करने से पहले")
    val skip = Bi("Check without restarting", "रीस्टार्ट किए बिना जाँचें")
    fun skipDetail(app: String) = Bi(
        "Recommended. $app keeps running as it is; the result may then say it was already running.",
        "सुझाया गया। $app जैसा है वैसे चलता रहता है; तब नतीजे में लिखा हो सकता है कि यह पहले से चल रहा था।",
    )
    val anyway = Bi("Restart anyway", "फिर भी रीस्टार्ट करें")
    fun anywayDetail(app: String) = Bi(
        "TraceVeil restarts $app before the check (its data stays). Afterwards the result reminds you what to turn back on.",
        "TraceVeil जाँच से पहले $app को रीस्टार्ट करता है (उसका डेटा बना रहता है)। बाद में नतीजा याद दिलाता है कि क्या फिर से चालू करना है।",
    )
    val resetCovers = Bi("Your reset approval includes stopping it.", "आपकी रीसेट मंज़ूरी में इसे रोकना भी शामिल है।")

    /** A short row title for what was found. */
    fun found(kind: RestartSideEffect) = when (kind) {
        RestartSideEffect.ACCESSIBILITY -> Bi("Its accessibility service is on", "इसकी ऐक्सेसिबिलिटी सेवा चालू है")
        RestartSideEffect.VPN -> Bi("Its VPN is on", "इसका VPN चालू है")
        RestartSideEffect.KEYBOARD -> Bi("It is your keyboard", "यह आपका कीबोर्ड है")
        RestartSideEffect.HOME -> Bi("It is your home screen", "यह आपकी होम स्क्रीन है")
        RestartSideEffect.SMS -> Bi("It is your SMS app", "यह आपका SMS ऐप है")
        RestartSideEffect.DEVICE_ADMIN -> Bi("It is a device admin app", "यह डिवाइस एडमिन ऐप है")
        RestartSideEffect.FOREGROUND_SERVICE -> Bi("It is running in the foreground", "यह सामने चल रहा है")
        RestartSideEffect.NOTIFICATION_LISTENER -> Bi("It can read notifications", "यह सूचनाएँ पढ़ सकता है")
    }

    fun warning(app: String, f: FoundSideEffect, reset: Boolean = false): Bi {
        val en = if (reset) "Resetting" else "Restarting"
        val hi = if (reset) "रीसेट" else "रीस्टार्ट"
        return when (f.kind) {
            RestartSideEffect.ACCESSIBILITY -> Bi(
                "$en $app will switch off its accessibility service (Android does this when an app is stopped). You'd need to turn it on again in Accessibility settings.",
                "$app को $hi करने से उसकी ऐक्सेसिबिलिटी सेवा बंद हो जाएगी (ऐप रोके जाने पर Android ऐसा करता है)। आपको उसे Accessibility सेटिंग में फिर से चालू करना होगा।",
            )
            RestartSideEffect.VPN -> Bi(
                "$app is running a VPN. $en it disconnects that VPN; turn it on again in $app or in VPN settings. TraceVeil's own check also needs Android's one VPN slot while it runs.",
                "$app एक VPN चला रहा है। $hi करने से वह VPN कट जाएगा; उसे $app में या VPN सेटिंग में फिर से चालू करें। जाँच के दौरान TraceVeil को भी Android की एकमात्र VPN जगह चाहिए।",
            )
            RestartSideEffect.KEYBOARD -> Bi(
                "$app is your keyboard. $en it closes the keyboard for a moment; some phones then switch to another keyboard. Check Keyboard settings afterwards.",
                "$app आपका कीबोर्ड है। $hi करने से कीबोर्ड कुछ पल के लिए बंद होगा; कुछ फ़ोन तब दूसरा कीबोर्ड चुन लेते हैं। बाद में कीबोर्ड सेटिंग देख लें।",
            )
            RestartSideEffect.HOME -> Bi(
                "$app is your home screen. $en it closes your home screen for a moment; it opens again when you press Home.",
                "$app आपकी होम स्क्रीन है। $hi करने से होम स्क्रीन कुछ पल के लिए बंद होगी; Home दबाने पर फिर खुल जाएगी।",
            )
            RestartSideEffect.SMS -> Bi(
                "$app is your default SMS app. $en it stops it for a moment; a message that arrives meanwhile may show up late.",
                "$app आपका डिफ़ॉल्ट SMS ऐप है। $hi करने से यह कुछ पल के लिए रुकेगा; उस बीच आया संदेश देर से दिख सकता है।",
            )
            RestartSideEffect.DEVICE_ADMIN -> Bi(
                "$app is a device admin app (for example to find or lock this phone). $en it stops it for a moment; its admin role stays on.",
                "$app एक डिवाइस एडमिन ऐप है (जैसे फ़ोन ढूँढने या लॉक करने के लिए)। $hi करने से यह कुछ पल के लिए रुकेगा; उसकी एडमिन भूमिका बनी रहती है।",
            )
            RestartSideEffect.FOREGROUND_SERVICE -> Bi(
                "$app is running in the foreground right now" + (f.detail?.let { " ($it)" } ?: "") + ". $en it stops that.",
                "$app अभी सामने चल रहा है" + (f.detail?.let { " ($it)" } ?: "") + "। $hi करने से वह रुक जाएगा।",
            )
            RestartSideEffect.NOTIFICATION_LISTENER -> Bi(
                "$app can read your notifications. $en it cuts that access for a moment; if it stops reacting to notifications, check Notification access in Settings.",
                "$app आपकी सूचनाएँ पढ़ सकता है। $hi करने से यह पहुँच कुछ पल के लिए कटेगी; अगर यह सूचनाओं पर काम करना बंद कर दे, तो Settings में Notification access देखें।",
            )
        }
    }

    /** The Settings screen that turns [kind] back on, as a button label; null when nothing needs turning on. */
    fun settingsLabel(kind: RestartSideEffect): Bi? = when (kind) {
        RestartSideEffect.ACCESSIBILITY -> Bi("Open Accessibility settings", "Accessibility सेटिंग खोलें")
        RestartSideEffect.VPN -> Bi("Open VPN settings", "VPN सेटिंग खोलें")
        RestartSideEffect.KEYBOARD -> Bi("Open Keyboard settings", "कीबोर्ड सेटिंग खोलें")
        RestartSideEffect.NOTIFICATION_LISTENER -> Bi("Open Notification access", "Notification access खोलें")
        RestartSideEffect.FOREGROUND_SERVICE -> Bi("Open the app", "ऐप खोलें")
        RestartSideEffect.HOME, RestartSideEffect.SMS, RestartSideEffect.DEVICE_ADMIN -> null
    }

    fun reminder(app: String, kind: RestartSideEffect) = Bi(
        "You chose to restart $app, which switched off its ${what(kind)}. " + when (kind) {
            RestartSideEffect.ACCESSIBILITY -> "Turn it on again in Accessibility settings."
            RestartSideEffect.VPN -> "Turn it on again in $app or in VPN settings."
            RestartSideEffect.KEYBOARD -> "If the keyboard changed, choose it again in Keyboard settings."
            RestartSideEffect.NOTIFICATION_LISTENER -> "If it stopped reacting to notifications, check Notification access."
            else -> "Open $app to start it again."
        },
        "आपने $app को रीस्टार्ट करना चुना, जिससे उसकी ${whatHi(kind)} बंद हो गई। " + when (kind) {
            RestartSideEffect.ACCESSIBILITY -> "उसे Accessibility सेटिंग में फिर से चालू करें।"
            RestartSideEffect.VPN -> "उसे $app में या VPN सेटिंग में फिर से चालू करें।"
            RestartSideEffect.KEYBOARD -> "अगर कीबोर्ड बदल गया हो, तो कीबोर्ड सेटिंग में उसे फिर चुनें।"
            RestartSideEffect.NOTIFICATION_LISTENER -> "अगर यह सूचनाओं पर काम नहीं कर रहा, तो Notification access देखें।"
            else -> "उसे फिर शुरू करने के लिए $app खोलें।"
        },
    )

    fun what(kind: RestartSideEffect) = when (kind) {
        RestartSideEffect.ACCESSIBILITY -> "accessibility service"
        RestartSideEffect.VPN -> "VPN"
        RestartSideEffect.KEYBOARD -> "keyboard"
        RestartSideEffect.HOME -> "home screen"
        RestartSideEffect.SMS -> "SMS handling"
        RestartSideEffect.DEVICE_ADMIN -> "device-admin app"
        RestartSideEffect.FOREGROUND_SERVICE -> "running foreground service"
        RestartSideEffect.NOTIFICATION_LISTENER -> "notification access"
    }

    private fun whatHi(kind: RestartSideEffect) = when (kind) {
        RestartSideEffect.ACCESSIBILITY -> "ऐक्सेसिबिलिटी सेवा"
        RestartSideEffect.VPN -> "VPN"
        RestartSideEffect.KEYBOARD -> "कीबोर्ड"
        RestartSideEffect.HOME -> "होम स्क्रीन"
        RestartSideEffect.SMS -> "SMS सेवा"
        RestartSideEffect.DEVICE_ADMIN -> "डिवाइस एडमिन सेवा"
        RestartSideEffect.FOREGROUND_SERVICE -> "सामने चल रही सेवा"
        RestartSideEffect.NOTIFICATION_LISTENER -> "सूचना पहुँच"
    }

    /** "accessibility service", "accessibility service and VPN", ... */
    fun whatList(effects: List<FoundSideEffect>): String {
        val names = effects.map { what(it.kind) }.distinct()
        return if (names.size <= 1) names.joinToString() else names.dropLast(1).joinToString() + " and " + names.last()
    }

    fun it(effects: List<FoundSideEffect>) = if (effects.distinctBy { it.kind }.size == 1) "it" else "them"

    fun whereToTurnOn(effects: List<FoundSideEffect>): String {
        val first = effects.first().kind
        return when {
            effects.size == 1 && first == RestartSideEffect.ACCESSIBILITY -> "turn it on again in Accessibility settings"
            effects.size == 1 && first == RestartSideEffect.VPN -> "turn it on again in the app or in VPN settings"
            else -> "turn ${it(effects)} on again afterwards"
        }
    }
}
