package com.dpdpxray.core.rules

import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.Category
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.report.AuditJson
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A lookup is a catalogue match, not proven tracking, and consent claims stay inside the captured window. Built from the
 * Facebook Quick check recorded on the iQOO 15 (audit-1791603680860), which scored 70/100 on the v1.1.0 build because
 * Meta's own API hosts matched the broad facebook.com entry and the evidence said "no consent was ever requested".
 */
class CatalogueMatchTest {
    private val s = 1_000_000_000L
    private val catalog = TrackerCatalog.loadDefault()
    private val engine = RulesEngine(catalog)

    private val facebookReplay: List<AuditSession> =
        AuditJson.decodeSessions(javaClass.getResource("/replays/facebook-audit-1791603680860.sessions.json")!!.readText())

    private fun fresh(pkg: String, vararg hosts: String) = AuditSession(
        id = "ACCEPT-$pkg", targetPackage = pkg, targetLabel = pkg, plan = AuditPlan.ACCEPT,
        startedAtNanos = 0, endedAtNanos = 30 * s, privateDnsActive = false, freshState = true,
        netEvents = hosts.mapIndexed { i, h -> NetEvent(tNanos = (i + 1) * s / 10, hostname = h) },
    )

    private fun ids(r: AuditResult) = r.findings.map { it.checkId }.toSet()
    private fun allText(r: AuditResult) = r.findings.flatMap { listOf(it.title, it.explanation) + it.evidence }

    @Test
    fun `the recorded Facebook capture is not reported as trackers before consent`() {
        val replay = facebookReplay.single()
        assertEquals("com.facebook.katana", replay.targetPackage)
        assertEquals(listOf("gateway.facebook.com", "b-graph.facebook.com"), replay.netEvents.map { it.hostname })

        val r = engine.evaluate(facebookReplay)
        assertFalse("no C1: ${ids(r)}", CheckId.C1 in ids(r))
        assertFalse("no NC: ${ids(r)}", CheckId.NC in ids(r))
        assertEquals(100, r.score)
        assertTrue(allText(r).none { it.contains("ever requested") })
    }

    @Test
    fun `Meta's general service hosts are a catalogue match, not a tracking endpoint`() {
        for (host in listOf("gateway.facebook.com", "b-graph.facebook.com", "www.facebook.com", "m.facebook.com")) {
            val info = catalog.classify(host)
            assertEquals(host, "Meta", info.entity)
            assertEquals(host, Category.COMPANY_SERVICE, info.category)
            assertFalse(host, info.category.isTracking)
        }
        // The SDK's own endpoints keep their tracking categories.
        assertEquals(Category.ADVERTISING, catalog.classify("graph.facebook.com").category)
        assertEquals(Category.ADVERTISING, catalog.classify("connect.facebook.net").category)
        assertEquals(Category.ADVERTISING, catalog.classify("an.facebook.com").category)
    }

    @Test
    fun `the same hosts from an unrelated freshly reset app raise no consent finding`() {
        val r = engine.evaluate(listOf(fresh("com.example.shop", "gateway.facebook.com", "b-graph.facebook.com", "www.facebook.com")))
        assertFalse(CheckId.C1 in ids(r))
        assertFalse(CheckId.NC in ids(r))
        assertEquals(100, r.score)
    }

    @Test
    fun `an older audit that stored entity Meta as SOCIAL takes the catalogue's current category`() {
        val old = fresh("com.example.shop").copy(
            netEvents = listOf(
                NetEvent(tNanos = s / 10, hostname = "gateway.facebook.com", entity = "Meta", category = Category.SOCIAL),
                NetEvent(tNanos = s / 5, hostname = "b-graph.facebook.com", entity = "Meta", category = Category.SOCIAL),
            ),
        )
        val r = engine.evaluate(listOf(old))
        assertFalse(CheckId.C1 in ids(r))
        assertFalse(CheckId.NC in ids(r))
    }

    @Test
    fun `an alias to a company-service domain never decides a classification`() {
        val info = catalog.classify("edge.example.com", listOf("edge.example.com", "star-mini.c10r.facebook.com"))
        assertNull(info.entity)
        assertNull(info.disguisedVia)
        assertEquals(Category.UNKNOWN, info.category)
    }

    @Test
    fun `SDK endpoints before consent are worded as catalogue matches within the capture window`() {
        val r = engine.evaluate(listOf(fresh("com.example.shop", "graph.facebook.com", "connect.facebook.net")))
        val c1 = r.findings.single { it.checkId == CheckId.C1 }
        assertEquals("Tracker hosts looked up before consent", c1.title)
        assertTrue(c1.explanation, c1.explanation.contains("catalogue match"))
        assertTrue(c1.explanation, c1.explanation.contains("not proof that personal data was sent"))
        assertEquals(
            "graph.facebook.com (Meta (Facebook SDK)) — catalogue match: advertising — looked up 0.1 s after launch, " +
                "no consent screen seen in this capture (30.0 s) [Plan A · Accept]",
            c1.evidence.first(),
        )

        val nc = r.findings.single { it.checkId == CheckId.NC }
        assertEquals("No consent screen seen during the capture", nc.title)
        assertEquals(listOf("No consent screen seen in Plan A · Accept during the 30.0 s captured."), nc.evidence)
        assertTrue(nc.explanation, nc.explanation.contains("covers only the captured window"))
        assertTrue(allText(r).none { it.contains("ever requested") || it.contains("No consent request or privacy notice was detected") })
    }
}
