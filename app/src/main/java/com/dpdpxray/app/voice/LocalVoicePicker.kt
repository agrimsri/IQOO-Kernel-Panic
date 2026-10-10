package com.dpdpxray.app.voice

import com.dpdpxray.core.copilot.CopilotLanguage

/** Plain copy of the parts of [android.speech.tts.Voice] that decide whether a voice is usable offline. */
data class VoiceInfo(val name: String, val languageTag: String, val networkRequired: Boolean, val notInstalled: Boolean, val quality: Int)

object LocalVoicePicker {
    /** Highest-quality installed offline voice; region breaks quality ties, never overrides quality. */
    fun pick(voices: List<VoiceInfo>, language: CopilotLanguage): VoiceInfo? {
        val lang = language.tag.substringBefore('-')
        return voices
            .filter { !it.networkRequired && !it.notInstalled && it.languageTag.substringBefore('-').equals(lang, ignoreCase = true) }
            .sortedWith(compareByDescending<VoiceInfo> { it.quality }.thenByDescending { it.languageTag.equals(language.tag, ignoreCase = true) }.thenBy { it.name })
            .firstOrNull()
    }
}
