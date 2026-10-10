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
    fun `Gemma 4 and Gemma 3n GPU bundles are multimodal`() {
        assertEquals(setOf(TEXT, VISION, AUDIO), ModelCapabilities.fromFileName("gemma-4-E2B-it-gpu.litertlm").inputs)
        assertEquals(setOf(TEXT, VISION, AUDIO), ModelCapabilities.fromFileName("gemma-3n-E2B-it-int4.litertlm").inputs)
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
        val named = ModelCapabilities.fromFileName("gemma-4-E2B-it-gpu.litertlm")
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
        val caps = ModelCapabilities.fromFileName("gemma-4-E2B-it-gpu.litertlm")
        assertEquals(CapabilityRouting.ScreenReviewPlan(callModel = true, attachImage = true, reason = null), CapabilityRouting.screenReview(caps, true))
        assertEquals(CapabilityRouting.ScreenReviewPlan(callModel = true, attachImage = false, reason = null), CapabilityRouting.screenReview(caps, false))
    }

    @Test
    fun `no capabilities known means no model call`() {
        assertFalse(CapabilityRouting.screenReview(null, true).callModel)
    }
}
