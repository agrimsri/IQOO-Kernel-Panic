package com.dpdpxray.app.ui.guide

import com.dpdpxray.app.audit.AuditConfig
import com.dpdpxray.app.audit.AuditProgress
import com.dpdpxray.app.data.XraySettings
import com.dpdpxray.core.model.AuditPlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GuideNarratorTest {
    private class Recorder : GuideSpeaker {
        val events = mutableListOf<String>()
        override fun say(key: String, text: String, lang: GuideLang) { events += "say:$key" }
        override fun stop(key: String?) { events += "stop:$key" }
    }

    private val config = AuditConfig("com.leakyshop.demo", "LeakyShop", listOf(AuditPlan.REJECT))

    @Test fun `speaks each step once and stops the last step when the check ends`() = runTest(UnconfinedTestDispatcher()) {
        val progress = MutableStateFlow<AuditProgress>(AuditProgress.Idle)
        val settings = MutableStateFlow(XraySettings(spokenGuidance = true))
        val speaker = Recorder()
        val job = launch { GuideNarrator.run(progress, settings, speaker) }
        progress.value = AuditProgress.Running(config, 0, AuditPlan.REJECT, "Your turn: tap Reject in the app")
        progress.value = AuditProgress.Running(config, 0, AuditPlan.REJECT, "Your turn: tap Reject in the app")
        progress.value = AuditProgress.Idle
        job.cancel()
        assertEquals(listOf("say:live.0.turn", "stop:live.0.turn"), speaker.events)
    }

    @Test fun `stays silent while spoken guidance is off`() = runTest(UnconfinedTestDispatcher()) {
        val progress = MutableStateFlow<AuditProgress>(AuditProgress.Idle)
        val settings = MutableStateFlow(XraySettings(spokenGuidance = false))
        val speaker = Recorder()
        val job = launch { GuideNarrator.run(progress, settings, speaker) }
        progress.value = AuditProgress.Running(config, 0, AuditPlan.REJECT, "Launching LeakyShop")
        job.cancel()
        assertEquals(emptyList<String>(), speaker.events)
    }
}
