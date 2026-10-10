package com.dpdpxray.app.ai

import com.dpdpxray.app.ai.ModelSelection.NpuChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSelectionTest {
    private val gpu = "gemma-4-E2B-it-gpu.litertlm"
    private val npu8850 = "Gemma3-1B-IT_q4_ekv1280_sm8850.litertlm"
    private val npu8750 = "Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm"

    @Test
    fun `device SoC is normalised and unknown values fail closed`() {
        assertEquals("sm8850", ModelSelection.normaliseSoc("SM8850"))
        assertEquals("sm8850", ModelSelection.normaliseSoc("SM8850-AC"))
        assertNull(ModelSelection.normaliseSoc("unknown"))
        assertNull(ModelSelection.normaliseSoc(""))
        assertNull(ModelSelection.normaliseSoc(null))
    }

    @Test
    fun `NPU bundle for this phone's SoC is chosen`() {
        assertEquals(NpuChoice.Found(npu8850, "sm8850"), ModelSelection.npuModel(listOf(gpu, npu8750, npu8850), "SM8850"))
    }

    @Test
    fun `an SM8750 bundle is never accepted on an SM8850 phone`() {
        val c = ModelSelection.npuModel(listOf(gpu, npu8750), "SM8850")
        assertTrue(c is NpuChoice.Refused)
        c as NpuChoice.Refused
        assertTrue(c.reason, c.reason.contains("SM8850"))
        assertTrue(c.reason, c.reason.contains(npu8750))
    }

    @Test
    fun `NPU never falls back to the GPU bundle`() {
        val c = ModelSelection.npuModel(listOf(gpu), "SM8850")
        assertTrue(c is NpuChoice.Refused)
        assertFalse((c as NpuChoice.Refused).reason.contains(gpu))
    }

    @Test
    fun `a generic npu file that names no chip is refused`() {
        assertTrue(ModelSelection.npuModel(listOf("gemma3-1b-npu.litertlm"), "SM8850") is NpuChoice.Refused)
    }

    @Test
    fun `a bundle naming two chips is refused`() {
        assertTrue(ModelSelection.npuModel(listOf("gemma3_sm8750_sm8850.litertlm"), "SM8850") is NpuChoice.Refused)
    }

    @Test
    fun `unknown SoC refuses even a correctly named bundle`() {
        val c = ModelSelection.npuModel(listOf(npu8850), null)
        assertTrue(c is NpuChoice.Refused)
        assertTrue((c as NpuChoice.Refused).reason.contains("did not report"))
    }

    @Test
    fun `chip id must match exactly, not as a prefix`() {
        assertTrue(ModelSelection.npuModel(listOf("gemma3_sm88501.litertlm"), "SM8850") is NpuChoice.Refused)
    }

    @Test
    fun `only litertlm bundles are NPU candidates`() {
        assertTrue(ModelSelection.npuModel(listOf("gemma3_sm8850.tflite"), "SM8850") is NpuChoice.Refused)
    }

    @Test
    fun `main GPU model selection is unchanged and never picks NPU bundles or embedders`() {
        assertEquals(gpu, ModelSelection.mainModel(listOf("embeddinggemma-300m.litertlm", npu8850, gpu)))
        assertEquals("gemma-3n-E2B-it-int4.litertlm", ModelSelection.mainModel(listOf(npu8850, "gemma-3n-E2B-it-int4.litertlm")))
        assertNull(ModelSelection.mainModel(listOf(npu8850, npu8750)))
        assertNull(ModelSelection.mainModel(listOf("gemma-4-E2B-it.tflite")))
    }

    @Test
    fun `NPU-compiled names are recognised`() {
        assertTrue(ModelSelection.isNpuCompiled(npu8850))
        assertTrue(ModelSelection.isNpuCompiled("model_SM8650.litertlm"))
        assertTrue(ModelSelection.isNpuCompiled("gemma-qualcomm.litertlm"))
        assertFalse(ModelSelection.isNpuCompiled(gpu))
        assertFalse(ModelSelection.isNpuCompiled("smollm2-135m.litertlm"))
    }
}
