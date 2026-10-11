package com.dpdpxray.core.web

import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.rules.RulesEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebFixesTest {
    @Test
    fun `a website check gets website fixes, never Android SDK settings`() {
        val result = RulesEngine(WebTestData.catalog).evaluate(listOf(WebTestData.build(WebTestData.leakyReject())))
        val fixes = WebFixes.forResult(result)
        assertEquals(listOf("web-consent-gate", "web-banner-choice"), fixes.map { it.id })
        assertTrue(fixes.all { it.kind == "web" && it.manifest == null && !it.verified })
        assertFalse(fixes.joinToString { it.code.orEmpty() }.contains("AndroidManifest"))
        assertTrue(WebFixes.forResult(AuditResult(emptyList(), 100, ScoreBand.READY)).isEmpty())
    }
}
