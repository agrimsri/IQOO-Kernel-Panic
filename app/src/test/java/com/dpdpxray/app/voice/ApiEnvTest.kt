package com.dpdpxray.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiEnvTest {
    @Test
    fun `missing file means off`() = assertFalse(ApiEnv.parse(null).useApi)

    @Test
    fun `defaults to off even with a key`() = assertFalse(ApiEnv.parse("SARVAM_API_KEY=abc").useApi)

    @Test
    fun `on only when explicitly true and a key is present`() {
        assertTrue(ApiEnv.parse("USE_API=true\nSARVAM_API_KEY=abc").useApi)
        assertFalse(ApiEnv.parse("USE_API=true").useApi)
        assertFalse(ApiEnv.parse("USE_API=yes\nSARVAM_API_KEY=abc").useApi)
    }

    @Test
    fun `accepts export prefix, quotes and comments`() {
        val c = ApiEnv.parse("# demo\nexport USE_API=\"true\"\nexport SARVAM_API_KEY='k-1'  \n")
        assertTrue(c.useApi)
        assertEquals("k-1", c.sarvamKey)
    }

    @Test
    fun `blank key is treated as absent`() = assertNull(ApiEnv.parse("SARVAM_API_KEY=   ").sarvamKey)

    @Test
    fun `toString never contains the key`() {
        val c = ApiEnv.parse("USE_API=true\nSARVAM_API_KEY=super-secret-value")
        assertFalse(c.toString().contains("super-secret-value"))
    }
}
