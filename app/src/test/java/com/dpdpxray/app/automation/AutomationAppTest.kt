package com.dpdpxray.app.automation

import com.dpdpxray.app.audit.AuditConfig
import com.dpdpxray.app.audit.AuditProgress
import com.dpdpxray.core.automation.AutomationContract
import com.dpdpxray.core.automation.AutomationMode
import com.dpdpxray.core.automation.AutomationStatuses
import com.dpdpxray.core.automation.RunRequest
import com.dpdpxray.core.model.AuditPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Developer automation on the phone side: settings, the audit it starts and the files it writes. No device needed. */
class AutomationAppTest {
    @get:Rule val temp = TemporaryFolder()

    private var tokens = 0
    private val newToken = { "token${++tokens}-aaaaaa-bbbbbb" }

    @Test fun `automation is off by default with no token and an empty reset allowlist`() {
        val s = AutomationSettings()
        assertFalse(s.enabled)
        assertNull(s.token)
        assertTrue(s.resetAllowlist.isEmpty())
    }

    @Test fun `switching on creates a token once and keeps it until Regenerate`() {
        val on = AutomationSettings().switchedOn(true, newToken)
        assertTrue(on.enabled)
        assertEquals("token1-aaaaaa-bbbbbb", on.token)
        val offAgain = on.switchedOn(false, newToken).switchedOn(true, newToken)
        assertEquals("the CI secret keeps working after off/on", "token1-aaaaaa-bbbbbb", offAgain.token)
        val regenerated = offAgain.withNewToken(newToken)
        assertEquals("token2-aaaaaa-bbbbbb", regenerated.token)
        assertNotEquals(offAgain.token, regenerated.token)
    }

    @Test fun `only valid packages other than TraceVeil can be allowlisted for reset`() {
        val own = AutomationContract.APP_PACKAGE
        val s = AutomationSettings().allowing("com.leakyshop.demo", own).allowing(own, own).allowing("not a package", own)
        assertEquals(setOf("com.leakyshop.demo"), s.resetAllowlist)
        assertTrue(s.disallowing("com.leakyshop.demo").resetAllowlist.isEmpty())
    }

    @Test fun `a full run starts the same three-plan audit as the check setup, with reset and agent taps`() {
        val config = AutomationAudits.config(RunRequest("r1", "com.leakyshop.demo", AutomationMode.FULL, autoTap = true, childFacing = false), "LeakyShop", resetAppData = true)
        assertEquals(
            AuditConfig(
                targetPackage = "com.leakyshop.demo", targetLabel = "LeakyShop",
                plans = listOf(AuditPlan.SILENT, AuditPlan.REJECT, AuditPlan.ACCEPT),
                childFacing = false, includeGms = false, autoDrive = true, resetAppData = true, fastPreview = false,
            ),
            config,
        )
    }

    @Test fun `quick and preview are single Accept plans and keep data unless reset was approved`() {
        val quick = AutomationAudits.config(RunRequest("r2", "com.example.shop", AutomationMode.QUICK, autoTap = false, childFacing = true), "Shop", resetAppData = false)
        assertEquals(listOf(AuditPlan.ACCEPT), quick.plans)
        assertFalse(quick.resetAppData)
        assertFalse("agent taps only when asked", quick.autoDrive)
        assertTrue(quick.childFacing)
        assertFalse(quick.fastPreview)
        val preview = AutomationAudits.config(RunRequest("r3", "com.example.shop", AutomationMode.PREVIEW, autoTap = true, childFacing = false), "Shop", resetAppData = false)
        assertTrue(preview.fastPreview)
        assertEquals(listOf(AuditPlan.ACCEPT), preview.plans)
    }

    @Test fun `progress steps name the plan and its position`() {
        val config = AutomationAudits.config(RunRequest("r1", "com.leakyshop.demo", AutomationMode.FULL, autoTap = true, childFacing = false), "LeakyShop", true)
        val step = AutomationAudits.step(AuditProgress.Running(config, 1, AuditPlan.REJECT, "Waiting for a consent screen"))
        assertEquals("Plan B · Reject (2 of 3): Waiting for a consent screen", step)
    }

    @Test fun `files go only under automation slash run id, replaced whole`() {
        val root = temp.newFolder("automation")
        val files = AutomationFiles(root)
        val request = RunRequest("run-1", "com.leakyshop.demo", AutomationMode.FULL, autoTap = true, childFacing = false)
        val running = AutomationStatuses.running(request, true, 1_791_590_400_000L, "1.1.0")
        assertFalse(files.exists("run-1"))
        files.writeStatus(running)
        assertTrue(files.exists("run-1"))
        val status = File(root, "run-1/status.json")
        assertEquals(running, AutomationStatuses.decode(status.readText()))
        val done = AutomationStatuses.done(running, "audit-1", 21, "AT_RISK", 5, 1_791_590_470_000L)
        files.writeStatus(done)
        assertEquals(done, AutomationStatuses.decode(status.readText()))
        files.writeBundle("run-1", "{\"schema\":\"traceveil.audit.v1\"}")
        assertEquals("{\"schema\":\"traceveil.audit.v1\"}\n", File(root, "run-1/bundle.json").readText())
        assertEquals("no temporary files are left behind", setOf("status.json", "bundle.json"), File(root, "run-1").list()!!.toSet())
        for (bad in listOf("../x", "a/b", "", "x".repeat(41))) {
            assertTrue(bad, runCatching { files.exists(bad) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(bad, runCatching { files.writeBundle(bad, "{}") }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun `old runs are pruned, never the current one or other folders`() {
        val root = temp.newFolder("automation")
        val files = AutomationFiles(root)
        (1..5).forEach { i -> File(root, "run-$i").apply { mkdirs(); File(this, "status.json").writeText("{}"); setLastModified(1_000_000L * i) } }
        File(root, "not a run").mkdirs()
        File(root, "run-1").setLastModified(1L) // the oldest, but it is the run in progress
        files.prune(keep = 2, current = "run-1")
        assertEquals(setOf("run-1", "run-4", "run-5", "not a run"), root.list()!!.toSet())
    }
}
