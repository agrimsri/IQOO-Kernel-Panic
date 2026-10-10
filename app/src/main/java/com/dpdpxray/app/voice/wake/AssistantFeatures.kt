package com.dpdpxray.app.voice.wake

import com.dpdpxray.app.ui.AuditRoutes

/** Features reachable from launcher shortcuts and "Hey Google, open TraceVeil …" (App Actions OPEN_APP_FEATURE). */
enum class AssistantFeature(val id: String) {
    CHECK_APP("check_app"),
    CHECK_APK("check_apk"),
    ASK_LAST_CHECK("ask_last_check"),
}

sealed interface AssistantRoute {
    data class Navigate(val route: String) : AssistantRoute
    data class Notice(val message: String) : AssistantRoute
}

/**
 * Maps the `feature` extra (a shortcut id, or the feature text Google Assistant passes) to an existing in-app
 * destination. It only navigates: it never starts an audit, grants a permission or opens the microphone.
 */
object AssistantFeatures {
    const val EXTRA_FEATURE = "feature"
    const val PICK_ROUTE = "pick"

    /** Added by the APK-check work; until that route exists in the nav graph, a notice is shown instead. */
    const val APK_GUARD_ROUTE = "apkguard"

    private val LAST = setOf("last", "latest", "recent", "previous", "newest")
    private val CHECK_NOUNS = setOf("check", "audit", "result", "results", "report", "scan")
    private val CHECK_VERBS = setOf("check", "audit", "scan", "test", "inspect")

    fun parse(raw: String?): AssistantFeature? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        AssistantFeature.entries.firstOrNull { it.id == text }?.let { return it }
        val words = WakePhrase.tokens(text).toSet()
        return when {
            "apk" in words || "apks" in words || "apkguard" in words -> AssistantFeature.CHECK_APK
            words.any { it in LAST } && words.any { it in CHECK_NOUNS } -> AssistantFeature.ASK_LAST_CHECK
            words.any { it in CHECK_VERBS } && ("app" in words || "apps" in words) -> AssistantFeature.CHECK_APP
            else -> null
        }
    }

    fun route(feature: AssistantFeature, latestAuditId: String?, apkRouteAvailable: Boolean): AssistantRoute = when (feature) {
        AssistantFeature.CHECK_APP -> AssistantRoute.Navigate(PICK_ROUTE)
        AssistantFeature.CHECK_APK ->
            if (apkRouteAvailable) AssistantRoute.Navigate(APK_GUARD_ROUTE)
            else AssistantRoute.Notice("Checking an APK file is not available in this version of TraceVeil.")
        AssistantFeature.ASK_LAST_CHECK -> when {
            latestAuditId == null -> AssistantRoute.Notice("No saved check to ask about yet. Run a check first, or open the sample audit.")
            else -> runCatching { AuditRoutes.voice(latestAuditId) }.getOrNull()?.let { AssistantRoute.Navigate(it) }
                ?: AssistantRoute.Notice("The latest saved check could not be opened.")
        }
    }
}
