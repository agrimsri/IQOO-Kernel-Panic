package com.dpdpxray.core.consentlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NpuBackendPolicyTest {
    private val v81Libs = NpuBackendPolicy.requiredLibraries("V81").toSet()
    private fun env(
        sdk: Int = 36,
        soc: String? = "SM8850",
        abis: List<String> = listOf("arm64-v8a"),
        libs: Set<String> = v81Libs,
        unfinished: Boolean = false,
        off: Boolean = false,
    ) = NpuBackendPolicy.Environment(sdk, soc, abis, libs, unfinished, off)

    @Test
    fun `SM8850 with packaged V81 libraries tries the NPU`() {
        val d = NpuBackendPolicy.decide(env())
        assertTrue(d.tryNpu)
        assertEquals("V81", d.htpArch)
        assertTrue(d.reason.contains("SM8850"))
    }

    @Test
    fun `unknown SoC falls back to CPU and says why`() {
        val d = NpuBackendPolicy.decide(env(soc = "MT6989"))
        assertFalse(d.tryNpu)
        assertNull(d.htpArch)
        assertTrue(d.reason.contains("MT6989"))
    }

    @Test
    fun `below Android 12 the SoC cannot be read so CPU is used`() {
        assertFalse(NpuBackendPolicy.decide(env(sdk = 30, soc = null)).tryNpu)
    }

    @Test
    fun `non arm64 devices use CPU`() {
        assertFalse(NpuBackendPolicy.decide(env(abis = listOf("x86_64"))).tryNpu)
    }

    @Test
    fun `missing skel library names the missing file`() {
        val d = NpuBackendPolicy.decide(env(libs = v81Libs - "libQnnHtpV81Skel.so"))
        assertFalse(d.tryNpu)
        assertTrue(d.reason.contains("libQnnHtpV81Skel.so"))
    }

    @Test
    fun `an older Snapdragon needs its own HTP version libraries`() {
        val d = NpuBackendPolicy.decide(env(soc = "SM8650"))
        assertFalse(d.tryNpu) // only V81 libraries supplied in this environment
        assertTrue(NpuBackendPolicy.decide(env(soc = "sm8650", libs = NpuBackendPolicy.requiredLibraries("V75").toSet())).tryNpu)
    }

    @Test
    fun `an unfinished previous NPU start keeps the CPU until retried`() {
        val d = NpuBackendPolicy.decide(env(unfinished = true))
        assertFalse(d.tryNpu)
        assertTrue(d.reason.contains("previous NPU start"))
    }

    @Test
    fun `user switch-off wins`() {
        assertFalse(NpuBackendPolicy.decide(env(off = true)).tryNpu)
    }
}
