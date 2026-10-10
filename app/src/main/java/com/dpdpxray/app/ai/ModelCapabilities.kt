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

    /** Drops one input, e.g. when the bundle turns out to have no encoder for it. */
    fun without(capability: ModelCapability, reason: String): ModelCapabilities =
        if (capability !in inputs) this else ModelCapabilities(inputs - capability, "$basis, $reason")

    companion object {
        private val MULTIMODAL_FAMILIES = listOf("gemma-4", "gemma4", "gemma_4", "gemma-3n", "gemma3n", "gemma_3n")

        /**
         * Conservative table from the file name. Gemma 4 and Gemma 3n GPU/CPU bundles take text and images. Audio is never
         * assumed: the app sends no audio to the model, and some published bundles (the Gemma 4 E2B GPU bundle on the iQOO 15)
         * ship without an audio encoder. NPU-compiled bundles and unknown families are text-only until verified on the phone.
         */
        fun fromFileName(name: String): ModelCapabilities = when {
            ModelSelection.isNpuCompiled(name) ->
                ModelCapabilities(setOf(ModelCapability.TEXT), "NPU bundle: treated as text-only until image input is verified on this phone")
            ModelSelection.isTextOnlyBuild(name) ->
                ModelCapabilities(setOf(ModelCapability.TEXT), "file name: -gpu/-web build, published without image or audio encoders")
            MULTIMODAL_FAMILIES.any { name.contains(it, ignoreCase = true) } ->
                ModelCapabilities(setOf(ModelCapability.TEXT, ModelCapability.VISION), "file name: Gemma 4 / Gemma 3n family")
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

enum class EncoderTarget { GPU, CPU }

/**
 * Which input encoders LiteRT-LM is asked to build. An encoder the bundle does not contain makes every conversation fail
 * ("NOT_FOUND: TF_LITE_AUDIO_ENCODER_HW not found in the model"), so only encoders for inputs the app actually sends are
 * requested: images when the bundle can read them, audio never.
 */
data class EncoderPlan(val vision: EncoderTarget?, val audio: EncoderTarget?) {
    companion object {
        fun of(kind: BackendKind, caps: ModelCapabilities) = EncoderPlan(
            vision = when {
                !caps.canReadImages -> null
                kind == BackendKind.CPU -> EncoderTarget.CPU
                else -> EncoderTarget.GPU
            },
            audio = null,
        )

        private val MISSING_SECTION = Regex("TF_LITE_(AUDIO|VISION)_[A-Z_]+ not found")

        /** The input whose encoder LiteRT-LM reported missing, so the model can be reopened without it. */
        fun missingEncoder(message: String?): ModelCapability? =
            when (message?.let { MISSING_SECTION.find(it) }?.groupValues?.get(1)) {
                "AUDIO" -> ModelCapability.AUDIO
                "VISION" -> ModelCapability.VISION
                else -> null
            }
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
