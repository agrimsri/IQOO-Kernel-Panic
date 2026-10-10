package com.dpdpxray.core.automation

import com.dpdpxray.core.model.AuditPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Validation of a runner request against the phone's state. Pure logic: no phone, no Android. */
class RunRequestsTest {
    private val token = "abcdef-ghijkm-npqrst-uvwxyz"
    private val ready = PhoneReadiness(
        automationEnabled = true, pairingToken = token, ownPackage = AutomationContract.APP_PACKAGE,
        resetAllowlist = setOf("com.leakyshop.demo"), vpnPrepared = true, consentWatcherOn = true,
        shizukuReady = true, busy = null,
    )
    private val installed = setOf("com.leakyshop.demo", "com.example.shop", "com.example.nolauncher", AutomationContract.APP_PACKAGE)
    private val launchable = installed - "com.example.nolauncher"
    private val used = mutableSetOf<String>()

    private fun input(
        token: String? = this.token, pkg: String? = "com.leakyshop.demo", mode: String? = "full",
        autoTap: Any? = true, childFacing: Any? = null, runId: String? = "run-1",
    ) = RunRequestInput(token = token, packageName = pkg, mode = mode, autoTap = autoTap, childFacing = childFacing, runId = runId)

    private fun decide(input: RunRequestInput = input(), phone: PhoneReadiness = ready) = RunRequests.decide(
        input, phone, isInstalled = { it in installed }, isLaunchable = { it in launchable }, runIdUsed = { it in used },
    )

    private fun refused(d: RunDecision): RunDecision.Refused = d as? RunDecision.Refused ?: throw AssertionError("expected a refusal, got $d")

    @Test fun `a valid full request for an allowlisted test app is accepted with reset`() {
        val d = decide() as RunDecision.Accepted
        assertEquals(RunRequest("run-1", "com.leakyshop.demo", AutomationMode.FULL, autoTap = true, childFacing = false), d.request)
        assertTrue(d.resetAppData)
        assertEquals(listOf(AuditPlan.SILENT, AuditPlan.REJECT, AuditPlan.ACCEPT), d.request.mode.plans)
    }

    @Test fun `quick and preview keep data unless the app is allowlisted and Shizuku is ready`() {
        val quick = decide(input(pkg = "com.example.shop", mode = "quick")) as RunDecision.Accepted
        assertFalse("a non-allowlisted app is never reset", quick.resetAppData)
        assertEquals(listOf(AuditPlan.ACCEPT), quick.request.mode.plans)
        assertFalse(quick.request.mode.fastPreview)

        val allowlisted = decide(input(mode = "quick")) as RunDecision.Accepted
        assertTrue(allowlisted.resetAppData)
        val noShizuku = decide(input(mode = "quick"), ready.copy(shizukuReady = false)) as RunDecision.Accepted
        assertFalse(noShizuku.resetAppData)

        val preview = decide(input(pkg = "com.example.shop", mode = "preview")) as RunDecision.Accepted
        assertTrue(preview.request.mode.fastPreview)
        assertEquals(listOf(AuditPlan.ACCEPT), preview.request.mode.plans)
    }

    @Test fun `autoTap and childFacing accept booleans or the strings true and false and default to false`() {
        val strings = decide(input(autoTap = "true", childFacing = "TRUE")) as RunDecision.Accepted
        assertTrue(strings.request.autoTap)
        assertTrue(strings.request.childFacing)
        val absent = decide(input(autoTap = null, childFacing = null)) as RunDecision.Accepted
        assertFalse("agent tapping is never implied", absent.request.autoTap)
        assertFalse(absent.request.childFacing)
        val wrong = refused(decide(input(autoTap = 1)))
        assertEquals(RefusalCode.BAD_REQUEST, wrong.code)
        assertTrue(wrong.reason, wrong.reason.contains("autoTap"))
        assertEquals(RefusalCode.BAD_REQUEST, refused(decide(input(childFacing = "yes"))).code)
    }

    @Test fun `an invalid or missing run id is refused with nowhere to write`() {
        for (bad in listOf(null, "", "../x", "a/b", "run 1", "x".repeat(41), "run.1", "ünï")) {
            val r = refused(decide(input(runId = bad)))
            assertEquals("runId=$bad", RefusalCode.BAD_RUN_ID, r.code)
            assertNull("no status may be written for runId=$bad", r.runId)
        }
        assertTrue(decide(input(runId = "A-z_09".padEnd(40, 'x'))) is RunDecision.Accepted)
    }

    @Test fun `a run id that was already used is refused without overwriting the earlier run`() {
        used += "run-1"
        val r = refused(decide())
        assertEquals(RefusalCode.RUN_ID_USED, r.code)
        assertNull(r.runId)
    }

    @Test fun `automation off is refused first, even with a wrong token`() {
        val r = refused(decide(input(token = "wrong-token-wrong-token"), ready.copy(automationEnabled = false)))
        assertEquals(RefusalCode.AUTOMATION_OFF, r.code)
        assertEquals("run-1", r.runId)
        assertTrue(r.fix, r.fix.contains("Settings") && r.fix.contains("Developer automation"))
    }

