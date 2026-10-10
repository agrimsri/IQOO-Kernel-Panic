package com.dpdpxray.app.voice.assistant

import com.dpdpxray.core.assistant.AppCandidate
import com.dpdpxray.core.assistant.OpenTarget
import com.dpdpxray.core.assistant.VoiceCommand
import com.dpdpxray.core.assistant.VoiceCommandParser
import com.dpdpxray.core.copilot.AuditEvidence
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.ScoreBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantPlannerTest {
    private val instagram = AppCandidate("Instagram", "com.instagram.android")
    private val instagramLite = AppCandidate("Instagram Lite", "com.instagram.lite")
    private val leaky = AppCandidate("LeakyShop", "com.leakyshop.demo")
    private val apps = listOf(instagram, instagramLite, leaky, AppCandidate("Spotify", "com.spotify.music"))
    private val ready = VoiceReadiness(vpnReady = true, watcherOn = true, privateDnsActive = false)
    private val saved = SavedAudit(
        "audit-1", AuditEvidence("LeakyShop", sample = false, result = AuditResult(emptyList(), 100, ScoreBand.READY), plans = setOf(AuditPlan.ACCEPT), completedPlans = setOf(AuditPlan.ACCEPT)),
    )
    private val ctx = AssistantContext(
        source = AssistantSource.WAKE, apps = apps, readiness = ready, audit = saved,
        routes = setOf(AssistantPlanner.ROUTE_CHECKUP, AssistantPlanner.ROUTE_TRUST, AssistantPlanner.ROUTE_APK_GUARD), wakeListening = true,
    )

    private fun plan(text: String, c: AssistantContext = ctx) = AssistantPlanner.plan(VoiceCommandParser.parse(text).command, c)

    @Test fun `check app starts a real Quick audit with the user choosing on a personal app`() {
        val step = plan("check Instagram")
        val start = step.action as AssistantAction.StartAudit
        assertEquals("com.instagram.android", start.config.targetPackage)
        assertEquals(listOf(AuditPlan.ACCEPT), start.config.plans)
        assertFalse(start.config.resetAppData)
        assertFalse(start.config.autoDrive)
        assertTrue(step.reply.en, step.reply.en.contains("Choose on the app's popup, I'm watching."))
        assertTrue(step.reply.en.contains("in this check"))
    }

    @Test fun `the demo app is tapped by the agent and the reply says so`() {
        val step = plan("check leaky shop")
        val start = step.action as AssistantAction.StartAudit
        assertTrue(start.config.autoDrive)
        assertTrue(step.reply.en, step.reply.en.contains("tap Accept"))
    }

    @Test fun `Hinglish and Devanagari commands start the same audit`() {
        listOf("Instagram ko check karo", "इंस्टाग्राम की जाँच करो").forEach {
            assertEquals(it, "com.instagram.android", ((plan(it).action) as AssistantAction.StartAudit).config.targetPackage)
        }
    }

    @Test fun `an unclear name asks which one with the top two options and starts nothing`() {
        val step = plan("check insta")
        assertNull(step.action)
        assertEquals(listOf(instagram, instagramLite).toSet(), step.choices.toSet())
        assertTrue(step.reply.en, step.reply.en.endsWith("Which one?"))
        val picked = AssistantPlanner.checkPicked(instagramLite, ctx)
        assertEquals("com.instagram.lite", (picked.action as AssistantAction.StartAudit).config.targetPackage)
    }

    @Test fun `an app that is not installed is reported, not guessed`() {
        val step = plan("check Netflix")
        assertNull(step.action)
        assertEquals("I couldn't find an app called netflix on this phone.", step.reply.en)
    }

    @Test fun `check without a name opens the app list`() {
        assertEquals(AssistantAction.Navigate(AssistantPlanner.ROUTE_PICK), plan("check an app").action)
    }

    @Test fun `readiness is checked first and the right screen is opened`() {
        val noVpn = plan("check Instagram", ctx.copy(readiness = ready.copy(vpnReady = false)))
        assertEquals(AssistantAction.FixSetup(SetupFix.VPN), noVpn.action)
        assertTrue(noVpn.reply.en.contains("VPN"))
        val noWatcher = plan("check Instagram", ctx.copy(readiness = ready.copy(watcherOn = false)))
        assertEquals(AssistantAction.FixSetup(SetupFix.WATCHER), noWatcher.action)
        assertTrue(noWatcher.reply.en.contains("consent watcher"))
        val privateDns = plan("check Instagram", ctx.copy(readiness = ready.copy(privateDnsActive = true)))
        assertEquals(AssistantAction.FixSetup(SetupFix.PRIVATE_DNS), privateDns.action)
        assertTrue(privateDns.reply.en.contains("Private DNS"))
    }

    @Test fun `no second audit while one runs or a quarantine run is active`() {
        assertNull(plan("check Instagram", ctx.copy(auditRunning = true)).action)
        assertNull(plan("check Instagram", ctx.copy(quarantineActive = true)).action)
        assertNull(AssistantPlanner.checkPicked(instagram, ctx.copy(auditRunning = true)).action)
    }

    @Test fun `a running check is named and nothing is promised about it`() {
        val running = ctx.copy(auditRunning = true, runningLabel = "LeakyShop")
        val step = plan("check Instagram", running)
        assertEquals("A check of LeakyShop is already running. Wait for it to finish, then ask me again.", step.reply.en)
        assertTrue(step.reply.hi, step.reply.hi.contains("LeakyShop"))
        assertEquals(step.reply, AssistantPlanner.checkPicked(instagram, running).reply)
        // Started from the app picker, nothing follows that check, so the reply must not promise to report on it.
        listOf(step.reply, plan("check Instagram", ctx.copy(auditRunning = true)).reply).forEach { r ->
            assertFalse(r.en, r.en.contains("I'll tell you"))
            assertFalse(r.hi, r.hi.contains("बताऊँगा"))
        }
    }

    @Test fun `a start the screen refused because another check runs says so instead of the planned reply`() {
        val start = plan("check Instagram").action as AssistantAction.StartAudit
        val planned = plan("check Instagram").reply
        assertEquals(planned, AssistantPlanner.afterScreenWork(planned, start, UiOutcome.Done))
        val busy = AssistantPlanner.afterScreenWork(planned, start, UiOutcome.CheckRunning("LeakyShop"))
        assertEquals("A check of LeakyShop is already running. Wait for it to finish, then ask me again.", busy.en)
        assertEquals(AssistantCopy.tapNotification, AssistantPlanner.afterScreenWork(planned, start, UiOutcome.NotReached()))
        assertEquals(AssistantCopy.unlockFirst, AssistantPlanner.afterScreenWork(planned, start, UiOutcome.NotReached(locked = true)))
        assertEquals(AssistantCopy.couldNotStart("no VPN"), AssistantPlanner.afterScreenWork(planned, start, UiOutcome.Failed("no VPN")))
    }

    @Test fun `a voice command never starts a Full audit, Fast Preview or a reset`() {
        listOf("check Instagram", "check leaky shop", "audit spotify", "run a full audit of instagram", "reset and check instagram").forEach { text ->
            val action = plan(text).action
            if (action is AssistantAction.StartAudit) {
                assertEquals(text, listOf(AuditPlan.ACCEPT), action.config.plans)
                assertFalse(text, action.config.resetAppData)
                assertFalse(text, action.config.fastPreview)
            }
        }
    }

    @Test fun `asking for a full or reset audit by voice gets a Quick check and is told so`() {
        val step = plan("run a full audit of instagram")
        val start = step.action as AssistantAction.StartAudit
        assertEquals(listOf(AuditPlan.ACCEPT), start.config.plans)
        assertFalse(start.config.resetAppData)
        assertTrue(step.reply.en, step.reply.en.startsWith("By voice I only run a Quick check that keeps the app's data"))
    }

    @Test fun `last result reads the saved summary and opens it only if TraceVeil is on screen`() {
        val step = plan("what did you find")
        assertTrue(step.reply.en, step.reply.en.startsWith("In this check, LeakyShop scored 100 out of 100"))
        assertEquals(AssistantAction.Navigate("results/audit-1", optional = true), step.action)
        assertNull("the copilot already shows the audit", plan("what did you find", ctx.copy(source = AssistantSource.COPILOT)).action)
        assertEquals(AssistantCopy.noSavedChecks, plan("read my last result", ctx.copy(audit = null)).reply)
    }

    @Test fun `questions go to the grounded copilot, or say there is nothing saved`() {
        val step = plan("which trackers did it contact")
        assertEquals("which trackers did it contact", step.question)
        assertNull(step.action)
        assertNull(plan("which trackers did it contact", ctx.copy(audit = null)).question)
    }

    @Test fun `open screens, including the ones other features add`() {
        assertEquals(AssistantAction.Navigate("reports"), plan("open reports").action)
        assertEquals(AssistantAction.Navigate("settings"), plan("open settings").action)
        assertEquals(AssistantAction.Navigate("home"), plan("go home").action)
        assertEquals(AssistantAction.Navigate("trust"), plan("open the trust map").action)
        assertEquals(AssistantAction.Navigate("checkup"), plan("check my phone").action)
        assertEquals(AssistantAction.PickApkFile, plan("check an apk file").action)
        val older = ctx.copy(routes = emptySet())
        assertNull(plan("open the trust map", older).action)
        assertTrue(plan("check my phone", older).reply.en.contains("not in this version"))
        assertNull(AssistantPlanner.plan(VoiceCommand.Open(null), ctx).action)
        assertEquals(AssistantCopy.opening("Settings", "सेटिंग्स"), AssistantPlanner.plan(VoiceCommand.Open(OpenTarget.SETTINGS), ctx).reply)
    }

    @Test fun `help, stop and empty`() {
        assertTrue(plan("what can you do").reply.en.contains("check Instagram"))
        assertTrue(plan("stop listening").stopListening)
        assertFalse(plan("stop listening", ctx.copy(wakeListening = false)).stopListening)
        assertEquals(AssistantCopy.notCaught, plan(""))
    }

    @Test fun `no reply claims an app is safe or harmful`() {
        val texts = listOf("check Instagram", "check leaky shop", "what did you find", "what can you do", "check insta", "check Netflix")
            .flatMap { plan(it).reply.let { r -> listOf(r.en, r.hi) } }
        texts.forEach { t ->
            listOf(" is safe", "malware", "malicious", "fraud", "compliant", "illegal").forEach { bad -> assertFalse("$bad in $t", t.lowercase().contains(bad)) }
        }
    }

    private fun assertEquals(expected: Said, actual: AssistantStep) = assertEquals(expected, actual.reply)
}
