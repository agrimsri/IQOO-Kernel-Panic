package com.dpdpxray.app.voice

import com.dpdpxray.core.copilot.CopilotLanguage

/** Plain copy of the parts of [android.speech.tts.Voice] that decide whether a voice is usable offline. */
data class VoiceInfo(val name: String, val languageTag: String, val networkRequired: Boolean, val notInstalled: Boolean, val quality: Int)

object LocalVoicePicker {
    /** Best installed, on-device voice for [language], preferring the Indian regional variant; null if none. */
    fun pick(voices: List<VoiceInfo>, language: CopilotLanguage): VoiceInfo? {
        val lang = language.tag.substringBefore('-')
        return voices
            .filter { !it.networkRequired && !it.notInstalled && it.languageTag.substringBefore('-').equals(lang, ignoreCase = true) }
            .sortedWith(compareByDescending<VoiceInfo> { it.languageTag.equals(language.tag, ignoreCase = true) }.thenByDescending { it.quality })
            .firstOrNull()
    }
}
