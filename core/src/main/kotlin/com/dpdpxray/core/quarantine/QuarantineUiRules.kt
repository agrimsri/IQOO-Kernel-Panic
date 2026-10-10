package com.dpdpxray.core.quarantine

/**
 * Pure matching rules for what the accessibility watcher records during a quarantine run: Android's own permission
 * dialogs and two sensitive settings screens. English window titles only; other languages and some OEM screens may be
 * missed (the UI says so). Nothing here ever taps or changes anything.
 */
object QuarantineUiRules {
    const val MAX_DIALOG_TEXT = 400
    private const val MAX_TEXT_PARTS = 12

    val PERMISSION_CONTROLLERS = setOf("com.android.permissioncontroller", "com.google.android.permissioncontroller")

    fun isPermissionController(packageName: String?): Boolean = packageName in PERMISSION_CONTROLLERS

    /**
     * The dialog's visible text: its permission message when one was found, otherwise its distinct visible strings
     * joined with " · ". Whitespace is collapsed and the result is capped at [MAX_DIALOG_TEXT] characters.
     */
    fun dialogText(message: String?, visibleTexts: List<String>): String? {
        val text = clean(message)?.takeIf { it.isNotEmpty() }
            ?: visibleTexts.mapNotNull { clean(it)?.takeIf(String::isNotEmpty) }.distinct().take(MAX_TEXT_PARTS)
                .joinToString(" · ").takeIf { it.isNotEmpty() }
            ?: return null
        return if (text.length <= MAX_DIALOG_TEXT) text else text.take(MAX_DIALOG_TEXT - 1) + "…"
    }

    /** The dialog names the app by its label (two characters or more) or its package name. */
    fun namesTarget(text: String, label: String, packageName: String): Boolean {
        val t = text.lowercase()
        val l = label.trim().lowercase()
        return (l.length >= 2 && l in t) || packageName.lowercase() in t
    }

    private val ACCESSIBILITY_TITLES = setOf("accessibility")
    private val OVERLAY_TITLES = setOf(
        "display over other apps", "appear on top", "draw over other apps", "display pop-up windows",
        "allow display over other apps", "floating windows",
    )

    /**
     * Which sensitive settings screen a window is, from the settings app's [className] or its exact window title.
     * Only settings-type packages count, so an app's own "Accessibility" screen never matches.
     */
    fun settingsScreen(packageName: String?, className: String?, windowTitle: String?): SensitiveSettings? {
        val pkg = packageName ?: return null
        val settingsApp = pkg == "com.android.settings" || pkg.endsWith(".settings") ||
            "securitycenter" in pkg || "permissionmanager" in pkg
        if (!settingsApp) return null
        val cls = className.orEmpty().lowercase()
        val title = clean(windowTitle)?.lowercase().orEmpty()
        return when {
            "accessibilitysettings" in cls || title in ACCESSIBILITY_TITLES -> SensitiveSettings.ACCESSIBILITY
            "overlaysettings" in cls || "drawoverlay" in cls || "manageoverlay" in cls || title in OVERLAY_TITLES ->
                SensitiveSettings.DISPLAY_OVER_OTHER_APPS
            else -> null
        }
    }

    private fun clean(s: String?): String? = s?.replace(Regex("\\s+"), " ")?.trim()
}
