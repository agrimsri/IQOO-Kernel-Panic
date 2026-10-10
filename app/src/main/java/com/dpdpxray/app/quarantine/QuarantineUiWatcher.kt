package com.dpdpxray.app.quarantine

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.dpdpxray.core.quarantine.QuarantineUiRules

/**
 * Read-only first-run watcher, active only while a quarantine run is recording ([QuarantineBus.watchedPackage]).
 * Records: the quarantined app coming to the screen, the visible text of Android's own permission dialogs, and the
 * Accessibility / Display-over-other-apps settings screens opening. It never taps, types or changes anything.
 */
class QuarantineUiWatcher(private val service: AccessibilityService, private val main: Handler) {
    private val readDialog = Runnable { readPermissionDialog() }
    private var pendingDialogAt = 0L
    private var pendingDialogPackage: String? = null

    /** The dialog text currently on screen; cleared when another window takes over, so a re-shown dialog counts again. */
    private var visibleDialogText: String? = null

    fun onEvent(event: AccessibilityEvent) {
        val target = QuarantineBus.watchedPackage ?: return
        val pkg = event.packageName?.toString() ?: return
        val t = SystemClock.elapsedRealtimeNanos()
        val stateChanged = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        when {
            pkg == target -> {
                QuarantineBus.targetOnScreen(t)
                if (stateChanged) visibleDialogText = null
            }
            QuarantineUiRules.isPermissionController(pkg) -> {
                if (stateChanged || event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                    if (!main.hasCallbacks(readDialog)) pendingDialogAt = t
                    pendingDialogPackage = pkg
                    main.removeCallbacks(readDialog)
                    main.postDelayed(readDialog, SETTLE_MS)
                }
            }
            stateChanged -> {
                visibleDialogText = null
                val title = event.text?.joinToString(" ")?.trim()
                QuarantineUiRules.settingsScreen(pkg, event.className?.toString(), title)?.let { kind ->
                    QuarantineBus.settingsScreen(t, kind, title?.takeIf { it.isNotEmpty() } ?: kind.title)
                }
            }
        }
    }

    fun cancel() = main.removeCallbacks(readDialog)

    private fun readPermissionDialog() {
        if (QuarantineBus.watchedPackage == null) return
        val pkg = pendingDialogPackage ?: return
        val root = runCatching { service.windows.mapNotNull { it.root }.lastOrNull { it.packageName?.toString() == pkg } }.getOrNull()
            ?: service.rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg }
            ?: return
        var message: String? = null
        val texts = mutableListOf<String>()
        var budget = MAX_NODES
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (--budget < 0) return
            val text = n.text?.toString()
            if (!text.isNullOrBlank() && n.isVisibleToUser) {
                if (message == null && n.viewIdResourceName?.endsWith(":id/permission_message") == true) message = text
                texts += text
            }
            if (depth >= MAX_DEPTH) return
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        val text = QuarantineUiRules.dialogText(message, texts) ?: return
        if (text == visibleDialogText) return
        visibleDialogText = text
        QuarantineBus.permissionDialog(pendingDialogAt, pkg, text)
    }

    private companion object {
        const val SETTLE_MS = 250L
        const val MAX_NODES = 200
        const val MAX_DEPTH = 20
    }
}
