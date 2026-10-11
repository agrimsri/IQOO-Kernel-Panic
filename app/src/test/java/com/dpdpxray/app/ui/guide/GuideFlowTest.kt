package com.dpdpxray.app.ui.guide

import com.dpdpxray.app.audit.AuditConfig
import com.dpdpxray.app.audit.AuditProgress
import com.dpdpxray.app.control.Preflight
import com.dpdpxray.core.model.AuditPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideFlowTest {
    private fun pf(vpn: Boolean = true, watcher: Boolean = true, privateDns: Boolean = false, shizukuRunning: Boolean = false, shizukuGranted: Boolean = false) =
        Preflight(vpn, watcher, shizukuRunning, shizukuGranted, privateDns, modelFound = false, modelLoaded = false)

    private val secretLabel = "SecretBankApp"
    private fun full(autoDrive: Boolean = false) = AuditConfig(
        "com.secret.bank", secretLabel, listOf(AuditPlan.SILENT, AuditPlan.REJECT, AuditPlan.ACCEPT), autoDrive = autoDrive, resetAppData = true,
    )
    private fun running(step: String, planIndex: Int = 1, config: AuditConfig = full()) =
        AuditProgress.Running(config, planIndex, config.plans[planIndex], step)

    @Test fun `home asks for the vpn first, then the watcher, then to check an app`() {
        val vpn = GuideFlow.home(pf(vpn = false, watcher = false))
        assertEquals(1, vpn.index)
        assertEquals(GuideFlow.TOTAL, vpn.total)
        assertEquals("Allow the VPN", vpn.title.en)
        assertEquals("home.vpn", vpn.key)

        val watcher = GuideFlow.home(pf(watcher = false))
        assertEquals(1, watcher.index)
        assertEquals("Turn on the consent watcher", watcher.title.en)

        val ready = GuideFlow.home(pf())
        assertEquals(2, ready.index)
        assertEquals("Check an app", ready.title.en)
        assertEquals(GuideTone.ACTION, ready.tone)
    }

    @Test fun `private dns is a caution on home, not a blocker`() {
        val step = GuideFlow.home(pf(privateDns = true))
        assertEquals(2, step.index)
        assertEquals(GuideTone.CAUTION, step.tone)
        assertTrue(step.body.en.contains("Private DNS"))
    }

    @Test fun `optional shizuku never becomes the home step`() {
        assertEquals("home.ready", GuideFlow.home(pf(shizukuRunning = true, shizukuGranted = false)).key)
    }

    @Test fun `a running check takes over the home step without speaking over the narrator`() {
        val step = GuideFlow.homeRunning(secretLabel)
        assertEquals(3, step.index)
        assertTrue(step.body.en.contains(secretLabel))
        assertEquals("", step.spoken.en)
    }

    @Test fun `picker step mentions the demo app only when it is installed`() {
        assertTrue(GuideFlow.picker(demoInstalled = true).body.en.contains("LeakyShop"))
        assertFalse(GuideFlow.picker(demoInstalled = false).body.en.contains("LeakyShop"))
        assertEquals(2, GuideFlow.picker(false).index)
        assertTrue(GuideFlow.pickerSetup(fullAvailable = true).body.en.contains("three times"))
        assertTrue(GuideFlow.pickerSetup(fullAvailable = false).body.en.contains("Shizuku"))
    }

    @Test fun `every orchestrator step string maps to a phase`() {
        val steps = mapOf(
            "Starting" to LivePhase.STARTING,
            "Starting per-app DNS capture" to LivePhase.STARTING,
            "Loading on-device AI model" to LivePhase.LOADING_AI,
            "Resetting authorised test app LeakyShop" to LivePhase.RESETTING,
            "Keeping LeakyShop's current data" to LivePhase.KEEPING_DATA,
            "Restarting LeakyShop (your data stays)" to LivePhase.RESTARTING,
            "Launching LeakyShop" to LivePhase.LAUNCHING,
            "Waiting for a consent screen" to LivePhase.WAITING_SCREEN,
            "Agent is choosing: reject" to LivePhase.AGENT_CHOOSING,
            "Your turn: tap Reject in the app" to LivePhase.YOUR_TURN,
            "Observing traffic after the recorded choice" to LivePhase.OBSERVING_CHOICE,
            "Observing traffic without a verified consent choice" to LivePhase.OBSERVING_NO_CHOICE,
            "Observing without touching the app" to LivePhase.OBSERVING_SILENT,
            "Finishing AI review of the consent screen" to LivePhase.REVIEWING,
            "Evaluating DPDP checks" to LivePhase.EVALUATING,
            "Something new" to LivePhase.OTHER,
        )
        steps.forEach { (step, phase) -> assertEquals(step, phase, LivePhase.of(step)) }
    }

    @Test fun `auto drive says TraceVeil will tap the planned choice for you`() {
        val step = GuideFlow.live(running("Waiting for a consent screen", planIndex = 1, config = full(autoDrive = true)))
        assertTrue(step.body.en, step.body.en.contains("TraceVeil will tap Reject for you"))
        assertTrue(step.body.en.contains(secretLabel))
        assertEquals(3, step.index)
    }

    @Test fun `manual choice becomes your turn with the planned button`() {
        val step = GuideFlow.live(running("Your turn: tap Accept in the app", planIndex = 2))
        assertEquals("Your turn: tap Accept", step.title.en)
        assertEquals(GuideTone.ACTION, step.tone)
        assertEquals("Your turn. Tap Accept in the app.", step.spoken.en)
    }

    @Test fun `a missing choice is a caution, not a success`() {
        val step = GuideFlow.live(running("Observing traffic without a verified consent choice"))
        assertEquals(GuideTone.CAUTION, step.tone)
        assertTrue(step.body.en.contains("unknown"))
    }

    @Test fun `silent waiting and observing share one key so guidance is not repeated`() {
        val waiting = GuideFlow.live(running("Waiting for a consent screen", planIndex = 0))
        val observing = GuideFlow.live(running("Observing without touching the app", planIndex = 0))
        assertEquals(waiting.key, observing.key)
        assertEquals("Don't touch the app", waiting.title.en)
    }

    @Test fun `different runs of the same phase get different keys`() {
        assertNotEquals(
            GuideFlow.live(running("Launching X", planIndex = 1)).key,
            GuideFlow.live(running("Launching X", planIndex = 2)).key,
        )
    }

    @Test fun `first phase of each run announces the run number`() {
        val step = GuideFlow.live(running("Resetting authorised test app $secretLabel", planIndex = 1))
        assertTrue(step.spoken.en, step.spoken.en.startsWith("Run 2 of 3."))
        assertTrue(step.spoken.hi, step.spoken.hi.startsWith("रन 2, कुल 3 में से।"))
    }

    @Test fun `spoken guidance never contains the app name or raw step text`() {
        val phases = listOf(
            "Starting per-app DNS capture", "Loading on-device AI model", "Resetting authorised test app $secretLabel",
            "Keeping $secretLabel's current data", "Restarting $secretLabel (your data stays)", "Launching $secretLabel", "Waiting for a consent screen", "Agent is choosing: reject",
            "Your turn: tap Reject in the app", "Observing traffic after the recorded choice",
            "Observing traffic without a verified consent choice", "Observing without touching the app",
            "Finishing AI review of the consent screen", "Evaluating DPDP checks", "Unexpected $secretLabel step",
        )
        listOf(false, true).forEach { auto ->
            (0..2).forEach { i ->
                phases.forEach { s ->
                    val step = GuideFlow.live(running(s, planIndex = i, config = full(autoDrive = auto)))
                    assertFalse(step.spoken.en, step.spoken.en.contains(secretLabel))
                    assertFalse(step.spoken.hi, step.spoken.hi.contains(secretLabel))
                }
            }
        }
    }

    @Test fun `an unknown step is shown as text but not spoken`() {
        val step = GuideFlow.live(running("Unexpected step"))
        assertEquals("Unexpected step", step.title.en)
        assertEquals("", step.spoken.en)
        assertEquals("", step.spoken.hi)
    }

    @Test fun `narration is silent when spoken guidance is off`() {
        assertNull(GuideFlow.narration(running("Your turn: tap Reject in the app"), enabled = false, lang = GuideLang.EN))
        assertNull(GuideFlow.narration(AuditProgress.Idle, enabled = true, lang = GuideLang.EN))
        assertNull(GuideFlow.narration(AuditProgress.Done("audit-1"), enabled = true, lang = GuideLang.EN))
    }

    @Test fun `narration speaks live steps in the chosen language`() {
        val en = GuideFlow.narration(running("Your turn: tap Reject in the app"), enabled = true, lang = GuideLang.EN)!!
        assertEquals("Your turn. Tap Reject in the app.", en.text)
        val hi = GuideFlow.narration(running("Your turn: tap Reject in the app"), enabled = true, lang = GuideLang.HI)!!
        assertEquals("अब आपकी बारी। ऐप में Reject दबाएँ।", hi.text)
        assertEquals(GuideLang.HI, hi.lang)
    }

    @Test fun `a failed check is narrated without its message`() {
        val n = GuideFlow.narration(AuditProgress.Failed("Cannot launch com.secret.bank"), enabled = true, lang = GuideLang.EN)!!
        assertFalse(n.text.contains("secret"))
        assertEquals("live.failed", n.key)
    }

    @Test fun `every step has both languages and short spoken text`() {
        val steps = listOf(
            GuideFlow.home(pf(vpn = false)), GuideFlow.home(pf(watcher = false)), GuideFlow.home(pf(privateDns = true)), GuideFlow.home(pf()),
            GuideFlow.picker(true), GuideFlow.picker(false), GuideFlow.pickerSetup(true), GuideFlow.pickerSetup(false),
        ) + listOf(
            "Starting", "Loading on-device AI model", "Resetting authorised test app X", "Keeping X's current data", "Restarting X (your data stays)", "Launching X",
            "Waiting for a consent screen", "Agent is choosing: accept", "Your turn: tap Reject in the app",
            "Observing traffic after the recorded choice", "Observing traffic without a verified consent choice",
            "Observing without touching the app", "Finishing AI review of the consent screen", "Evaluating DPDP checks",
        ).map { GuideFlow.live(running(it)) }
        steps.forEach { s ->
            listOf(s.title, s.body, s.spoken).forEach { bi ->
                assertTrue(s.key, bi.en.isNotBlank())
                assertTrue(s.key, bi.hi.any { it in 'ऀ'..'ॿ' })
            }
            assertTrue("${s.key}: ${s.spoken.en}", s.spoken.en.length <= 140)
        }
    }

    @Test fun `step labels are localised`() {
        val s = GuideFlow.home(pf())
        assertEquals("Step 2 of 4", s.label(GuideLang.EN))
        assertEquals("चरण 2 / 4", s.label(GuideLang.HI))
    }

    @Test fun `guide language falls back to English`() {
        assertEquals(GuideLang.HI, GuideLang.of("hi"))
        assertEquals(GuideLang.EN, GuideLang.of("fr"))
        assertEquals(GuideLang.EN, GuideLang.of(null))
    }
}
