package com.dpdpxray.app.ai

/** One fixed, data-free prompt run under one conversation setting by AI lab's "Answer check". */
data class AnswerCheckCase(
    val id: String,
    val label: String,
    val options: AskOptions,
    val prompt: String = AnswerCheck.PROMPT_OK,
    /** The whole word the answer must contain to count as answered-as-asked. */
    val expected: String = "OK",
    val jsonSchema: String? = null,
    val withTestImage: Boolean = false,
)

/**
 * The one-tap phone check for the empty-reply problem. It runs the app's current settings first, then each old setting on
 * its own, and turns the rows into plain conclusions. Pure logic: the model calls themselves happen in [LlmEngine.diagnose].
 */
object AnswerCheck {
    const val SYSTEM = "You are a test harness."
    const val PROMPT_OK = "Reply with the single word OK."
    const val PROMPT_JSON = "Return a JSON object whose field ok is true."
    const val SCHEMA_OK = """{"type":"object","properties":{"ok":{"type":"boolean"}},"required":["ok"]}"""
    const val IMAGE_WORD = "ACCEPT"
    const val PROMPT_IMAGE = "What single word is printed in this image? Reply with only that word."
    private const val SHORT = 64
    private const val THINKING_ROOM = 384
    private const val PREVIEW = 60

    fun cases(canReadImages: Boolean): List<AnswerCheckCase> = buildList {
        val app = AskOptions(maxOutputTokens = SHORT)
        add(AnswerCheckCase("app", "App settings: ${app.thinking.label}, ${app.sampler.label}", app))
        add(AnswerCheckCase("model_sampler", "Model's own sampler, thinking off", app.copy(sampler = SamplerChoice.MODEL_DEFAULT)))
        add(AnswerCheckCase("no_system", "App settings without a system instruction", app.copy(systemInstruction = false)))
        add(AnswerCheckCase("streaming", "App settings, streamed reply (sendMessageAsync)", app.copy(streaming = true)))
        add(AnswerCheckCase("json", "App settings, constrained JSON (screen-review path)", app.copy(constrained = true), PROMPT_JSON, "true", SCHEMA_OK))
        if (canReadImages) add(AnswerCheckCase("image", "App settings, image input (printed test card)", app, PROMPT_IMAGE, IMAGE_WORD, withTestImage = true))
        add(AnswerCheckCase("thinking_on", "Thinking on: answer must stay separate from thinking", app.copy(thinking = ThinkingChoice.ON, maxOutputTokens = THINKING_ROOM)))
        add(AnswerCheckCase("legacy_thinking", "Old: thinking not set (new sampler)", app.copy(thinking = ThinkingChoice.ENGINE_DEFAULT)))
        add(AnswerCheckCase("legacy_sampler", "Old: temperature 0 (thinking off)", app.copy(sampler = SamplerChoice.LEGACY_ZERO_TEMPERATURE)))
        add(AnswerCheckCase("legacy", "Old settings exactly: temperature 0, thinking not set", AskOptions.LEGACY.copy(maxOutputTokens = SHORT)))
    }

    fun isExpected(case: AnswerCheckCase, outcome: LlmOutcome): Boolean =
        outcome is LlmOutcome.Text && Regex("\\b${Regex.escape(case.expected)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(outcome.text)

    /** First line of a test answer, at most 60 characters. Empty for anything that is not an answer. */
    fun preview(outcome: LlmOutcome): String = (outcome as? LlmOutcome.Text)?.text?.lineSequence()?.firstOrNull()?.trim()?.take(PREVIEW).orEmpty()

    fun conclude(results: List<Pair<AnswerCheckCase, LlmOutcome>>): List<String> {
        val byId = results.associate { (case, outcome) -> case.id to (case to outcome) }
        fun ok(id: String): Boolean? = byId[id]?.let { (case, outcome) -> isExpected(case, outcome) }
        fun outcome(id: String): LlmOutcome? = byId[id]?.second
        val out = mutableListOf<String>()

        results.firstNotNullOfOrNull { (_, outcome) -> EncoderPlan.missingEncoder(outcome.describe()) }?.let {
            out += "The model never ran: the engine asked for a ${it.label} encoder this bundle does not contain. Reload the model; this build only requests encoders for inputs the app sends."
        }
        when {
            ok("app") == true && ok("legacy") == false -> out += "Fixed on this phone: the app's settings answer and the old settings do not, so the earlier empty replies came from the old conversation settings."
            ok("app") == true -> out += "The app's settings answer, but the old settings also answered this short prompt, so this check does not reproduce the earlier failure. Next, open a finding explanation and a Camera Check and read 'Last problem'."
            ok("app") == false -> out += "Still failing: the app's own settings did not answer as asked on this phone. Keep AI explanations out of the demo; findings and scores never depend on the model."
        }
        if (ok("legacy") == false) {
            val samplerBreaks = ok("legacy_sampler") == false
            val thinkingBreaks = ok("legacy_thinking") == false
            when {
                samplerBreaks && !thinkingBreaks -> out += "Temperature 0 is the breaking setting: the old sampler fails even with thinking off."
                thinkingBreaks && !samplerBreaks -> out += "Leaving thinking unset is the breaking setting: the old sampler answers once thinking is off."
                samplerBreaks && thinkingBreaks -> out += "Each old setting breaks answers on its own: temperature 0 and leaving thinking unset."
            }
        }
        when (val t = outcome("thinking_on")) {
            is LlmOutcome.Empty -> if (t.thinkingChars > 0) out += "With thinking on, the reply was only thinking text (${t.thinkingChars} chars, not shown). The app keeps thinking off so reasoning never replaces the answer."
            is LlmOutcome.Text -> if (t.hiddenThinkingChars > 0) out += "With thinking on, the thinking arrived separately and was kept out of the answer."
            else -> Unit
        }
        if (ok("model_sampler") == true && ok("app") == false) out += "The model's own sampler answers where the app's does not: change the app default to SamplerChoice.MODEL_DEFAULT."
        if (ok("app") == true && ok("json") == false) out += "Constrained JSON did not work on this backend, so consent-screen reviews use the unconstrained retry (one extra model call)."
        if (ok("app") == true && ok("image") == false) out += "Text works but the image test failed, so screen reviews may fall back to the accessibility tree; read the image row."
        if (ok("app") != null && ok("streaming") != null && ok("app") != ok("streaming")) out += "Streamed and single replies differ on this phone; report both rows to the AI owner."
        return out
    }
}
