package com.dpdpxray.app.ai

/**
 * Sampler settings a conversation can use. Values are plain numbers so they can be tested without the native library;
 * [LlmEngine] turns them into LiteRT-LM `SamplerConfig` objects. `null` fields mean "use the model bundle's own sampler".
 */
enum class SamplerChoice(val topK: Int?, val topP: Double?, val temperature: Double?, val label: String) {
    /** The sampler packaged in the model file (Gemma 4 E2B GPU bundle: top-p, k 64, p 0.95, temperature 1.0). */
    MODEL_DEFAULT(null, null, null, "the model's own sampler"),

    /** Deterministic in practice (only the top token survives top-k 1) without ever dividing by a zero temperature. */
    NEAR_GREEDY(1, 1.0, 1.0, "top-k 1 at temperature 1.0"),

    /** What the app used before October 2026. Kept only so AI lab can reproduce the empty-reply problem. */
    LEGACY_ZERO_TEMPERATURE(1, 1.0, 0.0, "top-k 1 at temperature 0 (old setting)"),
}

/** Whether Gemma 4 may think before answering. Thinking text goes to a separate channel and is never shown as an answer. */
enum class ThinkingChoice(val label: String) {
    OFF("thinking off"),
    ON("thinking on"),

    /** No thinking setting sent; the native runtime decides. This is what the app did before October 2026. */
    ENGINE_DEFAULT("thinking not set"),
}

/** Conversation settings for one call. The defaults are the app's current settings for every task. */
data class AskOptions(
    val systemInstruction: Boolean = true,
    val sampler: SamplerChoice = SamplerChoice.NEAR_GREEDY,
    val thinking: ThinkingChoice = ThinkingChoice.OFF,
    val constrained: Boolean = false,
    /** Upper bound on generated tokens, so a broken setting cannot keep the model busy for minutes. Null = no bound. */
    val maxOutputTokens: Int? = null,
    /** Read the reply through `sendMessageAsync` chunks instead of one `sendMessage` result (AI lab comparison only). */
    val streaming: Boolean = false,
) {
    companion object {
        /** Finding explanations: three short sentences in English or Hindi. */
        val EXPLANATION = AskOptions(maxOutputTokens = 512)

        /** Consent-screen review JSON. [constrained] adds the JSON-schema response format. */
        fun screenReview(constrained: Boolean) = AskOptions(constrained = constrained, maxOutputTokens = 768)

        /** The settings the app used when the empty replies were observed on the iQOO 15. */
        val LEGACY = AskOptions(sampler = SamplerChoice.LEGACY_ZERO_TEMPERATURE, thinking = ThinkingChoice.ENGINE_DEFAULT)
    }
}
