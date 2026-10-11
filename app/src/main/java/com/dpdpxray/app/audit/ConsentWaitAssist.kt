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
 * 4. Android's own permission dialog ([BlockerKind.PERMISSION]) is never tapped: the person is asked at once, and the
 *    wait pauses the same way. The person can also tap Continue on the card when they are through.
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
    /**
     * The person tapped Continue on the hand-off card: the step they were asked to do is done, even if the screen still
     * reads like it (a profile page that keeps a "Phone number" label). That kind of blocker is then no longer waited on.
     */
    private val userContinued: () -> Boolean = { false },
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
        var handoffKind: BlockerKind? = null
        val done = mutableSetOf<BlockerKind>()
        fun outcome(consent: ConsentEvent?) = Outcome(consent, first, skipped.toList(), handedOff, blockedMs)

        while (true) {
            probe()?.let { return outcome(it) }
            val now = clockMs()
            val seen = detectBlocker()
            if (showingHandoff && userContinued()) {
                handoffKind?.let { done += it }
                seen?.let { done += it }
                showingHandoff = false
                onResume()
            }
            val blocker = seen?.takeUnless { it in done }
            if (blocker != null) {
                if (first == null) first = blocker
                if (interactive) blockedMs = minOf(assistTimeoutMs, blockedMs + (now - last))
                // Android's permission dialog is never answered or skipped: only the person decides it.
                val skippable = blocker != BlockerKind.PERMISSION
                if (interactive && skippable && skips < maxSkips && now - lastSkipAt >= skipGapMs) {
                    lastSkipAt = now
                    skips++
                    val label = trySkip()
                    lastSkipFailed = label == null
                    if (label != null) skipped += label
                }
                if (interactive && !showingHandoff && (!skippable || skips >= maxSkips || (skips >= 2 && lastSkipFailed))) {
                    showingHandoff = true
                    handedOff = true
                    handoffKind = blocker
                    onHandoff(blocker)
                } else if (interactive && showingHandoff && blocker != handoffKind) {
                    // A different step now (the permission was answered and a sign-in shows): the card says the new one.
                    handoffKind = blocker
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