    @Test fun `a missing or wrong token is refused before anything about the package is revealed`() {
        for (t in listOf(null, "", "abcdef-ghijkm-npqrst-uvwxyZ", "$token-x", "short")) {
            val r = refused(decide(input(token = t, pkg = "com.not.installed")))
            assertEquals("token=$t", RefusalCode.BAD_TOKEN, r.code)
            assertFalse(r.reason, r.reason.contains("com.not.installed"))
        }
        val noStored = refused(decide(phone = ready.copy(pairingToken = null)))
        assertEquals(RefusalCode.BAD_TOKEN, noStored.code)
    }

    @Test fun `the package must be valid, installed, launchable and not TraceVeil itself`() {
        assertEquals(RefusalCode.BAD_REQUEST, refused(decide(input(pkg = null))).code)
        assertEquals(RefusalCode.BAD_REQUEST, refused(decide(input(pkg = "not a package"))).code)
        assertEquals(RefusalCode.BAD_REQUEST, refused(decide(input(pkg = "com.x;rm -rf /"))).code)
        val self = refused(decide(input(pkg = AutomationContract.APP_PACKAGE, mode = "quick")))
        assertEquals(RefusalCode.SELF, self.code)
        val missing = refused(decide(input(pkg = "com.not.installed", mode = "quick")))
        assertEquals(RefusalCode.NOT_INSTALLED, missing.code)
        assertTrue(missing.reason, missing.reason.contains("com.not.installed"))
        assertTrue(missing.fix, missing.fix.contains("--apk"))
        assertEquals(RefusalCode.NOT_LAUNCHABLE, refused(decide(input(pkg = "com.example.nolauncher", mode = "quick"))).code)
    }

    @Test fun `the mode must be full, quick or preview`() {
        for (m in listOf(null, "", "FULL", "fast", "full;reboot")) {
            val r = refused(decide(input(mode = m)))
            assertEquals("mode=$m", RefusalCode.BAD_REQUEST, r.code)
        }
    }

    @Test fun `full needs the app on the reset allowlist and Shizuku`() {
        val notListed = refused(decide(input(pkg = "com.example.shop")))
        assertEquals(RefusalCode.NOT_ALLOWLISTED, notListed.code)
        assertTrue(notListed.fix, notListed.fix.contains("Test apps the runner may reset"))
        assertTrue(notListed.fix, notListed.fix.contains("--mode quick"))
        val noShizuku = refused(decide(phone = ready.copy(shizukuReady = false)))
        assertEquals(RefusalCode.NO_SHIZUKU, noShizuku.code)
        assertTrue(noShizuku.fix, noShizuku.fix.contains("Shizuku"))
    }

    @Test fun `the VPN and the consent watcher must already be allowed by the person`() {
        val vpn = refused(decide(phone = ready.copy(vpnPrepared = false)))
        assertEquals(RefusalCode.VPN_NOT_PREPARED, vpn.code)
        assertTrue(vpn.fix, vpn.fix.contains("Allow the VPN"))
        val watcher = refused(decide(phone = ready.copy(consentWatcherOn = false)))
        assertEquals(RefusalCode.WATCHER_OFF, watcher.code)
        assertTrue(watcher.fix, watcher.fix.contains("Accessibility"))
    }

    @Test fun `a check that is already running is not interrupted`() {
        val r = refused(decide(phone = ready.copy(busy = "an audit of LeakyShop")))
        assertEquals(RefusalCode.BUSY, r.code)
        assertTrue(r.reason, r.reason.contains("an audit of LeakyShop"))
    }

    @Test fun `contract paths are built only from a valid run id`() {
        assertEquals("/sdcard/Android/data/com.dpdpxray.app/files/automation/run-1/status.json", AutomationContract.deviceStatusPath("run-1"))
        assertEquals("/sdcard/Android/data/com.dpdpxray.app/files/automation/run-1/bundle.json", AutomationContract.deviceBundlePath("run-1"))
        assertEquals("com.dpdpxray.app/com.dpdpxray.app.automation.AutomationActivity", AutomationContract.COMPONENT)
        val thrown = runCatching { AutomationContract.deviceStatusPath("../../etc") }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }

    @Test fun `package names follow Android's rules`() {
        assertTrue(AutomationContract.isValidPackage("com.leakyshop.demo"))
        assertTrue(AutomationContract.isValidPackage("a.b_c.D9"))
        assertFalse(AutomationContract.isValidPackage("single"))
        assertFalse(AutomationContract.isValidPackage("com..x"))
        assertFalse(AutomationContract.isValidPackage("com.9x"))
        assertFalse(AutomationContract.isValidPackage("com.x'y"))
        assertFalse(AutomationContract.isValidPackage("com." + "x".repeat(300)))
    }
}
