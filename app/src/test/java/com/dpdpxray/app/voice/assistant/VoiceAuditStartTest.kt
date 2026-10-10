package com.dpdpxray.app.voice.assistant

import com.dpdpxray.app.audit.AuditConfig
import com.dpdpxray.app.audit.AuditProgress
import com.dpdpxray.core.model.AuditPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VoiceAuditStartTest {
    private val instagram = AuditConfig("com.instagram.android", "Instagram", listOf(AuditPlan.ACCEPT))
    private val leaky = AuditConfig("com.leakyshop.demo", "LeakyShop", listOf(AuditPlan.ACCEPT))
    private fun running(c: AuditConfig) = AuditProgress.Running(c, 0, AuditPlan.ACCEPT, "Starting")

    @Test fun `another running check is reported and nothing is started`() {
        var started = false
        val outcome = VoiceAuditStart.start(instagram, progress = { running(leaky) }, startAudit = { started = true; true }, failure = { null })
        assertEquals(UiOutcome.CheckRunning("LeakyShop"), outcome)
        assertFalse("the orchestrator would ignore it and the follow-up would read LeakyShop's check", started)
    }

    @Test fun `a start that the orchestrator accepted is done`() {
        var progress: AuditProgress = AuditProgress.Idle
        val outcome = VoiceAuditStart.start(instagram, progress = { progress }, startAudit = { progress = running(it); true }, failure = { null })
        assertEquals(UiOutcome.Done, outcome)
    }

    @Test fun `a check that ended at once is still done, so the follow-up reports how it ended`() {
        var progress: AuditProgress = AuditProgress.Idle
        val outcome = VoiceAuditStart.start(instagram, progress = { progress }, startAudit = { progress = AuditProgress.Failed("VPN lost"); true }, failure = { null })
        assertEquals(UiOutcome.Done, outcome)
    }

    @Test fun `another check that won the race is reported, not followed`() {
        var progress: AuditProgress = AuditProgress.Idle
        // The screen's start returned true, but the orchestrator kept a check another caller started a moment earlier.
        val outcome = VoiceAuditStart.start(instagram, progress = { progress }, startAudit = { progress = running(leaky); true }, failure = { null })
        assertEquals(UiOutcome.CheckRunning("LeakyShop"), outcome)
    }

    @Test fun `a refused start carries the screen's reason`() {
        val outcome = VoiceAuditStart.start(instagram, progress = { AuditProgress.Idle }, startAudit = { false }, failure = { "Turn on the VPN first." })
        assertEquals(UiOutcome.Failed("Turn on the VPN first."), outcome)
    }
}
