package com.dpdpxray.app.trust

import com.dpdpxray.app.voice.ApiEnv
import com.dpdpxray.core.evidence.EvidenceSignatures
import com.dpdpxray.core.model.ScoreBand
import java.util.Locale

/** A permission TraceVeil requests, as Android reports it right now. [granted] null = Android did not say. */
data class HeldPermission(val name: String, val granted: Boolean?)

/** One row of the permissions card: a plain title and purpose, the raw name, and the live status. */
data class PermissionLine(val name: String, val title: String, val purpose: String, val status: String, val described: Boolean)

/** A statement on the trust screen: one short line, with detail behind "Learn more". */
data class Claim(val title: String, val line: String, val detail: String? = null)

/**
 * One way TraceVeil's own code uses the network. [sourceFile] (relative to the app's source root) is the only file
 * that does it; NetworkCodeScanTest fails if any other file starts using a network API, so this list cannot go stale.
 */
data class NetworkPath(val sourceFile: String, val title: String, val line: String)

/**
 * Everything the "Why you can trust TraceVeil" screen says, as plain functions so each statement is unit-tested
 * against the code it describes. Nothing here reads the phone; [TrustReader] does that and passes the facts in.
 */
object TrustText {
    const val TITLE = "Why you can trust TraceVeil"
    const val NOT_DESCRIBED = "Purpose not described"

    /**
     * Plain-language title and purpose for a permission TraceVeil requests, or null when there is none.
     * [onlineVoiceAvailable] is false in a build made without the optional online voice, which then is not mentioned.
     */
    fun purpose(permission: String, ownPackage: String, onlineVoiceAvailable: Boolean = ApiEnv.ONLINE_VOICE_AVAILABLE): Pair<String, String>? = when (permission) {
        "android.permission.INTERNET" -> "Internet" to
            if (onlineVoiceAvailable) "Relays the checked app's DNS lookups during a check, and opens a website you ask TraceVeil to check. The optional online voice and wake-word model download also use it, only if you turn them on."
            else "Relays the checked app's DNS lookups during a check, and opens a website you ask TraceVeil to check. The optional wake-word model download also uses it, only if you start it."
        "android.permission.RECEIVE_BOOT_COMPLETED" -> "Resume after a restart" to
            "Lets Auto-guard reschedule its new-app check and weekly phone checkup after the phone restarts. Both run on this phone only and can be switched off in Settings."
        "android.permission.ACCESS_NETWORK_STATE" -> "Network state" to
            "Finds this network's DNS servers for the relay and checks whether Private DNS is on."
        "android.permission.RECORD_AUDIO" -> "Microphone" to
            "Only when you tap the microphone in Ask this audit, or while you have started Hey TraceVeil (it shows a notification). Asked when first needed."
        "android.permission.FOREGROUND_SERVICE" -> "Run with a notification" to
            "Keeps a check's VPN, or Hey TraceVeil once you start it, running with a visible notification."
        "android.permission.FOREGROUND_SERVICE_SPECIAL_USE" -> "Check VPN service type" to
            "Declares the check VPN to Android as \"Local per-app DNS observation for a user-started privacy audit\"."
        "android.permission.FOREGROUND_SERVICE_MICROPHONE" -> "Wake-word service type" to
            "Lets Hey TraceVeil listen only after you start it, with a notification. Off by default."
        "android.permission.POST_NOTIFICATIONS" -> "Notifications" to
            "Shows the running-check notification, APK Guard's new-install and new-download alerts and the Hey TraceVeil notification."
        "android.permission.REQUEST_INSTALL_PACKAGES" -> "Install anyway (Android asks you)" to
            "Only when you tap Install anyway after checking an APK file: TraceVeil hands that exact file to Android's installer, which asks you to confirm. " +
            "Android also needs you to allow TraceVeil once under Install unknown apps; TraceVeil cannot switch that on itself."
        "android.permission.QUERY_ALL_PACKAGES" -> "See installed apps" to
            "Lists your installed apps so you can pick one to check, or to inspect in APK Guard. The list stays on this phone."
        "$ownPackage.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" -> "Private broadcasts (added by AndroidX)" to
            "Added automatically by the AndroidX library so that other apps cannot call TraceVeil's internal broadcast receivers."
        "moe.shizuku.manager.permission.API_V23" -> "Shizuku (optional)" to
            "Only if you install Shizuku and allow TraceVeil: opens the checked app and returns to TraceVeil during a check, and, " +
            "when you approve a reset, stops the checked app and clears its data."
        else -> null
    }

