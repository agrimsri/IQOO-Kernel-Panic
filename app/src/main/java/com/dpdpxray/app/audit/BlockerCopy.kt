package com.dpdpxray.app.audit

import com.dpdpxray.core.consent.BlockerKind
import com.dpdpxray.core.model.BlockedStep

/** Words for sign-in and verification blockers: audit notes, the live step line and the hand-off notification. */
internal object BlockerCopy {
    /** Audit notes starting with this mark a plan whose consent screen was never reached because of a blocker. */
    const val BLOCKED_MARK = "Blocked by"

    fun kind(k: BlockerKind): String = when (k) {
        BlockerKind.SIGN_IN -> "sign-in screen"
        BlockerKind.OTP -> "verification-code screen"
        BlockerKind.CAPTCHA -> "CAPTCHA"
        BlockerKind.BIOMETRIC -> "fingerprint or PIN prompt"
        BlockerKind.FORM -> "details form"
        BlockerKind.PERMISSION -> "Android permission request"
    }

    /** "a sign-in screen", "an Android permission request". */
    private fun aKind(k: BlockerKind) = kind(k).let { if (it.first().lowercaseChar() in "aeiou") "an $it" else "a $it" }

    fun kindHi(k: BlockerKind): String = when (k) {
        BlockerKind.SIGN_IN -> "साइन-इन स्क्रीन"
        BlockerKind.OTP -> "वेरिफ़िकेशन कोड स्क्रीन"
        BlockerKind.CAPTCHA -> "कैप्चा"
        BlockerKind.BIOMETRIC -> "फ़िंगरप्रिंट या PIN"
        BlockerKind.FORM -> "जानकारी फ़ॉर्म"
        BlockerKind.PERMISSION -> "Android अनुमति अनुरोध"
    }

    /** The live "step" line while the person is asked to act. */
    fun waitingStep(k: BlockerKind, fields: List<String> = emptyList()) =
        if (fields.isEmpty()) "Waiting for you: finish the ${kind(k)}, then TraceVeil continues"
        else "Waiting for you: fill in ${quoted(fields)}, then TraceVeil continues"

    fun notificationTitle(app: String) = "$app needs you"
    fun notificationText(app: String, k: BlockerKind, fields: List<String> = emptyList()) =
        if (k == BlockerKind.PERMISSION) "Android is asking whether $app may use something. Choose Allow or Don't allow yourself; TraceVeil never answers it and continues the check by itself."
        else if (fields.isEmpty()) "Complete the ${kind(k)} in $app (or skip it). TraceVeil is still watching and continues the check by itself."
        else "Fill in ${quoted(fields)} in $app. TraceVeil is still watching and continues the check by itself."
    fun notificationTextHi(app: String, k: BlockerKind, fields: List<String> = emptyList()) =
        if (k == BlockerKind.PERMISSION) "Android पूछ रहा है कि $app को किसी चीज़ की अनुमति मिले या नहीं। Allow या Don't allow खुद चुनें; TraceVeil इसका जवाब कभी नहीं देता और जाँच अपने आप आगे बढ़ाएगा।"
        else if (fields.isEmpty()) "$app में ${kindHi(k)} पूरा करें (या छोड़ दें)। TraceVeil देख रहा है और जाँच अपने आप आगे बढ़ाएगा।"
        else "$app में ${quoted(fields)} भरें। TraceVeil देख रहा है और जाँच अपने आप आगे बढ़ाएगा।"

    private fun quoted(fields: List<String>) = fields.joinToString(" and ") { "“$it”" }

