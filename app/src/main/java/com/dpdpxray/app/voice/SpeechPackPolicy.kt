package com.dpdpxray.app.voice

import com.dpdpxray.core.copilot.CopilotLanguage

data class SpeechPacks(
    val installed: List<String> = emptyList(),
    val supported: List<String> = emptyList(),
    val pending: List<String> = emptyList(),
)

enum class PackStatus { INSTALLED, DOWNLOADABLE, PENDING, UNSUPPORTED, UNKNOWN }
data class SpeechPackChoice(val tag: String, val status: PackStatus)

object SpeechPackPolicy {
    fun choose(language: CopilotLanguage, packs: SpeechPacks?, deviceTag: String): SpeechPackChoice {
        val base = language.tag.substringBefore('-')
        fun matching(tag: String) = tag.substringBefore('-').equals(base, ignoreCase = true)
        val preferred = deviceTag.takeIf(::matching) ?: language.tag
        fun pick(tags: List<String>): String? {
            val candidates = tags.filter(::matching)
            return candidates.firstOrNull { it.equals(preferred, true) }
                ?: candidates.firstOrNull { it.equals(language.tag, true) }
                ?: candidates.sorted().firstOrNull()
        }
        if (packs == null) return SpeechPackChoice(preferred, PackStatus.UNKNOWN)
        pick(packs.installed)?.let { return SpeechPackChoice(it, PackStatus.INSTALLED) }
        pick(packs.pending)?.let { return SpeechPackChoice(it, PackStatus.PENDING) }
        pick(packs.supported)?.let { return SpeechPackChoice(it, PackStatus.DOWNLOADABLE) }
        return SpeechPackChoice(preferred, PackStatus.UNSUPPORTED)
    }
}
