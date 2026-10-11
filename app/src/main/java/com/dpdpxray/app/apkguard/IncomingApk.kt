package com.dpdpxray.app.apkguard

/**
 * Which incoming intents APK Guard accepts. Another app (Chrome downloads, Files, WhatsApp) can open or share an APK
 * file with TraceVeil; only `content:` URIs of the APK MIME type are read. TraceVeil checks the file first and installs
 * it only if the person taps "Install anyway" and then confirms in Android's own installer.
 */
object IncomingApk {
    const val APK_MIME = "application/vnd.android.package-archive"
    const val ACTION_OPEN_INSTALLED = "com.dpdpxray.app.apkguard.OPEN_INSTALLED"
    const val EXTRA_PACKAGE = "com.dpdpxray.app.apkguard.PACKAGE"

    private const val VIEW = "android.intent.action.VIEW"
    private const val SEND = "android.intent.action.SEND"

    /** Deprecated by Android but still sent by some file managers when an APK is tapped. */
    private const val INSTALL_PACKAGE = "android.intent.action.INSTALL_PACKAGE"
    private const val CONTENT = "content://"

    const val NO_FILE = "No file came with this request. Open the APK again from the app where you found it (for example Files, Chrome downloads or WhatsApp)."
    const val FILE_URI = "This app handed over the APK as a file path, which Android no longer lets apps read. Open it from the Files app or from Chrome's downloads instead, or share it to TraceVeil."
    const val NOT_ON_PHONE = "TraceVeil reads only APK files that another app on this phone hands over. Download the file first, then open it from Files or Chrome's downloads."

    sealed interface Parsed {
        /** A readable content URI to check. */
        data class Check(val uri: String) : Parsed

        /** An APK request TraceVeil cannot read; [reason] is shown on the result screen. */
        data class Unreadable(val reason: String) : Parsed
    }

    /**
     * What to do with an incoming intent: check a content URI, explain why an APK request cannot be read, or null for
     * every intent that is not an APK handed to TraceVeil (the app then starts normally).
     */
    fun parse(action: String?, type: String?, data: String?, stream: String?): Parsed? {
        if (action != VIEW && action != SEND && action != INSTALL_PACKAGE) return null
        if (!type.equals(APK_MIME, ignoreCase = true)) return null
        val uri = (if (action == SEND) stream else data)?.trim()?.takeIf { it.isNotEmpty() } ?: return Parsed.Unreadable(NO_FILE)
        return when {
            uri.startsWith(CONTENT, ignoreCase = true) && uri.length > CONTENT.length -> Parsed.Check(uri)
            uri.startsWith(CONTENT, ignoreCase = true) -> Parsed.Unreadable(NO_FILE)
            uri.startsWith("file:", ignoreCase = true) -> Parsed.Unreadable(FILE_URI)
            else -> Parsed.Unreadable(NOT_ON_PHONE)
        }
    }

    /** The content URI to check, or null when the intent is not a readable APK file handed to us. */
    fun pickUri(action: String?, type: String?, data: String?, stream: String?): String? =
        (parse(action, type, data, stream) as? Parsed.Check)?.uri

    /** A tapped new-install notification opens that package's result, and nothing else. */
    fun installedRoute(action: String?, pkg: String?): String? =
        if (action == ACTION_OPEN_INSTALLED && ApkGuardRoutes.isPackageName(pkg)) ApkGuardRoutes.app(pkg!!) else null
}