    fun status(granted: Boolean?): String = when (granted) {
        true -> "Granted"
        false -> "Not granted"
        null -> "Not reported"
    }

    /** Described permissions first, in the order Android lists them; anything unknown is still shown, never hidden. */
    fun permissionLines(
        held: List<HeldPermission>,
        ownPackage: String,
        onlineVoiceAvailable: Boolean = ApiEnv.ONLINE_VOICE_AVAILABLE,
    ): List<PermissionLine> = held.distinctBy { it.name }.map { p ->
        val described = purpose(p.name, ownPackage, onlineVoiceAvailable)
        PermissionLine(p.name, described?.first ?: shortName(p.name), described?.second ?: NOT_DESCRIBED, status(p.granted), described != null)
    }.sortedBy { !it.described }

    /** "android.permission.RECORD_AUDIO" → "RECORD_AUDIO". */
    fun shortName(permission: String): String = permission.substringAfterLast('.')

    /** Special access that is not a manifest permission: the check VPN. */
    val vpnAccess = Claim(
        "Local VPN, during a check",
        "Local and per-app: only the app you chose (plus Google Play services, if you include them) is routed, and only its DNS lookups.",
        "Nothing is decrypted and no certificate is installed. Android asks before the first check. A quarantine run uses the same " +
            "VPN to cut that one app's internet while recording what it tries to reach.",
    )

    /** Special access that is not a manifest permission: the consent watcher. */
    val accessibilityAccess = Claim(
        "Consent watcher (accessibility)",
        "Reads only the app you chose, while a check runs: the consent screen and when you tap Accept or Reject.",
        "A screenshot of the consent screen is kept with the audit on this phone. If you switch on \"Let the agent tap Accept and " +
            "Reject\", it taps them for you. During a quarantine run it also reads Android's permission pop-ups and the titles of " +
            "Settings screens, to record what the app asked for. Other apps are ignored.",
    )

    /**
     * What stays on the phone. [onlineVoiceOn] and [backupAllowed] are read live from this install; a build made without
     * the online voice ([onlineVoiceAvailable] false) has no online-voice line at all.
     */
    fun staysOnPhone(
        onlineVoiceOn: Boolean,
        backupAllowed: Boolean?,
        onlineVoiceAvailable: Boolean = ApiEnv.ONLINE_VOICE_AVAILABLE,
    ): List<Claim> = listOfNotNull(
        Claim("No account", "TraceVeil has no sign-in and no server of its own."),
        Claim(
            "Your audits stay here",
            "Audits, consent-screen screenshots and reports are saved in TraceVeil's private storage on this phone.",
            "They leave the phone only when you tap Share or Save (Save copies to Downloads/TraceVeil). Deleting an audit deletes its folder.",
        ),
        Claim(
            "Signing key for exports",
            "Where this phone supports it, exported audit data is signed with a key kept in its secure hardware (StrongBox or TEE). The private key never leaves the phone.",
            "Key \"${EvidenceSignatures.KEY_ALIAS}\" is made the first time audit data is exported and signs only audit data you export, " +
                "or that TraceVeil Runner writes for a paired laptop. " +
                "A laptop checks the signature offline with traceveil-ci verify: it shows the file is unchanged and was signed by a key " +
                "in a phone's secure hardware that Android made for TraceVeil. It does not prove the phone was free of tampering or that " +
                "what an audit recorded is true. A phone that cannot attest such a key shows \"${EvidenceSignatures.UNAVAILABLE}\" and " +
                "exports unsigned.",
        ),
        Claim(
            "Android backup",
            when (backupAllowed) {
                false -> "Off: Android does not copy TraceVeil's data to your cloud backup."
                true -> "On: Android may include TraceVeil's data in your backup."
                null -> "Not reported by Android."
            },
        ),
        if (!onlineVoiceAvailable) null else Claim(
            "Online voice (Sarvam AI)",
            if (onlineVoiceOn) "On for this phone: each spoken answer's text (not your audio, not the audit) is sent to Sarvam AI to make speech."
            else "Off. It is off by default and can only be switched on with a settings file placed on the phone.",
            "When it is on, Settings and Ask this audit say so. Speech recognition and answers always stay on this phone.",
        ),
        Claim(
            "AI on the phone",
            "The optional AI model runs on this phone, and voice questions are recognised on it.",
            "You copy the AI model to the phone yourself; TraceVeil never downloads it. The IP-to-country table for the map is " +
                "inside the app, so the map needs no internet either.",
        ),
    )

