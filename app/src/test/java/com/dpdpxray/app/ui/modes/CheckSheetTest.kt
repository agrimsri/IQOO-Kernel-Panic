package com.dpdpxray.app.ui.modes

import com.dpdpxray.app.data.Audience
import com.dpdpxray.core.model.AuditPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckSheetTest {
    private val bools = listOf(false, true)

    @Test fun `developer sheet keeps today's defaults`() {
        // Controlled demo with Shizuku ready: Full, reset approved, the agent taps.
        assertEquals(CheckSheetSetup(CheckChoice.FULL, allowReset = true, agentTaps = true),
            CheckSheet.defaults(Audience.DEVELOPER, controlledDemo = true, shizukuReady = true, preferPreview = true))
        // Controlled demo without Shizuku: single run (Preview only when preferred).
        assertEquals(CheckSheetSetup(CheckChoice.QUICK, allowReset = true, agentTaps = true),
            CheckSheet.defaults(Audience.DEVELOPER, controlledDemo = true, shizukuReady = false, preferPreview = false))
        assertEquals(CheckSheetSetup(CheckChoice.PREVIEW, allowReset = true, agentTaps = true),
            CheckSheet.defaults(Audience.DEVELOPER, controlledDemo = true, shizukuReady = false, preferPreview = true))
        // Any other app: Quick, no clearing, the person taps.
        assertEquals(CheckSheetSetup(CheckChoice.QUICK, allowReset = false, agentTaps = false),
            CheckSheet.defaults(Audience.DEVELOPER, controlledDemo = false, shizukuReady = true, preferPreview = false))
        assertEquals(CheckSheetSetup(CheckChoice.PREVIEW, allowReset = false, agentTaps = false),
            CheckSheet.defaults(Audience.DEVELOPER, controlledDemo = false, shizukuReady = true, preferPreview = true))
    }

    @Test fun `everyday sheet always opens on Quick with clearing off`() {
        for (demo in bools) for (shizuku in bools) for (preview in bools) {
            val d = CheckSheet.defaults(Audience.USER, demo, shizuku, preview)
            assertEquals("demo=$demo shizuku=$shizuku preview=$preview", CheckChoice.QUICK, d.choice)
            assertFalse(d.allowReset)
            assertEquals(demo, d.agentTaps)
        }
    }

    @Test fun `everyday Quick says No, developer Quick keeps the Accept run`() {
        assertEquals(listOf(AuditPlan.REJECT), CheckSheet.plans(Audience.USER, CheckChoice.QUICK, freshReset = false))
        assertEquals(listOf(AuditPlan.REJECT), CheckSheet.plans(Audience.USER, CheckChoice.QUICK, freshReset = true))
        assertEquals(listOf(AuditPlan.ACCEPT), CheckSheet.plans(Audience.DEVELOPER, CheckChoice.QUICK, freshReset = false))
        assertEquals(listOf(AuditPlan.ACCEPT), CheckSheet.plans(Audience.DEVELOPER, CheckChoice.PREVIEW, freshReset = true))
    }

    @Test fun `Full runs three times only after an approved fresh start, in both modes`() {
        Audience.entries.forEach { a ->
            assertEquals(listOf(AuditPlan.SILENT, AuditPlan.REJECT, AuditPlan.ACCEPT), CheckSheet.plans(a, CheckChoice.FULL, freshReset = true))
            assertEquals(1, CheckSheet.plans(a, CheckChoice.FULL, freshReset = false).size)
        }
    }

    @Test fun `everyday sheet offers Quick and Full only`() {
        assertEquals(listOf(CheckChoice.QUICK, CheckChoice.FULL), CheckSheet.offered(Audience.USER))
        assertEquals(listOf(CheckChoice.FULL, CheckChoice.QUICK, CheckChoice.PREVIEW), CheckSheet.offered(Audience.DEVELOPER))
    }

    @Test fun `every offered choice builds a valid check and clears data only with approval and Shizuku`() {
        Audience.entries.forEach { a ->
            CheckSheet.offered(a).forEach { choice ->
                for (reset in bools) for (shizuku in bools) for (agent in bools) {
                    val c = CheckSheet.config(a, "com.example.shop", "Shop", choice, reset, shizuku, agent, childFacing = true, includeGms = false)
                    assertEquals(reset && shizuku, c.resetAppData)
                    assertEquals(choice == CheckChoice.PREVIEW, c.fastPreview)
                    assertEquals(agent, c.autoDrive)
                    assertTrue(c.childFacing)
                    assertEquals(CheckSheet.plans(a, choice, reset && shizuku), c.plans)
                }
            }
        }
    }
}
