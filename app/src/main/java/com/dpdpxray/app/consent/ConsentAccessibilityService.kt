package com.dpdpxray.app.consent

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.dpdpxray.app.quarantine.QuarantineBus
import com.dpdpxray.app.quarantine.QuarantineUiWatcher
import com.dpdpxray.core.consent.BlockerKind
import com.dpdpxray.core.consent.BlockerLexicon
import com.dpdpxray.core.consent.ConsentLexicon
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.UiNode
import com.dpdpxray.core.quarantine.QuarantineUiRules
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Watches ONLY the package currently armed by an audit. Detects the consent screen (tree + screenshot), timestamps the
 * user's Accept/Reject tap, and can perform those taps itself for automated audit plans.
 */
class ConsentAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val check = Runnable { checkScreen() }
    private var consentVisible = false
    private var visibleGeneration = -1L
    private var pendingScreenGeneration: Long? = null

    /** Read-only watcher used only during a quarantine run; it never taps. */
    private val quarantineWatcher = QuarantineUiWatcher(this, main)

    /** Read-only: Android's own permission dialogs while an audit is armed (check C14); it never taps. */
    private val permissionWatcher = PermissionDialogWatcher(this, main)

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        main.removeCallbacks(check)
        quarantineWatcher.cancel()
        permissionWatcher.cancel()
        io.shutdown()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (QuarantineBus.watchedPackage != null) quarantineWatcher.onEvent(event)
        val armed = ConsentBus.armedPackage.value ?: run { consentVisible = false; return }
        val pkg = event.packageName?.toString()
        if (pkg != armed) {
            // Android's permission dialog over the audited app is recorded (C14), never treated as its consent screen.
            if (pkg != null && QuarantineUiRules.isPermissionController(pkg) && event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) {
                permissionWatcher.onPermissionControllerEvent(pkg, ConsentBus.generation)
            }
            return
        }
        ConsentBus.markTargetSeen()
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> onClick(event)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) permissionWatcher.onTargetWindowChanged()
                // Trailing debounce: always re-check once things settle, so a dialog that appears a moment after the
                // activity window is never missed.
                main.removeCallbacks(check)
                main.postDelayed(check, SETTLE_MS)
            }
        }
    }

    /** The armed app's own window (never TraceVeil's), falling back to the active window. */
    private fun targetRoot(): AccessibilityNodeInfo? {
        val armed = ConsentBus.armedPackage.value ?: return null
        val fromWindows = runCatching {
            windows.mapNotNull { it.root }.lastOrNull { it.packageName?.toString() == armed }
        }.getOrNull()
        return fromWindows ?: rootInActiveWindow?.takeIf { it.packageName?.toString() == armed }
    }

    private fun onClick(event: AccessibilityEvent) {
        val label = event.text?.joinToString(" ")?.takeIf { it.isNotBlank() } ?: NodeMapper.labelOf(event.source)
        val kind = ConsentLexicon.classifyButton(label) ?: return
        if (!ConsentBus.screenSeen) {
            val tree = NodeMapper.map(targetRoot()) ?: return
            if (!ConsentLexicon.isConsentScreen(tree.flatten().map { it.label })) return
            recordScreen(tree)
        }
        ConsentBus.emit(ConsentEvent(toElapsedNanos(event.eventTime), kind, label))
    }

    private fun checkScreen() {
        val generation = ConsentBus.generation
        if (visibleGeneration != generation) {
            visibleGeneration = generation
            consentVisible = false
            pendingScreenGeneration = null
        }
        val tree = NodeMapper.map(targetRoot()) ?: return
        ConsentBus.markTargetSeen()
        val isConsent = ConsentLexicon.isConsentScreen(tree.flatten().map { it.label })
        if (isConsent && !ConsentBus.screenSeen) recordScreen(tree)
        if (consentVisible && !isConsent) onConsentGone()
        consentVisible = isConsent
    }

    /** Android's Home action, used by the audit's background step (C12). Callers use the main dispatcher. */
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    /**
     * Whether an on-screen app window belongs to [pkg]. null when that cannot be told: no app window is readable, or one
     * of them cannot be attributed to a package. Never false by default.
     */
    fun packageOnScreen(pkg: String): Boolean? = runCatching {
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.map { it.root?.packageName?.toString() }
        when {
            apps.isEmpty() -> null
            pkg in apps -> true
            apps.any { it == null } -> null
            else -> false
        }
    }.getOrNull()

    /** Bounded orchestrator polling complements event debounce; callers use the main dispatcher. */
    fun probeNow() {
        check(Looper.myLooper() == Looper.getMainLooper())
        checkScreen()
    }

    /** Closing a dialog establishes no choice identity; never fabricate Accept/Reject from the plan. */
    private fun onConsentGone() {
        ConsentBus.onConsentDialogClosed(SystemClock.elapsedRealtimeNanos())
    }

    private fun recordScreen(tree: UiNode) {
        val generation = ConsentBus.generation
        if (pendingScreenGeneration == generation || ConsentBus.screenSeen) return
        pendingScreenGeneration = generation
        val t = SystemClock.elapsedRealtimeNanos()
        val dir = ConsentBus.shotDir
        if (dir == null) {
            ConsentBus.emitForSession(generation, ConsentEvent(t, ConsentKind.SCREEN_APPEARED, tree = tree))
            pendingScreenGeneration = null
            return
        }
        val file = File(dir, "consent_${t}.jpg")
        capture(file) { ok ->
            main.post {
                ConsentBus.emitForSession(generation, ConsentEvent(t, ConsentKind.SCREEN_APPEARED, screenshotPath = file.absolutePath.takeIf { ok }, tree = tree))
                if (pendingScreenGeneration == generation) pendingScreenGeneration = null
            }
        }
    }

    /** Screenshot on the accessibility framework, encoded off the main thread; the buffer is always released. */
    private fun capture(file: File, done: (Boolean) -> Unit) {
        // Accessibility screenshots need Android 11; on Android 10 the audit uses the UI tree alone.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return done(false)
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY, io,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val ok = try {
                            val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            FileOutputStream(file).use { out -> bmp?.compress(Bitmap.CompressFormat.JPEG, 88, out) }
                            bmp != null
                        } catch (_: Exception) {
                            false
                        } finally {
                            result.hardwareBuffer.close()
                        }
                        done(ok)
                    }

                    override fun onFailure(errorCode: Int) = done(false)
                },
            )
        } catch (_: Exception) {
            done(false)
        }
    }

    /** "Mark consent now": snapshot the audited app's window (not TraceVeil's own screen). */
    suspend fun markNow(): Boolean {
        val generation = ConsentBus.generation
        val tree = NodeMapper.map(targetRoot()) ?: return false
        val t = SystemClock.elapsedRealtimeNanos()
        val dir = ConsentBus.shotDir ?: return false
        val file = File(dir, "manual_${t}.jpg")
        val ok = suspendCancellableCoroutine { cont -> capture(file) { cont.resume(it) } }
        return ConsentBus.emitForSession(generation, ConsentEvent(t, ConsentKind.MANUAL_MARK, screenshotPath = file.absolutePath.takeIf { ok }, tree = tree))
    }

    /**
     * Taps the on-screen button of [kind] (ACCEPT/REJECT/SETTINGS). Returns the label tapped, or null.
     * The event is recorded here so automated runs are timestamped even if the app sends no click event.
     */
    fun tap(kind: ConsentKind): String? {
        val root = targetRoot() ?: return null
        val target = findButton(root, kind) ?: return null
        val label = NodeMapper.labelOf(target)
        var node: AccessibilityNodeInfo? = target
        while (node != null && !node.isClickable) node = node.parent
        val t = SystemClock.elapsedRealtimeNanos()
        val ok = node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        if (ok && (kind == ConsentKind.ACCEPT || kind == ConsentKind.REJECT)) ConsentBus.emit(ConsentEvent(t, kind, label))
        return label.takeIf { ok }
    }

    private fun findButton(n: AccessibilityNodeInfo, kind: ConsentKind): AccessibilityNodeInfo? {
        val label = listOfNotNull(n.text, n.contentDescription).joinToString(" ")
        if (label.isNotBlank() && ConsentLexicon.classifyButton(label) == kind && (n.isClickable || n.parent?.isClickable == true)) return n
        for (i in 0 until n.childCount) {
            val hit = n.getChild(i)?.let { findButton(it, kind) }
            if (hit != null) return hit
        }
        return null
    }

    /**
     * The sign-in, verification-code, CAPTCHA or biometric screen the audited app is showing right now, or null. Reads the
     * app's window only; never taps. Callers use the main dispatcher.
     */
    fun blockerNow(): BlockerKind? {
        check(Looper.myLooper() == Looper.getMainLooper())
        val tree = NodeMapper.map(targetRoot()) ?: return null
        return BlockerLexicon.detect(tree)
    }

    /**
     * Taps a harmless skip button ("Skip", "Not now", "Continue as guest") on the audited app's screen and returns its
     * label, or null when there is none. It never types, and never taps a sign-in, social-login or sign-up button.
     */
    fun trySkipBlocker(): String? {
        val root = targetRoot() ?: return null
        val target = findSkip(root) ?: return null
        val label = NodeMapper.labelOf(target)
        var node: AccessibilityNodeInfo? = target
        while (node != null && !node.isClickable) node = node.parent
        return label.takeIf { node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }
    }

    private fun findSkip(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val label = listOfNotNull(n.text, n.contentDescription).joinToString(" ")
        if (label.isNotBlank() && BlockerLexicon.isSkipLabel(label) && (n.isClickable || n.parent?.isClickable == true)) return n
        for (i in 0 until n.childCount) {
            val hit = n.getChild(i)?.let { findSkip(it) }
            if (hit != null) return hit
        }
        return null
    }

    /** AccessibilityEvent times are uptime-based; convert to the elapsed-realtime clock the VPN uses. */
    private fun toElapsedNanos(eventUptimeMs: Long): Long {
        val ageMs = (SystemClock.uptimeMillis() - eventUptimeMs).coerceAtLeast(0)
        return SystemClock.elapsedRealtimeNanos() - ageMs * 1_000_000L
    }

    companion object {
        private const val SETTLE_MS = 250L

        @Volatile
        var instance: ConsentAccessibilityService? = null
            private set
    }
}
