package com.dpdpxray.app.audit

import com.dpdpxray.app.ui.guide.Bi
import com.dpdpxray.core.consent.BlockerKind
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.ConsentKind

/**
 * The result's "Steps that needed you" summary: for each plan, the screen that stood before the consent screen (sign-in,
 * code, CAPTCHA, fingerprint, details form, Android permission request) and what the agent or the person did about it,
 * plus every Android permission request the app made. Read from what each plan recorded; older audits without the
 * record show nothing here (their notes still say what happened).
 */
internal object BlockedSteps {
    data class Line(val title: Bi, val detail: Bi)

    val heading = Bi("Steps that needed you", "जिन कदमों में आपकी ज़रूरत पड़ी")

    private const val MAX_LINES = 8

    fun lines(sessions: List<AuditSession>): List<Line> {
        val several = sessions.map { it.plan }.distinct().size > 1
        fun where(s: AuditSession) = if (several) "${s.plan.label}: " else ""
        val out = mutableListOf<Line>()
        for (s in sessions) {
            val step = s.conditions?.blocker
            val kind = step?.let { runCatching { BlockerKind.valueOf(it.kind) }.getOrNull() }
            val dialogs = s.consentEvents.filter { it.kind == ConsentKind.PERMISSION_DIALOG && !it.nodeText.isNullOrBlank() }
            if (step != null && kind != null && kind != BlockerKind.PERMISSION) {
                val waited = step.waitedMs / 1000
                val tapped = step.agentTapped.joinToString { "“$it”" }
                val (en, hi) = when {
                    step.handedOff && step.consentReached -> "You completed it (TraceVeil waited $waited s, not counted against the check), then the consent screen was reached." to
                        "आपने इसे पूरा किया (TraceVeil ने $waited सेकंड इंतज़ार किया, जो जाँच के समय में नहीं गिना गया), फिर सहमति स्क्रीन आई।"
                    step.handedOff -> "You were asked to complete it, but the consent screen was not reached in time, so consent was not tested in this plan." to
                        "आपसे इसे पूरा करने को कहा गया, पर सहमति स्क्रीन समय पर नहीं आई, इसलिए इस रन में सहमति की जाँच नहीं हुई।"
                    step.agentTapped.isNotEmpty() && step.consentReached -> "TraceVeil got past it by itself ($tapped), then the consent screen was reached. It never signs in and types only harmless test values." to
                        "TraceVeil ने इसे खुद पार किया ($tapped), फिर सहमति स्क्रीन आई। यह कभी साइन-इन नहीं करता और सिर्फ़ हानिरहित टेस्ट मान भरता है।"
                    step.consentReached -> "It went away by itself, then the consent screen was reached." to "यह अपने आप हट गया, फिर सहमति स्क्रीन आई।"
                    else -> "It was shown while TraceVeil was not touching the app, and the consent screen was not reached, so consent was not tested in this plan." to
                        "यह तब दिखा जब TraceVeil ऐप को छू नहीं रहा था, और सहमति स्क्रीन नहीं आई, इसलिए इस रन में सहमति की जाँच नहीं हुई।"
                }
                out += Line(Bi(title(kind), titleHi(kind)), Bi(where(s) + en, where(s) + hi))
            }
            dialogs.forEachIndexed { i, d ->
                val waited = if (i == 0 && kind == BlockerKind.PERMISSION && step != null && step.waitedMs > 0) " TraceVeil waited ${step.waitedMs / 1000} s for you." else ""
                val waitedHi = if (waited.isNotEmpty()) " TraceVeil ने आपके लिए ${step!!.waitedMs / 1000} सेकंड इंतज़ार किया।" else ""
                out += Line(
                    Bi("Android permission request", "Android अनुमति अनुरोध"),
                    Bi(where(s) + "Android asked: “${d.nodeText}”. TraceVeil never answers these; the choice was yours.$waited",
                        where(s) + "Android ने पूछा: “${d.nodeText}”। TraceVeil इनका जवाब कभी नहीं देता; फ़ैसला आपका था।$waitedHi"),
                )
            }
        }
        return out.take(MAX_LINES)
    }

    private fun title(k: BlockerKind) = when (k) {
        BlockerKind.SIGN_IN -> "Sign-in screen"
        BlockerKind.OTP -> "Verification code"
        BlockerKind.CAPTCHA -> "CAPTCHA"
        BlockerKind.BIOMETRIC -> "Fingerprint or PIN"
        BlockerKind.FORM -> "Details form"
        BlockerKind.PERMISSION -> "Android permission request"
    }

    private fun titleHi(k: BlockerKind) = when (k) {
        BlockerKind.SIGN_IN -> "साइन-इन स्क्रीन"
        BlockerKind.OTP -> "वेरिफ़िकेशन कोड"
        BlockerKind.CAPTCHA -> "कैप्चा"
        BlockerKind.BIOMETRIC -> "फ़िंगरप्रिंट या PIN"
        BlockerKind.FORM -> "जानकारी फ़ॉर्म"
        BlockerKind.PERMISSION -> "Android अनुमति अनुरोध"
    }
}
