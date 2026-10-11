package com.dpdpxray.app.audit

import com.dpdpxray.app.data.AuditMeta
import com.dpdpxray.app.ui.modes.CheckChoice
import com.dpdpxray.app.ui.modes.CheckSheet
import com.dpdpxray.app.data.Audience
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.PlanConditions
import com.dpdpxray.core.model.RestartChoice
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.model.StartMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stopping an app switches off things it runs: on this phone (and in stock Android) a force-stop disables the app's
 * accessibility service. Before a check TraceVeil reads what a restart would switch off; the default is then not to
 * restart, the person can choose "Restart anyway", and the choice is recorded. The phone readings are faked here.
 */
class RestartSafeguardTest {
    private val pkg = "com.reskill.hacktracker"

    private class Probe(
        var accessibilitySetting: String? = null,
        var accessibilityList: Set<String>? = null,
        var ime: String? = null,
        var home: String? = null,
        var sms: String? = null,
        var admins: Set<String>? = null,
        var listeners: Set<String>? = null,
        var alwaysOnVpn: String? = null,
        var services: String? = null,
    ) : SideEffectProbe {
        override fun enabledAccessibilityServices() = accessibilitySetting
        override fun accessibilityPackages() = accessibilityList
        override fun defaultInputMethod() = ime
        override fun homePackage() = home
        override fun smsPackage() = sms
        override fun deviceAdminPackages() = admins
        override fun notificationListenerPackages() = listeners
        override suspend fun alwaysOnVpnPackage() = alwaysOnVpn
        override suspend fun runningServices(pkg: String) = services
    }

    private fun kinds(p: Probe, target: String = pkg) = runBlocking { RestartSideEffects.detect(target, p) }.map { it.kind }

    // --- detection ---------------------------------------------------------------------------------------------------

    @Test fun `an enabled accessibility service of the app is found from the secure setting or the manager list`() {
        val setting = "com.google.android.marvin.talkback/.TalkBackService:$pkg/com.reskill.hacktracker.ScreenReaderService"
        assertEquals(listOf(RestartSideEffect.ACCESSIBILITY), kinds(Probe(accessibilitySetting = setting)))
        assertEquals(listOf(RestartSideEffect.ACCESSIBILITY), kinds(Probe(accessibilityList = setOf(pkg))))
        assertEquals("another app's service is not this app's", emptyList<RestartSideEffect>(), kinds(Probe(accessibilitySetting = setting), "com.other.app"))
        assertEquals("a package name that only starts the same is not the app", emptyList<RestartSideEffect>(),
            kinds(Probe(accessibilitySetting = "$pkg.helper/.Svc")))
    }

    @Test fun `keyboard, home, SMS, device admin and notification access are read from their own settings`() {
        assertEquals(listOf(RestartSideEffect.KEYBOARD), kinds(Probe(ime = "$pkg/.Keyboard")))
        assertEquals(listOf(RestartSideEffect.HOME), kinds(Probe(home = pkg)))
        assertEquals(listOf(RestartSideEffect.SMS), kinds(Probe(sms = pkg)))
        assertEquals(listOf(RestartSideEffect.DEVICE_ADMIN), kinds(Probe(admins = setOf(pkg, "com.google.android.gms"))))
        assertEquals(listOf(RestartSideEffect.NOTIFICATION_LISTENER), kinds(Probe(listeners = setOf(pkg))))
    }

    private val vpnDump = """
        ACTIVITY MANAGER SERVICES (dumpsys activity services)
          User 0 active services:
          * ServiceRecord{a1b2c3 u0 $pkg/.net.TunnelService}
            intent={cmp=$pkg/.net.TunnelService}
            packageName=$pkg
            processName=$pkg
            permission=android.permission.BIND_VPN_SERVICE
            app=ProcessRecord{d4e5 1234:$pkg/u0a321}
            createTime=-5m2s startingBgTimeout=--
    """.trimIndent()

    private val musicDump = """
          * ServiceRecord{f00 u0 $pkg/.player.PlaybackService}
            packageName=$pkg
            app=ProcessRecord{abc 99:$pkg/u0a321}
            isForeground=true foregroundId=7 foregroundNoti=Notification(channel=playback) types=0x00000002
    """.trimIndent()

