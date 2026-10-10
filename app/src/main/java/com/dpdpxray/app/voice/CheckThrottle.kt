package com.dpdpxray.app.voice

import com.dpdpxray.core.copilot.CopilotLanguage

/**
 * Stops the same speech-pack check from being issued several times in a row (opening the screen and resuming it both
 * ask). Every check binds the phone's speech service, so repeats are churn. A user pressing Check again is never skipped.
 */
class CheckThrottle(private val minGapMs: Long) {
    private var lastAt: Long? = null
    private var lastLanguage: CopilotLanguage? = null

    fun allow(nowMs: Long, language: CopilotLanguage, force: Boolean = false): Boolean {
        val last = lastAt
        if (!force && last != null && lastLanguage == language && nowMs - last < minGapMs) return false
        lastAt = nowMs
        lastLanguage = language
        return true
    }
}
