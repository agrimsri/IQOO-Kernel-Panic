package com.dpdpxray.app.voice.assistant

import com.dpdpxray.core.assistant.AppCandidate
import com.dpdpxray.core.model.AuditPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAuditOptionsTest {
    private val instagram = AppCandidate("Instagram", "com.instagram.android")
    private val leaky = AppCandidate("LeakyShop", "com.leakyshop.demo")
    private val leakyFixed = AppCandidate("LeakyShop Fixed", "com.leakyshop.demo.fixed")

    @Test fun `a voice check is a normal Quick audit that keeps the app's data`() {
        listOf(instagram, leaky, leakyFixed).forEach { app ->
            val c = VoiceAuditOptions.config(app)
            assertEquals(app.packageName, c.targetPackage)
            assertEquals(app.label, c.targetLabel)
            assertEquals("Quick only: one Accept plan, never Full", listOf(AuditPlan.ACCEPT), c.plans)
            assertFalse("never a data reset by voice", c.resetAppData)
            assertFalse("normal timing, not Fast Preview", c.fastPreview)
            assertFalse(c.childFacing)
            assertFalse(c.includeGms)
        }
    }

    @Test fun `the agent taps consent only for the developer-owned demo apps`() {
        assertTrue(VoiceAuditOptions.config(leaky).autoDrive)
        assertTrue(VoiceAuditOptions.config(leakyFixed).autoDrive)
        assertFalse("a personal app's consent is the user's choice", VoiceAuditOptions.config(instagram).autoDrive)
    }

    @Test fun `apps on the developer automation allowlist may be auto-tapped, nothing else`() {
        val allow = setOf("com.example.mytestbuild")
        assertTrue(VoiceAuditOptions.config(AppCandidate("My test build", "com.example.mytestbuild"), allow).autoDrive)
        assertFalse(VoiceAuditOptions.config(instagram, allow).autoDrive)
        assertTrue(VoiceAuditOptions.autoTapAllowed("com.leakyshop.demo", emptySet()))
        assertFalse(VoiceAuditOptions.autoTapAllowed("com.leakyshop.demo.evil", emptySet()))
    }
}
