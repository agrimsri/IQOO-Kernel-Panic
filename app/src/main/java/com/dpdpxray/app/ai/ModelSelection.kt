package com.dpdpxray.app.ai

/**
 * Which model file to load, decided from file names only (pure, unit-tested). An NPU bundle is compiled for one exact
 * Qualcomm/MediaTek chip, so it is accepted only when its name carries exactly this phone's chip id; anything else is
 * refused with a reason. The GPU bundle is never offered as an NPU model.
 */
object ModelSelection {
    /** Qualcomm (SM8850) and MediaTek (MT6991) chip ids; the 4 digits must not continue (SM88501 is not SM8850). */
    private val SOC_TOKEN = Regex("(?i)(sm|mt)\\d{4}(?!\\d)")
    private val NPU_MARKERS = listOf("npu", "qualcomm", "hexagon", "qnn")

    sealed interface NpuChoice {
        data class Found(val name: String, val soc: String) : NpuChoice
        data class Refused(val reason: String) : NpuChoice
    }

    fun socTokens(name: String): Set<String> = SOC_TOKEN.findAll(name).map { it.value.lowercase() }.toSet()

    /** True for any file that looks compiled for an NPU; such files are never used as the GPU/CPU model. */
    fun isNpuCompiled(name: String): Boolean = NPU_MARKERS.any { name.contains(it, ignoreCase = true) } || socTokens(name).isNotEmpty()

    /** `Build.SOC_MODEL` ("SM8850", "SM8850-AC") to "sm8850"; null, blank, "unknown" or unrecognised values give null. */
    fun normaliseSoc(raw: String?): String? = raw?.let { SOC_TOKEN.find(it)?.value?.lowercase() }

    /** The main multimodal LLM: Gemma 4 E2B first, then any Gemma 4, then Gemma 3n, then any other non-NPU, non-embedder bundle. */
    fun mainModel(names: List<String>): String? {
        val llms = names.filter { it.endsWith(".litertlm") && !it.contains("embed", ignoreCase = true) && !isNpuCompiled(it) }
        return llms.firstOrNull { it.contains("gemma-4", true) && it.contains("e2b", true) }
            ?: llms.firstOrNull { it.contains("gemma-4", true) }
            ?: llms.firstOrNull { it.contains("3n", true) }
            ?: llms.firstOrNull()
    }

    fun npuModel(names: List<String>, deviceSoc: String?): NpuChoice {
        val soc = normaliseSoc(deviceSoc)
            ?: return NpuChoice.Refused("this phone did not report its chip model (Build.SOC_MODEL needs Android 12 or later), so no NPU bundle can be matched safely")
        val candidates = names.filter { it.endsWith(".litertlm", ignoreCase = true) && isNpuCompiled(it) && !it.contains("embed", ignoreCase = true) }.sorted()
        candidates.firstOrNull { socTokens(it) == setOf(soc) }?.let { return NpuChoice.Found(it, soc) }
        val chip = soc.uppercase()
        return NpuChoice.Refused(
            if (candidates.isEmpty()) "no NPU bundle compiled for $chip is in the models folder"
            else "no NPU bundle compiled for $chip is in the models folder; not used: ${candidates.joinToString()} (built for another chip or no chip named)",
        )
    }
}
