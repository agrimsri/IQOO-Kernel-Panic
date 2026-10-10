package com.dpdpxray.core.report

import com.dpdpxray.core.dpdp.CitationService
import com.dpdpxray.core.dpdp.DpdpCorpus
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Markdown report shows the background window and Android permission dialogs only when they were recorded. */
class NewEvidenceReportTest {
    private val s = 1_000_000_000L
    private val citations = CitationService(DpdpCorpus.loadDefault())

    private val base = AuditSession(
        id = "a", targetPackage = "com.shop.app", targetLabel = "ShopNow", plan = AuditPlan.ACCEPT, endedAtNanos = 40 * s,
        netEvents = listOf(NetEvent(1 * s, "api.shop.app"), NetEvent(33 * s, "app-measurement.com")),
        consentEvents = listOf(
            ConsentEvent(1 * s, ConsentKind.PERMISSION_DIALOG, "Allow ShopNow to access this device's location?"),
            ConsentEvent(2 * s, ConsentKind.SCREEN_APPEARED), ConsentEvent(5 * s, ConsentKind.ACCEPT, "Accept"),
        ),
        backgroundStartedNanos = 30 * s, permissionDialogsWatched = true, freshState = true,
    )

    @Test
    fun `background lookups and permission dialogs are marked in the timeline`() {
        val result = RulesEngine(TrackerCatalog.loadDefault()).evaluate(listOf(base))
        assertEquals(setOf(CheckId.C12, CheckId.C14), result.findings.map { it.checkId }.toSet())
        val md = MarkdownReport.build(result, listOf(base), emptyList(), citations)
        assertTrue(md, md.contains("**Plan A · Accept** — 2 lookups, user chose accept at 5.0 s, app in the background from 30.0 s"))
        assertTrue(md, md.contains("- Android permission dialog at 1.0 s: \"Allow ShopNow to access this device's location?\""))
        assertTrue(md, md.contains("| 33.0 | app-measurement.com (app in background) |"))
        assertTrue(md, md.contains("| 1.0 | api.shop.app |"))
        // C12 is info without a legal reference, so no DPDP passage is attached to it; C14 cites s.5(1) only.
        assertTrue(citations.cite(result.findings.single { it.checkId == CheckId.C12 }).isEmpty())
        assertEquals(listOf("s5(1)"), citations.cite(result.findings.single { it.checkId == CheckId.C14 }).map { it.id })
    }

    @Test
    fun `older sessions render exactly as before`() {
        val old = base.copy(backgroundStartedNanos = null, consentEvents = base.consentEvents.filter { it.kind != ConsentKind.PERMISSION_DIALOG })
        val md = MarkdownReport.build(RulesEngine(TrackerCatalog.loadDefault()).evaluate(listOf(old)), listOf(old), emptyList(), citations)
        assertFalse(md.contains("background"))
        assertFalse(md.contains("permission dialog"))
    }
}
