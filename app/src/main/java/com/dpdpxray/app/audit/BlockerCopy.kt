package com.dpdpxray.app.audit

import com.dpdpxray.core.consent.BlockerKind

/** Words for sign-in and verification blockers: audit notes, the live step line and the hand-off notification. */
internal object BlockerCopy {
    /** Audit notes starting with this mark a plan whose consent screen was never reached because of a blocker. */
    const val BLOCKED_MARK = "Blocked by"

    fun kind(k: BlockerKind): String = when (k) {
        BlockerKind.SIGN_IN -> "sign-in screen"
        BlockerKind.OTP -> "verification-code screen"
        BlockerKind.CAPTCHA -> "CAPTCHA"
        BlockerKind.BIOMETRIC -> "fingerprint or PIN prompt"
    }

    fun kindHi(k: BlockerKind): String = when (k) {
        BlockerKind.SIGN_IN -> "साइन-इन स्क्रीन"
        BlockerKind.OTP -> "वेरिफ़िकेशन कोड स्क्रीन"
        BlockerKind.CAPTCHA -> "कैप्चा"
        BlockerKind.BIOMETRIC -> "फ़िंगरप्रिंट या PIN"
    }

    /** The live "step" line while the person is asked to act. */
    fun waitingStep(k: BlockerKind) = "Waiting for you: finish the ${kind(k)}, then TraceVeil continues"

    fun notificationTitle(app: String) = "$app needs you"
    fun notificationText(app: String, k: BlockerKind) =
        "Complete the ${kind(k)} in $app (or skip it). TraceVeil is still watching and continues the check by itself."
    fun notificationTextHi(app: String, k: BlockerKind) =
        "$app में ${kindHi(k)} पूरा करें (या छोड़ दें)। TraceVeil देख रहा है और जाँच अपने आप आगे बढ़ाएगा।"

    /** Notes for the audit record, from what the wait found. */
    fun notes(outcome: ConsentWaitAssist.Outcome, blocksPlan: Boolean = true): List<String> {
        val kind = outcome.blocker ?: return emptyList()
        return buildList {
            if (outcome.skipped.isNotEmpty()) {
                add("A ${kind(kind)} appeared before the consent screen; the agent tapped ${outcome.skipped.joinToString { "“$it”" }} to continue.")
            }
            if (outcome.handedOff) {
                add("A ${kind(kind)} could not be skipped; TraceVeil waited ${outcome.blockedMs / 1000} s for the person to complete it.")
            }
            if (outcome.consent == null && blocksPlan) {
                add("$BLOCKED_MARK a ${kind(kind)}: the app's consent screen was never reached, so this plan could not test consent.")
            }
        }
    }

    /** The one coverage line shown on the result when a plan was blocked. */
    const val GAP_LINE = "The app asked for sign-in or verification before showing its consent screen, so TraceVeil could not reach it. " +
        "When the check waits for you, sign in or skip the step, or test with an account that is already signed in."
}