    @Test fun `a running VPN service, an always-on VPN and a foreground service are read through the shell`() {
        assertEquals(listOf(RestartSideEffect.VPN), kinds(Probe(services = vpnDump)))
        assertEquals(listOf(RestartSideEffect.VPN), kinds(Probe(alwaysOnVpn = pkg)))
        val music = runBlocking { RestartSideEffects.detect(pkg, Probe(services = musicDump)) }.single()
        assertEquals(RestartSideEffect.FOREGROUND_SERVICE, music.kind)
        assertEquals("playing media", music.detail)
        assertEquals("a stopped VPN service (no process) is not running", emptyList<RestartSideEffect>(),
            kinds(Probe(services = vpnDump.replace(Regex("app=ProcessRecord\\{[^}]*}"), "app=null"))))
    }

    @Test fun `nothing readable means nothing is claimed`() {
        assertTrue(kinds(Probe()).isEmpty())
    }

    // --- the decision before each plan -------------------------------------------------------------------------------

    private val accessibility = listOf(FoundSideEffect(RestartSideEffect.ACCESSIBILITY))

    @Test fun `by default an app with an accessibility service is not restarted`() {
        val d = RestartDecision.decide(RestartPolicy.AUTO, accessibility, resetApproved = false, app = "HackTracker")
        assertFalse(d.stop)
        assertEquals(RestartChoice.SKIPPED_BY_DEFAULT, d.choice)
        assertEquals("restarting it would switch off its accessibility service", d.reason)
        assertEquals("Not restarted before this plan: restarting HackTracker would switch off its accessibility service, so TraceVeil left it running (the safe default).", d.note)
    }

    @Test fun `the person's choice is followed and recorded`() {
        val skip = RestartDecision.decide(RestartPolicy.SKIP, accessibility, resetApproved = false, app = "HackTracker")
        assertFalse(skip.stop)
        assertEquals(RestartChoice.SKIPPED_BY_PERSON, skip.choice)
        assertTrue(skip.note!!, skip.note!!.startsWith("Not restarted before this plan: you chose to check without restarting"))
        val anyway = RestartDecision.decide(RestartPolicy.ALWAYS, accessibility, resetApproved = false, app = "HackTracker")
        assertTrue(anyway.stop)
        assertEquals(RestartChoice.RESTARTED_BY_PERSON, anyway.choice)
        assertEquals("Restarted as you chose, although it switches off HackTracker's accessibility service; turn it on again in Accessibility settings.", anyway.note)
        assertNull(anyway.reason)
    }

    @Test fun `with nothing to switch off, and for an approved reset, the app is stopped as before`() {
        val plain = RestartDecision.decide(RestartPolicy.AUTO, emptyList(), resetApproved = false, app = "LeakyShop")
        assertTrue(plain.stop)
        assertNull(plain.choice)
        assertNull(plain.note)
        val reset = RestartDecision.decide(RestartPolicy.AUTO, accessibility, resetApproved = true, app = "TestApp")
        assertTrue(reset.stop)
        assertEquals(RestartChoice.RESTARTED_BY_PERSON, reset.choice)
    }

    private class Shell {
        val calls = mutableListOf<String>()
        fun start() = AppStart({ true }, { calls += "pidof $it"; true }, { calls += "force-stop $it"; Result.success("") }, { calls += "clear $it"; Result.success("") })
    }

    @Test fun `a plan left running is opened without any force-stop and says why`() = runBlocking {
        val shell = Shell()
        val o = shell.start().prepare(pkg, resetApproved = false, keepRunning = "restarting it would switch off its accessibility service")
        assertEquals(listOf("pidof $pkg"), shell.calls)
        assertEquals(StartMode.NOT_RESTARTED, o.mode)
        assertEquals(true, o.runningBefore)
        assertEquals("restarting it would switch off its accessibility service", o.problem)
        assertTrue("kept data stays marked", o.notes.any { it.startsWith("App data was not cleared") })
        assertFalse(o.notes.any { it.startsWith("Could not restart") })
    }

