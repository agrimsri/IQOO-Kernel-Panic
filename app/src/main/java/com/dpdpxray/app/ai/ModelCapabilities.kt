package com.dpdpxray.app.ai

/** Input kinds a loaded model bundle accepts. Output is always text. */
enum class ModelCapability(val label: String) { TEXT("text"), VISION("image"), AUDIO("audio") }

/**
 * What one loaded bundle can take as input, and how we know ([basis]). Routing uses this so a text-only model (for
 * example a future Gemma 3 1B NPU bundle) is never sent screenshots or camera photos.
 */
data class ModelCapabilities(val inputs: Set<ModelCapability>, val basis: String) {
    val canReadImages: Boolean get() = ModelCapability.VISION in inputs

    /** Only ever removes inputs: bundle metadata can say "no images" but cannot add what the routing table did not expect. */
    fun narrowTo(other: ModelCapabilities): ModelCapabilities =
        if (other.inputs.containsAll(inputs)) this
        else ModelCapabilities(inputs intersect other.inputs, "$basis, narrowed by ${other.basis}")

    fun describe(): String = inputs.sortedBy { it.ordinal }.joinToString { it.label } + " ($basis)"

    companion object {
        private val MULTIMODAL_FAMILIES = listOf("gemma-4", "gemma4", "gemma_4", "gemma-3n", "gemma3n", "gemma_3n")

        /**
         * Conservative table from the file name. Gemma 4 and Gemma 3n GPU/CPU bundles take text, images and audio.
         * NPU-compiled bundles and unknown families are text-only until a vision-capable bundle is verified on the phone.
         */
        fun fromFileName(name: String): ModelCapabilities = when {
            ModelSelection.isNpuCompiled(name) ->
                ModelCapabilities(setOf(ModelCapability.TEXT), "NPU bundle: treated as text-only until image input is verified on this phone")
            MULTIMODAL_FAMILIES.any { name.contains(it, ignoreCase = true) } ->
                ModelCapabilities(ModelCapability.entries.toSet(), "file name: Gemma 4 / Gemma 3n multimodal family")
            else -> ModelCapabilities(setOf(ModelCapability.TEXT), "file name: unrecognised family, treated as text-only")
        }

        /** From LiteRT-LM's `Capabilities.inputModalities()` (bundle metadata). */
        fun fromBundle(text: Boolean, vision: Boolean, audio: Boolean) = ModelCapabilities(
            buildSet {
                if (text) add(ModelCapability.TEXT)
                if (vision) add(ModelCapability.VISION)
                if (audio) add(ModelCapability.AUDIO)
            },
            "bundle metadata",
        )
    }
}

object CapabilityRouting {
    data class ScreenReviewPlan(val callModel: Boolean, val attachImage: Boolean, val reason: String?)

    /**
     * Consent-screen review and Camera Check. A model that cannot read images is not called at all (its prompt describes a
     * screenshot, and the NPU plan keeps text-only models away from consent decisions); the accessibility-tree rules decide.
     */
    fun screenReview(caps: ModelCapabilities?, screenshotAvailable: Boolean): ScreenReviewPlan = when {
        caps == null -> ScreenReviewPlan(false, false, "no model capabilities are known")
        !caps.canReadImages -> ScreenReviewPlan(
            false, false,
            "the loaded model is text-only, so screenshots and camera photos are not sent to it; the accessibility-tree rules decide this screen",
        )
        else -> ScreenReviewPlan(callModel = true, attachImage = screenshotAvailable, reason = null)
    }
}
