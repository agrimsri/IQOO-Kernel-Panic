package com.dpdpxray.app.apkguard

/**
 * Which incoming intents APK Guard accepts. Another app (WhatsApp, Files) can open or share an APK file with
 * TraceVeil; only `content:` URIs of the APK MIME type are read. TraceVeil never installs the file.
 */
object IncomingApk {
    const val APK_MIME = "application/vnd.android.package-archive"
    const val ACTION_OPEN_INSTALLED = "com.dpdpxray.app.apkguard.OPEN_INSTALLED"
    const val EXTRA_PACKAGE = "com.dpdpxray.app.apkguard.PACKAGE"

    private const val VIEW = "android.intent.action.VIEW"
    private const val SEND = "android.intent.action.SEND"

    /** The content URI to check, or null when the intent is not an APK file handed to us. */
    fun pickUri(action: String?, type: String?, data: String?, stream: String?): String? {
        if (!type.equals(APK_MIME, ignoreCase = true)) return null
        val uri = when (action) {
            VIEW -> data
            SEND -> stream
            else -> null
        } ?: return null
        return uri.takeIf { it.startsWith("content://", ignoreCase = true) }
    }

    /** A tapped new-install notification opens that package's result, and nothing else. */
    fun installedRoute(action: String?, pkg: String?): String? =
        if (action == ACTION_OPEN_INSTALLED && ApkGuardRoutes.isPackageName(pkg)) ApkGuardRoutes.app(pkg!!) else null
}