    /** The popup shown over the audited app while it waits for the person: names the field to fill in when it is known. */
    fun promptTitle(k: BlockerKind, fields: List<String>, hindi: Boolean = false): String = when {
        fields.isNotEmpty() -> if (hindi) "${quoted(fields).replace(" and ", " और ")} भरें" else "Fill in ${quoted(fields)}"
        else -> when (k) {
            BlockerKind.SIGN_IN -> if (hindi) "जारी रखने के लिए साइन इन करें" else "Sign in to continue"
            BlockerKind.OTP -> if (hindi) "वेरिफ़िकेशन कोड डालें" else "Enter the verification code"
            BlockerKind.CAPTCHA -> if (hindi) "कैप्चा हल करें" else "Solve the CAPTCHA"
            BlockerKind.BIOMETRIC -> if (hindi) "फ़िंगरप्रिंट या PIN इस्तेमाल करें" else "Use your fingerprint or PIN"
            BlockerKind.FORM -> if (hindi) "फ़ॉर्म भरें" else "Fill in the form"
            BlockerKind.PERMISSION -> if (hindi) "Android की अनुमति का जवाब दें" else "Answer Android's permission request"
        }
    }

    /**
     * What the person does on the card, then Continue (the check also carries on by itself once they are through). [why]
     * is the reason TraceVeil, or its on-device model, could not carry on by itself, when it knows one.
     */
    fun promptBody(k: BlockerKind = BlockerKind.SIGN_IN, hindi: Boolean = false, why: String? = null): String {
        val base = when {
            k == BlockerKind.PERMISSION && hindi -> "Allow या Don't allow खुद चुनें, फिर Continue दबाएँ। TraceVeil अनुमति के अनुरोधों का जवाब कभी नहीं देता।"
            k == BlockerKind.PERMISSION -> "Choose Allow or Don't allow yourself, then tap Continue. TraceVeil never answers permission requests."
            hindi -> "यह ऐप में खुद पूरा करें, फिर Continue दबाएँ। TraceVeil पासवर्ड, कोड या निजी जानकारी कभी नहीं भरता।"
            else -> "Do this in the app yourself, then tap Continue. TraceVeil never types a password, code or personal detail."
        }
        val reason = why?.let { (if (hindi) "रुकने का कारण: " else "Why TraceVeil stopped: ") + it }
        return listOfNotNull(base, reason).joinToString("\n")
    }

    fun promptContinue(hindi: Boolean = false) = if (hindi) "जारी रखें" else "Continue"

    fun promptStop(hindi: Boolean = false) = if (hindi) "जाँच रोकें" else "Stop check"
    fun promptHide(hindi: Boolean = false) = if (hindi) "छिपाएँ" else "Hide"

    /** Notes for the audit record, from what the wait found. */
    fun notes(outcome: ConsentWaitAssist.Outcome, blocksPlan: Boolean = true): List<String> {
        val kind = outcome.blocker ?: return emptyList()
        return buildList {
            if (outcome.skipped.isNotEmpty()) {
                add("A ${kind(kind)} appeared before the consent screen; the agent took these steps to continue: ${outcome.skipped.joinToString("; ") { "“$it”" }}.")
            }
            if (outcome.handedOff && kind == BlockerKind.PERMISSION) {
                add("An Android permission request appeared before the consent screen; TraceVeil never answers these and waited ${outcome.blockedMs / 1000} s for the person.")
            } else if (outcome.handedOff) {
                add("A ${kind(kind)} could not be skipped; TraceVeil waited ${outcome.blockedMs / 1000} s for the person to complete it.")
            }
            if (outcome.consent == null && blocksPlan) {
                add("$BLOCKED_MARK ${aKind(kind)}: the app's consent screen was never reached, so this plan could not test consent.")
            }
        }
    }

    /** The plan's first blocker as a saved record for the result's summary; null when nothing stood in the way. */
    fun step(outcome: ConsentWaitAssist.Outcome): BlockedStep? = outcome.blocker?.let {
        BlockedStep(it.name, outcome.skipped, outcome.handedOff, outcome.blockedMs, consentReached = outcome.consent != null)
    }

    /** The one coverage line shown on the result when a plan was blocked. */
    const val GAP_LINE = "The app asked for sign-in or verification before showing its consent screen, so TraceVeil could not reach it. " +
        "When the check waits for you, sign in or skip the step, or test with an account that is already signed in."
}
