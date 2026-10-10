package com.dpdpxray.app.ui

import com.dpdpxray.app.data.SampleAudit
import com.dpdpxray.core.dpdp.DpdpCorpus
import com.dpdpxray.core.model.*
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class EvidenceUiTest {
    private val catalog = TrackerCatalog.loadDefault().withFirstParty(setOf("shop.test"))
    private val ui = EvidenceUi(catalog, DpdpCorpus.loadDefault(), ZoneId.of("Asia/Kolkata"))
    private val alias = NetEvent(9_000_000_000, "metrics.shop.test", cnameChain = listOf("metrics.shop.test", "graph.facebook.com"))
    private val sessions = SampleAudit.sessions(1_791_570_081_266)
    private val result = RulesEngine(catalog).evaluate(sessions)

    @Test fun `first party queried name does not hide an observed tracker alias`() {
        val e = ui.classify(alias)
        assertEquals("Meta (Facebook SDK)", e.entity)
        assertEquals(Category.ADVERTISING, e.category)
        assertEquals("metrics.shop.test → graph.facebook.com (alias observed)", ui.aliasLabel(e))
    }

    @Test fun `ordinary host does not receive an alias tag`() {
        assertNull(ui.aliasLabel(ui.classify(NetEvent(1, "graph.facebook.com"))))
    }

    @Test fun `saved timeline retains the alias and assigns after refusal phase`() {
        val s = sessions.first { it.plan == AuditPlan.REJECT }.copy(netEvents = listOf(alias))
        val event = ui.timeline(s).single()
        assertEquals(Phase.AFTER_REJECT, event.phase)
        assertEquals("graph.facebook.com", event.disguisedVia)
    }

    @Test fun `finding detail only shows matching plan and tracker aliases`() {
        val s = sessions.first { it.plan == AuditPlan.REJECT }.copy(netEvents = listOf(alias))
        val f = result.findings.first { it.checkId == CheckId.C2 }
        assertEquals(listOf("metrics.shop.test → graph.facebook.com (alias observed)"), ui.aliasesFor(f, listOf(s)))
        assertTrue(ui.aliasesFor(f, listOf(s.copy(plan = AuditPlan.ACCEPT))).isEmpty())
    }

    @Test fun `developer notice includes actual Reject and post choice host`() {
        val n = ui.notice(CheckId.C2, result, sessions)
        assertNotNull(n)
        assertTrue(n!!.text.contains("graph.facebook.com (Meta (Facebook SDK)): 1.1 s after I declined"))
        assertTrue(n.text.contains("not a legal conclusion"))
        assertEquals(listOf("s6(4)", "s6(6)", "s6(10)", "r3(c)"), n.citedIds)
    }

    @Test fun `timeline labels the audited app's own company servers as first party`() {
        val instagram = sessions.first { it.plan == AuditPlan.ACCEPT }
            .copy(targetPackage = "com.instagram.android", netEvents = listOf(NetEvent(1_000_000_000, "graph.facebook.com")))
        assertEquals(Category.FIRST_PARTY_SAME_COMPANY, ui.timeline(instagram).single().category)
    }

    @Test fun `timeline keeps the Facebook SDK a tracker inside another publisher's app`() {
        val shop = sessions.first { it.plan == AuditPlan.ACCEPT }.copy(netEvents = listOf(NetEvent(1_000_000_000, "graph.facebook.com")))
        assertEquals(Category.ADVERTISING, ui.timeline(shop).single().category)
    }

    @Test fun `notice action is absent on any other finding`() {
        assertNull(ui.notice(CheckId.C1, result, sessions))
    }

    @Test fun `notice action is absent without a C2 finding`() {
        assertNull(ui.notice(CheckId.C2, result.copy(findings = result.findings.filterNot { it.checkId == CheckId.C2 }), sessions))
    }
}