    /**
     * Every network path in TraceVeil's own code. Kept in step with the source by NetworkCodeScanTest. A build made
     * without the online voice never reaches voice/SarvamTts.kt (ApiEnv and SpeechOutputChoice stay offline), so it is
     * not listed there.
     */
    fun networkPaths(
        onlineVoiceOn: Boolean,
        fallbackResolvers: List<String>,
        onlineVoiceAvailable: Boolean = ApiEnv.ONLINE_VOICE_AVAILABLE,
    ): List<NetworkPath> = listOfNotNull(
        NetworkPath(
            "capture/DnsForwarder.kt", "DNS relay, during a check",
            "The checked app's own DNS lookups go out from TraceVeil's socket to this network's DNS servers, then to " +
                "${fallbackResolvers.joinToString(" or ")} if they fail. They count in the numbers above.",
        ),
        NetworkPath(
            "capture/SocketConnector.kt", "Connection relay, during a deep check",
            "In a deep check, the checked app's own connections are forwarded unchanged to the servers the app chose, using " +
                "TraceVeil's sockets. TraceVeil adds nothing to them and sends nothing of its own. Android counts these bytes to " +
                "TraceVeil, so the upload figure above is not shown for a deep check.",
        ),
        if (!onlineVoiceAvailable) null else NetworkPath(
            "voice/SarvamTts.kt", "Online voice (Sarvam AI), only if switched on",
            if (onlineVoiceOn) "On: each spoken answer's text goes to api.sarvam.ai and counts in the numbers above."
            else "Off on this phone, so it sends nothing.",
        ),
        NetworkPath("webcheck/WebCheckBrowser.kt", WEBSITE_CHECK_TITLE, WEBSITE_CHECK_LINE),
        NetworkPath("webcheck/WebHostResolver.kt", WEBSITE_ADDRESSES_TITLE, WEBSITE_ADDRESSES_LINE),
        NetworkPath(
            "voice/wake/WakeModelManager.kt", "Wake-word model, only if you tap Download",
            "Android's download service fetches the Vosk model (about 40 MB) from alphacephei.com. Android normally counts those " +
                "bytes to the app that asked, so they can appear above.",
        ),
    )

    const val NETWORK_NOTHING_ELSE = "Nothing else in TraceVeil's code opens a network connection, and it contains no analytics, " +
        "advertising or crash-reporting library."

