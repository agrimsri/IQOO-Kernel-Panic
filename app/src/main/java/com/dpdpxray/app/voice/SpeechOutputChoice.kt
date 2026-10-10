package com.dpdpxray.app.voice

/**
 * Which voice speaks answers. The online voice needs a build that includes it ([ApiEnv.ONLINE_VOICE_AVAILABLE]), and
 * both an explicit `USE_API=true` and a key in the `api.env` that was pushed to the phone; with any of these missing,
 * the phone's own offline voice speaks. There is no other way to get the online voice, and no speech recognition or
 * answer generation ever goes online.
 */
enum class SpeechOutputChoice {
    OFFLINE, ONLINE;

    companion object {
        fun choose(config: ApiConfig, onlineVoiceAvailable: Boolean = ApiEnv.ONLINE_VOICE_AVAILABLE): SpeechOutputChoice =
            if (onlineVoiceAvailable && config.useApi && !config.sarvamKey.isNullOrBlank()) ONLINE else OFFLINE
    }
}
