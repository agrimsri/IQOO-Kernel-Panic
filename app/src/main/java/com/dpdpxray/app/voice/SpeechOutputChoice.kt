package com.dpdpxray.app.voice

/**
 * Which voice speaks answers. The online voice needs both an explicit `USE_API=true` and a key in the `api.env` that
 * was pushed to the phone; with either missing, the phone's own offline voice speaks. There is no other way to get the
 * online voice, and no speech recognition or answer generation ever goes online.
 */
enum class SpeechOutputChoice {
    OFFLINE, ONLINE;

    companion object {
        fun choose(config: ApiConfig): SpeechOutputChoice = if (config.useApi && !config.sarvamKey.isNullOrBlank()) ONLINE else OFFLINE
    }
}