    const val WEBSITE_CHECK_TITLE = "Website check, only when you start one"
    const val WEBSITE_CHECK_LINE = "TraceVeil opens the address you typed in its own browser on this phone, only while the check is on " +
        "screen; the site sees a normal visit and its pages load as in any browser, while TraceVeil records the addresses they " +
        "request. Google Safe Browsing lookups and usage statistics are off for this browser, and it is cleared before each run " +
        "and afterwards. These bytes count in the numbers above."
    const val WEBSITE_ADDRESSES_TITLE = "Server addresses for the map, after a website check"
    const val WEBSITE_ADDRESSES_LINE = "TraceVeil asks this phone's DNS resolver for the addresses of the servers the site contacted " +
        "during the check (at most ${com.dpdpxray.app.webcheck.WebHostResolver.MAX_HOSTS}), so the server map can place them. Nothing else is sent."

    const val NETWORK_COUNTER_DETAIL = "Android counts every byte sent or received by TraceVeil's own connections, including any made " +
        "by a library inside it, so these numbers are a check on the list below. They include retries and packet headers and " +
        "reset when the phone restarts. In a DNS-only check the checked app's other traffic never passes through TraceVeil and is not counted here; in a deep check it is relayed and the figure is not shown."

    /** How results are made. Each line matches the rules engine, the score and the result labels. */
    val findings: List<Claim> = listOf(
        Claim(
            "Rules decide",
            "Each finding comes from fixed rules applied to what this phone recorded. The same evidence always gives the same result.",
            "Network findings rest on DNS lookups (app checks) or the page's requests in TraceVeil's own browser (website checks), " +
                "matched against TraceVeil's tracker catalogue, never on inspected content. A lookup or request is an attempt to contact " +
                "a server, not proof of what data was sent.",
        ),
        Claim(
            "AI explains, and is labelled",
            "Text written by the on-device AI is marked AI-inferred. Without the AI, rules-based text is used and marked Fallback.",
            "The optional AI can also read the consent screen; what only it saw is marked AI-inferred and counts half in the score. " +
                "An AI explanation that cites law it was not given is not shown.",
        ),
        Claim(
            "Every finding is tagged",
            "Observed: seen on this phone. AI-inferred: read by the on-device AI. Inferred: reasoned from vendor documentation.",
        ),
        Claim(
            "No verdicts",
            "TraceVeil's rules and screens never call an app safe or malicious, and the on-device AI is told not to.",
            "A score covers only the checks the evidence supports; even the top band, \"${ScoreBand.READY.label}\", is not a " +
                "certificate of compliance. Too little evidence gives ${ScoreBand.INCONCLUSIVE.label} instead of a score.",
        ),
    )

    /** Bytes in binary units with one decimal, e.g. 1536 → "1.5 KB"; null (Android did not report) stays "Not reported". */
    fun formatBytes(bytes: Long?): String {
        if (bytes == null || bytes < 0) return "Not reported"
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }

    /** "3 h 5 min" / "12 min" / "2 d 4 h" since the phone started. */
    fun formatUptime(millis: Long): String {
        val minutes = millis / 60_000
        val days = minutes / (24 * 60)
        val hours = (minutes / 60) % 24
        val mins = minutes % 60
        return when {
            days > 0 -> "$days d $hours h"
            minutes >= 60 -> "${minutes / 60} h $mins min"
            else -> "$mins min"
        }
    }

    /** Lower-case hex with no separators, the form `sha256sum` and `apksigner verify --print-certs` print. */
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** True for the shared Android debug certificate ("CN=Android Debug,O=Android,C=US"). */
    fun isDebugCertificate(subject: String?): Boolean = subject?.contains("CN=Android Debug", ignoreCase = true) == true

    const val COMPARE_DETAIL = "To compare with an APK you were given, run `apksigner verify --print-certs TraceVeil.apk` for the " +
        "certificate and `sha256sum TraceVeil.apk` for the file. The file hash matches only the exact same build."

    const val DEBUG_KEY_NOTE = "Signed with Android's shared debug key: fine for a demo build, but anyone can sign with it, so it " +
        "does not prove who built this copy."
}