    // --- the check sheet and check again ---------------------------------------------------------------------------

    @Test fun `the sheet's choice maps to the policy, and the safe option is the default`() {
        assertEquals(RestartPolicy.AUTO, CheckSheet.restartPolicy(effects = null, restartAnyway = false))
        assertEquals(RestartPolicy.AUTO, CheckSheet.restartPolicy(effects = emptyList(), restartAnyway = true))
        assertEquals(RestartPolicy.SKIP, CheckSheet.restartPolicy(accessibility, restartAnyway = false))
        assertEquals(RestartPolicy.ALWAYS, CheckSheet.restartPolicy(accessibility, restartAnyway = true))
        val config = CheckSheet.config(Audience.USER, pkg, "HackTracker", CheckChoice.QUICK, false, true, false, false, false, RestartPolicy.SKIP)
        assertEquals(RestartPolicy.SKIP, config.restart)
        assertEquals(RestartPolicy.AUTO, AuditConfig(pkg, "HackTracker", listOf(AuditPlan.REJECT)).restart)
    }

    private fun recorded(choice: RestartChoice) = AuditSession(
        "s", pkg, "HackTracker", AuditPlan.REJECT,
        conditions = PlanConditions(restartSideEffects = listOf("ACCESSIBILITY"), restartChoice = choice),
    )

    @Test fun `check again carries the person's restart choice, and a reminder follows a restart anyway`() {
        val meta = AuditMeta("a", pkg, "HackTracker", 0, null, ScoreBand.INCONCLUSIVE, 1, listOf(AuditPlan.REJECT.label))
        assertEquals(RestartPolicy.AUTO, CheckAgain.config(meta, listOf(recorded(RestartChoice.SKIPPED_BY_DEFAULT)), CheckAgain.Mode.RESTART).restart)
        assertEquals(RestartPolicy.ALWAYS, CheckAgain.config(meta, emptyList(), CheckAgain.Mode.RESTART, RestartPolicy.ALWAYS).restart)
        assertEquals(listOf(RestartSideEffect.ACCESSIBILITY), RestartReminder.recorded(listOf(recorded(RestartChoice.SKIPPED_BY_DEFAULT))))
        assertEquals(listOf(RestartSideEffect.ACCESSIBILITY), RestartReminder.toTurnBackOn(listOf(recorded(RestartChoice.RESTARTED_BY_PERSON))))
        assertTrue(RestartReminder.toTurnBackOn(listOf(recorded(RestartChoice.SKIPPED_BY_PERSON))).isEmpty())
    }

    // --- words -------------------------------------------------------------------------------------------------------

    @Test fun `the warning says what a restart switches off, in both languages`() {
        val c = RestartSafeguardCopy
        assertEquals(
            "Restarting HackTracker will switch off its accessibility service (Android does this when an app is stopped). " +
                "You'd need to turn it on again in Accessibility settings.",
            c.warning("HackTracker", FoundSideEffect(RestartSideEffect.ACCESSIBILITY)).en,
        )
        assertEquals("Check without restarting", c.skip.en)
        assertEquals("Restart anyway", c.anyway.en)
        assertEquals("Open Accessibility settings", c.settingsLabel(RestartSideEffect.ACCESSIBILITY)?.en)
        assertEquals("You chose to restart HackTracker, which switched off its accessibility service. Turn it on again in Accessibility settings.",
            c.reminder("HackTracker", RestartSideEffect.ACCESSIBILITY).en)
        RestartSideEffect.entries.forEach { k ->
            val w = c.warning("App", FoundSideEffect(k, "playing media"))
            assertTrue(k.name, w.en.contains("App") && w.hi.contains("App"))
            assertFalse(k.name, w.en.contains("malicious", ignoreCase = true) || w.en.contains("safe", ignoreCase = true))
        }
        assertTrue(c.warning("App", FoundSideEffect(RestartSideEffect.ACCESSIBILITY), reset = true).en.startsWith("Resetting App will switch off"))
    }
}
