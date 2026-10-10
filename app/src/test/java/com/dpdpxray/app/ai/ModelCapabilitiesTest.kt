package com.dpdpxray.app.ai

import com.dpdpxray.app.ai.ModelCapability.AUDIO
import com.dpdpxray.app.ai.ModelCapability.TEXT
import com.dpdpxray.app.ai.ModelCapability.VISION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCapabilitiesTest {
    @Test
    fun `Gemma 4 and Gemma 3n full bundles read text and images, never assumed to take audio`() {
        assertEquals(setOf(TEXT, VISION), ModelCapabilities.fromFileName("gemma-4-E2B-it.litertlm").inputs)
        assertEquals(setOf(TEXT, VISION), ModelCapabilities.fromFileName("gemma-3n-E2B-it-int4.litertlm").inputs)
    }

    @Test
    fun `the published -gpu and -web Gemma 4 builds are text-only (no image encoder in the file)`() {
        assertEquals(setOf(TEXT), ModelCapabilities.fromFileName("gemma-4-E2B-it-gpu.litertlm").inputs)
        assertEquals(setOf(TEXT), ModelCapabilities.fromFileName("gemma-4-E2B-it-web.litertlm").inputs)
        assertEquals(null, EncoderPlan.of(BackendKind.GPU, ModelCapabilities.fromFileName("gemma-4-E2B-it-gpu.litertlm")).vision)
    }

    @Test
    fun `no audio encoder is ever requested, on any backend (the iQOO 15 GPU bundle has none)`() {
        for (kind in BackendKind.entries) {
            for (name in listOf("gemma-4-E2B-it-gpu.litertlm", "Gemma3-1B-IT_q4_ekv1280_sm8850.litertlm", "qwen3-0.6b.litertlm")) {
                assertEquals("$kind $name", null, EncoderPlan.of(kind, ModelCapabilities.fromFileName(name)).audio)
            }
            // Even a bundle that declares audio gets no audio encoder: the app never sends audio to the model.
            assertEquals(null, EncoderPlan.of(kind, ModelCapabilities.fromBundle(text = true, vision = true, audio = true)).audio)
        }
    }

    @Test
    fun `vision encoder only for image-capable bundles, on the CPU for the CPU backend`() {
        val gemma = ModelCapabilities.fromFileName("gemma-4-E2B-it.litertlm")
        assertEquals(EncoderTarget.GPU, EncoderPlan.of(BackendKind.GPU, gemma).vision)
        assertEquals(EncoderTarget.CPU, EncoderPlan.of(BackendKind.CPU, gemma).vision)
        assertEquals(null, EncoderPlan.of(BackendKind.NPU, ModelCapabilities.fromFileName("Gemma3-1B-IT_q4_ekv1280_sm8850.litertlm")).vision)
        assertEquals(null, EncoderPlan.of(BackendKind.GPU, gemma.without(VISION, "test")).vision)
    }

    @Test
    fun `the missing encoder is read from the LiteRT-LM error so the model can reopen without it`() {
        val audio = "Failed to create conversation: NOT_FOUND: TF_LITE_AUDIO_ENCODER_HW not found in the model."
        assertEquals(AUDIO, EncoderPlan.missingEncoder(audio))
        assertEquals(VISION, EncoderPlan.missingEncoder("NOT_FOUND: TF_LITE_VISION_ENCODER not found in the model."))
        assertEquals(null, EncoderPlan.missingEncoder("out of memory"))
        assertEquals(null, EncoderPlan.missingEncoder(null))
        val reopened = ModelCapabilities.fromFileName("gemma-4-E2B-it.litertlm").without(VISION, "bundle has no image encoder")
        assertEquals(setOf(TEXT), reopened.inputs)
        assertFalse(reopened.canReadImages)
    }

    @Test
    fun `Gemma 3 1B NPU bundle is text-only`() {
        val c = ModelCapabilities.fromFileName("Gemma3-1B-IT_q4_ekv1280_sm8850.litertlm")
        assertEquals(setOf(TEXT), c.inputs)
        assertFalse(c.canReadImages)
    }

    @Test
    fun `any NPU-compiled bundle is treated as text-only until verified, even a Gemma 4 one`() {
        assertEquals(setOf(TEXT), ModelCapabilities.fromFileName("gemma-4-E2B-it_sm8850.litertlm").inputs)
    }

    @Test
    fun `unknown families are text-only (fail closed for images)`() {
        assertEquals(setOf(TEXT), ModelCapabilities.fromFileName("qwen3-0.6b.litertlm").inputs)
    }

    @Test
    fun `bundle metadata can only narrow what the file name suggested`() {
        val named = ModelCapabilities.fromFileName("gemma-4-E2B-it.litertlm")
        assertEquals(setOf(TEXT), named.narrowTo(ModelCapabilities.fromBundle(text = true, vision = false, audio = false)).inputs)
        val textOnly = ModelCapabilities.fromFileName("qwen3-0.6b.litertlm")
        assertEquals(setOf(TEXT), textOnly.narrowTo(ModelCapabilities.fromBundle(text = true, vision = true, audio = true)).inputs)
    }

    @Test
    fun `text-only model is never asked to review a screenshot or a camera photo`() {
        val plan = CapabilityRouting.screenReview(ModelCapabilities.fromFileName("Gemma3-1B-IT_q4_ekv1280_sm8850.litertlm"), screenshotAvailable = true)
        assertFalse(plan.callModel)
        assertFalse(plan.attachImage)
        assertNotNull(plan.reason)
        assertTrue(plan.reason!!.contains("text-only"))
    }

    @Test
    fun `vision model gets the screenshot when there is one`() {
        val caps = ModelCapabilities.fromFileName("gemma-4-E2B-it.litertlm")
        assertEquals(CapabilityRouting.ScreenReviewPlan(callModel = true, attachImage = true, reason = null), CapabilityRouting.screenReview(caps, true))
        assertEquals(CapabilityRouting.ScreenReviewPlan(callModel = true, attachImage = false, reason = null), CapabilityRouting.screenReview(caps, false))
    }

    @Test
    fun `no capabilities known means no model call`() {
        assertFalse(CapabilityRouting.screenReview(null, true).callModel)
    }
}
