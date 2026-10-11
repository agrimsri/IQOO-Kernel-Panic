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
import com.dpdpxray.core.consent.BlockerAgent
import com.dpdpxray.core.consent.BlockerKind
import com.dpdpxray.core.consent.BlockerLexicon
import com.dpdpxray.core.consent.ConsentLexicon
import com.dpdpxray.core.consent.FormFill
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.UiNode
import com.dpdpxray.core.quarantine.QuarantineUiRules
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

    /** The glow along the top edge and the "fill this in" card, drawn over the audited app. */
    private val overlay = InspectionOverlay(this)
    private val driver = ScreenDriver(this)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onServiceConnected() {
        instance = this
        // The glow follows the audit: on while an app is armed for inspection, off the moment it is not.
        scope.launch {
            ConsentBus.armedPackage.collect { pkg -> if (pkg == null) overlay.hideAll() else overlay.showGlow() }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        overlay.hideAll()
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
        // The glow marks the inspected app: it steps aside while TraceVeil itself is in front and returns with the app.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Our own overlay windows also report events from this package, so only a real activity counts as "in front".
            if (pkg == armed) overlay.showGlow()
            else if (pkg == packageName && event.className?.toString()?.endsWith("Activity") == true) overlay.hideAll()
        }
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

    /**
     * Evidence screenshots are of the app alone: the glow is taken off the screen for the moment of the shot and put back.
     * Callers use the main thread.
     */
    private fun capture(file: File, done: (Boolean) -> Unit) {
        val hadGlow = overlay.glowShowing
        if (hadGlow) overlay.hideGlow()
        val finish: (Boolean) -> Unit = { ok ->
            if (hadGlow) main.post { if (ConsentBus.armedPackage.value != null) overlay.showGlow() }
            done(ok)
        }
        if (hadGlow) main.postDelayed({ captureNow(file, finish) }, GLOW_CLEAR_MS) else captureNow(file, finish)
    }

    /** Screenshot on the accessibility framework, encoded off the main thread; the buffer is always released. */
    private fun captureNow(file: File, done: (Boolean) -> Unit) {
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

    /** What the audited app's sign-in or code screen asks the person to fill in, as the app labels it. Main thread. */
    fun blockerFields(): List<String> {
        check(Looper.myLooper() == Looper.getMainLooper())
        val tree = NodeMapper.map(targetRoot()) ?: return emptyList()
        return BlockerLexicon.fieldLabels(tree)
    }

    /** Shows the "fill this in" card over the audited app. Main thread. */
    fun showBlockerPrompt(title: String, body: String, stopLabel: String, hideLabel: String, onStop: () -> Unit) =
        overlay.showPrompt(title, body, stopLabel, hideLabel, onStop)

    fun hideBlockerPrompt() = overlay.hidePrompt()

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

    /**
     * For a profile form (Name, Date of Birth, CONTINUE): types obvious test values into the harmless fields, and taps the
     * Continue button only when every field then holds something. Never touches a password, phone, email, code or other
     * identifying field, and never types the person's own details. Returns a short description for the audit notes, or
     * null when the form could not be completed this way (the person is then asked for what is left). Main thread.
     */
    suspend fun fillFormAndContinue(): String? {
        check(Looper.myLooper() == Looper.getMainLooper())
        val (tree, live) = NodeMapper.mapWithNodes(targetRoot()) ?: return null
        if (BlockerLexicon.detect(tree) != BlockerKind.FORM) return null
        // A date-of-birth field opens a picker; the date typed in (or the picker's own default) is confirmed with OK.
        FormFill.pickerConfirmIndex(tree)?.let { ok ->
            return if (driver.press(live[ok])) "OK (confirmed the date picker)" else null
        }
        val plan = FormFill.plan(tree)
        if (plan.forwardIndex == null) return null
        val typed = mutableListOf<FormFill.Fill>()
        for (f in plan.fills) {
            // A date field is only tapped: it opens a picker, which is confirmed on the next pass.
            val done = if (f.value == FormFill.TEST_BIRTH) driver.press(live[f.index]) else driver.type(live[f.index], f.value)
            if (done) typed += f
        }
        driver.hideKeyboard()
        // Read the screen again: continue only if the typing took and nothing identifying is left to fill.
        kotlinx.coroutines.delay(400)
        driver.releaseClipboard()
        val (after, afterLive) = NodeMapper.mapWithNodes(targetRoot()) ?: return null
        val left = FormFill.plan(after)
        if (left.fills.isNotEmpty() || left.manual.isNotEmpty()) return null
        val button = afterLive.getOrNull(left.forwardIndex ?: return null) ?: return null
        val label = NodeMapper.labelOf(button) ?: "Continue"
        if (!driver.press(button)) return null
        return "$label (after typing test values into ${typed.joinToString { it.label }.ifEmpty { "no fields" }})"
    }

    /**
     * Saves a screenshot of the screen to [file] (JPEG), without the inspection glow in it. False when it could not be
     * taken (for example on Android 10, where accessibility screenshots do not exist). Main thread.
     */
    suspend fun screenshotTo(file: File): Boolean = suspendCancellableCoroutine { cont -> capture(file) { ok -> if (cont.isActive) cont.resume(ok) } }

    /** The audited app's screen as the pure model plus its live nodes (same order), or null. Main thread. */
    fun readScreen(): Pair<UiNode, List<AccessibilityNodeInfo>>? {
        check(Looper.myLooper() == Looper.getMainLooper())
        return NodeMapper.mapWithNodes(targetRoot())
    }

    /**
     * Carries out one step the on-device model chose and [BlockerAgent] approved, on the screen it was planned from
     * ([planned]). The screen is read again first, because the model takes seconds: if the control it picked is no longer
     * there, nothing is done. Returns a description for the audit notes, or null when nothing was done. Main thread.
     */
    suspend fun executeAgentStep(step: BlockerAgent.Step, planned: UiNode): String? {
        check(Looper.myLooper() == Looper.getMainLooper())
        val index = when (step) { is BlockerAgent.Step.Tap -> step.index; is BlockerAgent.Step.Type -> step.index; else -> return null }
        val (now, live) = NodeMapper.mapWithNodes(targetRoot()) ?: return null
        val was = planned.flatten()[index]
        val nowNodes = now.flatten()
        fun same(a: UiNode) = a.className == was.className && a.label == was.label && a.hint == was.hint
        val at = (if (index < nowNodes.size && same(nowNodes[index])) index else nowNodes.indexOfFirst(::same)).takeIf { it >= 0 } ?: return null
        return when (step) {
            is BlockerAgent.Step.Tap -> if (driver.press(live[at])) "Tapped “${step.label}”" else null
            is BlockerAgent.Step.Type -> {
                var done = false
                if (step.kind == BlockerAgent.FieldKind.BIRTH_DATE) {
                    // A date field usually opens a picker, which the model then confirms; if none opens, type the date.
                    done = driver.press(live[at])
                    kotlinx.coroutines.delay(500)
                    val picker = NodeMapper.map(targetRoot())?.let { FormFill.pickerConfirmIndex(it) != null } == true
                    if (!picker) done = driver.type(live[at], step.value) || done
                } else {
                    done = driver.type(live[at], step.value)
                }
                driver.hideKeyboard()
                kotlinx.coroutines.delay(300)
                driver.releaseClipboard()
                if (done) "Typed a test value into “${step.label}”" else null
            }
            else -> null
        }
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
        /** Time for the removed glow window to leave the screen before a screenshot is taken. */
        private const val GLOW_CLEAR_MS = 120L

        @Volatile
        var instance: ConsentAccessibilityService? = null
            private set
    }
}
