package com.dpdpxray.core.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CitationValidatorTest {
    private val allowed = setOf("s9(1)", "s9(3)", "s6(1)")

    @Test
    fun `accepts text citing only allowed passages`() {
        assertTrue(CitationValidator.isGrounded("Tracking children is barred [s9(3)] and needs consent [s9(1)].", allowed))
    }

    @Test
    fun `rejects an invented citation`() {
        assertFalse(CitationValidator.isGrounded("See [s42(7)] for details.", allowed))
    }

    @Test
    fun `rejects a real corpus id that was not retrieved`() {
        assertFalse(CitationValidator.isGrounded("Penalties apply [schedule].", allowed))
    }

    @Test
    fun `rejects bare section mentions that bypass brackets`() {
        assertFalse(CitationValidator.isGrounded("Under Section 33 the Board may fine the app.", allowed))
    }

    @Test
    fun `extracts bracketed ids in order without duplicates`() {
        assertEquals(listOf("s9(3)", "s6(1)"), CitationValidator.citedIds("a [s9(3)] b [s6(1)] c [s9(3)]"))
    }

    @Test
    fun `text with no citations is grounded`() {
        assertTrue(CitationValidator.isGrounded("The score was 21.", allowed))
    }
}
