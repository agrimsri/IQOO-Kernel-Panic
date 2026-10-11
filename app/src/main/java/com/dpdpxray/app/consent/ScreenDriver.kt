package com.dpdpxray.app.consent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.dpdpxray.app.control.ShizukuBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Carries out the few physical things a check needs on the audited app's screen: pressing a control and typing a harmless
 * test value. It does only what it is told; deciding what is safe to do is [com.dpdpxray.core.consent.BlockerAgent]'s job.
 * Calls are made on the main thread.
 */
internal class ScreenDriver(private val service: AccessibilityService) {
    /** Presses [node] (or the nearest clickable parent) with a real touch, falling back to the accessibility click. */
    suspend fun press(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        val pressed = target ?: node
        val r = Rect().also { pressed.getBoundsInScreen(it) }
        // A real touch first: some apps only act when a control is pressed the way a finger would press it.
        return tap(r.exactCenterX(), r.exactCenterY()) || pressed.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /**
     * Types [value] into [field]. Some apps only register what arrives like real typing, so the keyboard path comes first:
     * the shell's own `input text` when Shizuku is allowed, else a paste through the field's input connection, else set-text.
     * The field is tapped first so that it has focus.
     */
    suspend fun type(field: AccessibilityNodeInfo, value: String): Boolean {
        press(field)
        delay(300)
        val clipboard = service.getSystemService(ClipboardManager::class.java)
        val ok = (ShizukuBridge.hasPermission() && ShizukuBridge.exec("input", "text", value.replace(" ", "%s")).isSuccess) ||
            runCatching {
                clipboard.setPrimaryClip(ClipData.newPlainText("TraceVeil test value", value))
                field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }.getOrDefault(false) ||
            field.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) },
            )
        pendingClipboardClear = true
        return ok
    }

    private var pendingClipboardClear = false

    /** Clears the clipboard after the app has had time to read a pasted test value, so it does not linger there. */
    fun releaseClipboard() {
        if (!pendingClipboardClear) return
        pendingClipboardClear = false
        runCatching { service.getSystemService(ClipboardManager::class.java).clearPrimaryClip() }
    }

    /** Puts the keyboard away so the controls under it can be reached, but only when one is actually showing. */
    fun hideKeyboard() {
        if (service.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }

    /** A short touch at ([x], [y]) on screen, the way a finger taps. True once the system has completed it. */
    private suspend fun tap(x: Float, y: Float): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
        val sent = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            },
            null,
        )
        if (!sent && cont.isActive) cont.resume(false)
    }
}
