package com.dpdpxray.app.ui.guide

import com.dpdpxray.core.copilot.CopilotLanguage

/** Language of on-screen and spoken guidance. Follows the copilot's EN/HI languages; anything else falls back to English. */
enum class GuideLang(val code: String, val copilot: CopilotLanguage) {
    EN("en", CopilotLanguage.EN),
    HI("hi", CopilotLanguage.HI);

    companion object {
        fun of(code: String?): GuideLang = entries.firstOrNull { it.code == code } ?: EN
    }
}

/** One piece of guidance copy in both supported languages. */
data class Bi(val en: String, val hi: String) {
    fun pick(lang: GuideLang): String = if (lang == GuideLang.HI) hi else en
}

/** What the person should feel about a step: it decides the banner's rail colour, never the wording. */
enum class GuideTone { INFO, ACTION, WAIT, CAUTION, DONE }

/**
 * "Step [index] of [total]: what to do now". [spoken] is what the optional speaker reads aloud: always short, never an
 * app name, host, message or other captured detail. Empty [spoken] means the step is shown but not read.
 */
data class GuideStep(
    val key: String,
    val index: Int,
    val total: Int,
    val tone: GuideTone,
    val title: Bi,
    val body: Bi,
    val spoken: Bi,
) {
    fun label(lang: GuideLang): String = if (lang == GuideLang.HI) "चरण $index / $total" else "Step $index of $total"
}

/** A step to read aloud, already resolved to one language. */
data class Narration(val key: String, val text: String, val lang: GuideLang)

/** Suppresses repeating the same step in the same language within [gapMs], e.g. when the person goes back to Home. */
class SpeechDedupe(private val gapMs: Long) {
    private var last: String? = null
    private var at = 0L

    fun shouldSpeak(key: String, lang: GuideLang, nowMs: Long): Boolean {
        val id = "$key|${lang.code}"
        if (id == last && nowMs - at < gapMs) return false
        last = id
        at = nowMs
        return true
    }

    fun reset() { last = null }
}

/** First-run onboarding appears once; a running check always wins so the person can follow it. */
object OnboardingGate {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    fun startRoute(onboardingDone: Boolean, auditRunning: Boolean): String = if (onboardingDone || auditRunning) HOME else ONBOARDING
}
