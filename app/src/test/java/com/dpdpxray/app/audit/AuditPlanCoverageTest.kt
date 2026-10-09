package com.dpdpxray.app.audit

import com.dpdpxray.app.data.SampleAudit
import com.dpdpxray.app.report.EvidenceReport
import com.dpdpxray.app.ui.AuditStartReadiness
import com.dpdpxray.app.control.Preflight
import com.dpdpxray.core.model.*
import com.dpdpxray.core.report.MarkdownReport
import org.junit.Assert.*
import org.junit.Test

class AuditPlanCoverageTest {
    private val good = SampleAudit.sessions(1000)
    private val missed = good.map { if (it.plan == AuditPlan.REJECT) it.copy(consentEvents = emptyList(), screenAudit = null) else it }

    @Test fun `missing refusal makes C2 explicitly not tested`() {
        assertTrue(AuditPlanCoverage.gaps(missed).single().contains("C2 not tested"))
        assertFalse(AuditPlanCoverage.completedPlans(missed).contains(AuditPlan.REJECT))
    }

    @Test fun `wrong choice does not satisfy a Reject plan`() {
        val wrong = missed.map { if (it.plan == AuditPlan.REJECT) it.copy(consentEvents = listOf(ConsentEvent(1, ConsentKind.ACCEPT))) else it }
        assertTrue(AuditPlanCoverage.gaps(wrong).single().contains("C2 not tested"))
    }

    @Test fun `successful retry establishes choice while keeping the failed session`() {
        val all = missed + good.first { it.plan == AuditPlan.REJECT }.copy(id = "retry")
        assertTrue(AuditPlanCoverage.gaps(all).isEmpty())
        assertTrue(AuditPlanCoverage.completedPlans(all).contains(AuditPlan.REJECT))
    }

    @Test fun `normal complete audit has no incomplete plan warning`() {
        assertTrue(AuditPlanCoverage.gaps(good).isEmpty())
    }

    @Test fun `missing Accept is separately qualified`() {
        val sessions = good.map { if (it.plan == AuditPlan.ACCEPT) it.copy(consentEvents = emptyList()) else it }
        assertTrue(AuditPlanCoverage.gaps(sessions).single().contains("Accept"))
    }

    @Test fun `empty run without reset names already opened app as a possibility`() {
        val empty = good.first { it.plan == AuditPlan.ACCEPT }.copy(netEvents = emptyList(), consentEvents = emptyList(),
            notes = listOf("App data was not cleared (Shizuku not authorised)"))
        val hint = AuditPlanCoverage.freshStateHint(listOf(empty))
        assertNotNull(hint)
        assertTrue(hint!!.contains("already opened"))
        assertTrue(hint.contains("may"))
    }

    @Test fun `zero traffic after a successful reset does not assert prior app use`() {
        val empty = good.first().copy(netEvents = emptyList(), consentEvents = emptyList(), notes = emptyList())
        assertNull(AuditPlanCoverage.freshStateHint(listOf(empty)))
    }

    @Test fun `report header warns before score and removes unmeasured zero byte claim`() {
        val raw = "# TraceVeil report\n\nScore: 51/100\n\n${MarkdownReport.PRIVACY_LINE}\n"
        val rendered = EvidenceReport.annotate(raw, missed)
        assertTrue(rendered.indexOf("C2 not tested") in 0 until rendered.indexOf("Score:"))
        assertFalse(rendered.contains("0 bytes"))
        assertTrue(rendered.contains("DNS"))
    }

    @Test fun `report annotation is not duplicated when exported again`() {
        val once = EvidenceReport.annotate("# Report", missed)
        assertEquals(once, EvidenceReport.annotate(once, missed))
    }

    @Test fun `start readiness gives a reason instead of silently ignoring a tap`() {
        val pf = Preflight(false, false, false, false, false, false, false)
        assertTrue(AuditStartReadiness.problem(pf)!!.contains("VPN"))
        assertTrue(AuditStartReadiness.problem(pf.copy(vpnReady = true))!!.contains("consent watcher"))
        assertNull(AuditStartReadiness.problem(pf.copy(vpnReady = true, accessibilityOn = true)))
    }
}
