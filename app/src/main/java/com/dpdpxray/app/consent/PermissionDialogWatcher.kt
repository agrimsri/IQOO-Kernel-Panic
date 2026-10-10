package com.dpdpxray.app.consent

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.quarantine.QuarantineUiRules

/** One event per distinct permission dialog while it stays on screen; a dialog shown again later counts again. */
internal class PermissionDialogTracker {
    private var visibleText: String? = null

    fun onDialog(tNanos: Long, text: String?): ConsentEvent? {
        if (text.isNullOrBlank() || text == visibleText) return null
        visibleText = text
        return ConsentEvent(tNanos, ConsentKind.PERMISSION_DIALOG, text)
    }

    fun onOtherWindow() {
        visibleText = null
    }
}

/**
 * Read-only: while an audit is armed, records Android's own runtime-permission dialogs (AOSP and Google permission
 * controller) as [ConsentKind.PERMISSION_DIALOG] events with their visible text, for check C14. It never taps, and a
 * tap on "Allow" in these dialogs is never a consent choice (the consent watcher ignores other packages' clicks).
 * Some phone brands show their own permission screens, which are not recognised.
 */
internal class PermissionDialogWatcher(private val service: AccessibilityService, private val main: Handler) {
    private val tracker = PermissionDialogTracker()
    private val read = Runnable { readDialog() }
    private var pendingAt = 0L
    private var pendingPackage: String? = null
    private var pendingGeneration = -1L

    /** A window event from [pkg], already known to be a permission controller, while [generation] is armed. */
    fun onPermissionControllerEvent(pkg: String, generation: Long) {
        if (generation != pendingGeneration) tracker.onOtherWindow() // a new plan: earlier dialogs do not suppress this one
        if (!main.hasCallbacks(read)) pendingAt = SystemClock.elapsedRealtimeNanos()
        pendingPackage = pkg
        pendingGeneration = generation
        main.removeCallbacks(read)
        main.postDelayed(read, SETTLE_MS)
    }

    /** The audited app's own window changed: a dialog shown after this counts again. */
    fun onTargetWindowChanged() = tracker.onOtherWindow()

    fun cancel() = main.removeCallbacks(read)

    private fun readDialog() {
        val pkg = pendingPackage ?: return
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
        val event = tracker.onDialog(pendingAt, QuarantineUiRules.dialogText(message, texts)) ?: return
        ConsentBus.emitForSession(pendingGeneration, event)
    }

    private companion object {
        const val SETTLE_MS = 250L
        const val MAX_NODES = 200
        const val MAX_DEPTH = 20
    }
}
