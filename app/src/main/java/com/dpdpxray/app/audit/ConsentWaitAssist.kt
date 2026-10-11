package com.dpdpxray.app.audit

import com.dpdpxray.core.consent.BlockerKind
import com.dpdpxray.core.model.ConsentEvent

/**
 * Waits for the audited app's consent screen the way [ConsentScreenWaiter] does, but copes with a sign-in, code,
 * CAPTCHA or biometric screen standing in the way:
 *
 * 1. When a blocker shows, it taps a harmless skip button ("Skip", "Not now", "Continue as guest") or completes a profile form
 *    with test values, up to [maxSkips]
 *    times. It never types a password, code or identifying detail and never taps a sign-in button.
 * 2. If nothing can be skipped, it hands over to the person ([onHandoff], once): they sign in or pass the check
 *    themselves, and TraceVeil keeps watching and carries on when the consent screen shows. [onResume] fires when the
 *    blocker is gone again.
 * 3. Time spent behind a blocker does not count against [await]'s timeout, up to `assistTimeoutMs` in total, so the
 *    usual consent wait only starts once the app is past it. Capture keeps running throughout.
 */
internal class ConsentWaitAssist(
    private val probe: suspend () -> ConsentEvent?,
    private val detectBlocker: suspend () -> BlockerKind?,
    private val trySkip: suspend () -> String?,
    private val onHandoff: suspend (BlockerKind) -> Unit,
    private val onResume: suspend () -> Unit,
    private val clockMs: () -> Long,
    private val pause: suspend (Long) -> Unit,
    private val intervalMs: Long = 500,
    private val skipGapMs: Long = 1_500,
    private val maxSkips: Int = 3,
    /** False for the Silent plan, which never touches the app: a blocker is then only noted, never skipped or handed over. */
    private val interactive: Boolean = true,
) {
    data class Outcome(
        val consent: ConsentEvent?,
        /** The first blocker seen, or null when the app went straight to its consent screen (or never showed one). */
        val blocker: BlockerKind?,
        /** Labels of the skip buttons the agent tapped. */
        val skipped: List<String>,
        /** The person was asked to complete a step themselves. */
        val handedOff: Boolean,
        /** Time spent behind a blocker, which did not count against the timeout. */
        val blockedMs: Long,
    )

    suspend fun await(baseTimeoutMs: Long, assistTimeoutMs: Long = DEFAULT_ASSIST_MS): Outcome {
        require(baseTimeoutMs >= 0 && intervalMs > 0)
        val started = clockMs()
        var last = started
        var blockedMs = 0L
        var first: BlockerKind? = null
        val skipped = mutableListOf<String>()
        var skips = 0
        var lastSkipAt = Long.MIN_VALUE / 2
        var lastSkipFailed = false
        var handedOff = false
        var showingHandoff = false
        fun outcome(consent: ConsentEvent?) = Outcome(consent, first, skipped.toList(), handedOff, blockedMs)

        while (true) {
            probe()?.let { return outcome(it) }
            val now = clockMs()
            val blocker = detectBlocker()
            if (blocker != null) {
                if (first == null) first = blocker
                if (interactive) blockedMs = minOf(assistTimeoutMs, blockedMs + (now - last))
                if (interactive && skips < maxSkips && now - lastSkipAt >= skipGapMs) {
                    lastSkipAt = now
                    skips++
                    val label = trySkip()
                    lastSkipFailed = label == null
                    if (label != null) skipped += label
                }
                if (interactive && !showingHandoff && (skips >= maxSkips || (skips >= 2 && lastSkipFailed))) {
                    showingHandoff = true
                    handedOff = true
                    onHandoff(blocker)
                }
            } else if (showingHandoff) {
                showingHandoff = false
                onResume()
            }
            last = now
            val active = (now - started) - blockedMs
            val remaining = baseTimeoutMs - active
            if (remaining <= 0) return outcome(null)
            pause(minOf(intervalMs, remaining))
        }
    }

    companion object {
        /** The most time a blocker can add to the consent wait: enough to sign in and pass a verification step. */
        const val DEFAULT_ASSIST_MS = 180_000L
    }
}
