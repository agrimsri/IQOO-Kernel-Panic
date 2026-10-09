package com.dpdpxray.core.rules

import com.dpdpxray.core.dns.DnsAnswers
import com.dpdpxray.core.dns.TestPackets
import com.dpdpxray.core.dns.withAnswer
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AliasRulesTest {
    private val s = 1_000_000_000L
    private val engine = RulesEngine(TrackerCatalog.loadDefault().withFirstParty(setOf("shop.test")))

    private fun session(plan: AuditPlan, events: List<NetEvent>, consent: List<ConsentEvent>) = AuditSession(
        id = plan.name, targetPackage = "com.shop.test", targetLabel = "Shop", plan = plan, endedAtNanos = 40 * s,
        netEvents = events, consentEvents = consent,
    )

    private val rejectAt5 = listOf(ConsentEvent(2 * s, ConsentKind.SCREEN_APPEARED), ConsentEvent(5 * s, ConsentKind.REJECT))

    /** A first-party name whose verified reply aliases to Firebase Analytics, built from a literal packet. */
    private fun cloaked(t: Long): NetEvent {
        val query = TestPackets.dnsQuery("metrics.shop.test", 1)
        val reply = TestPackets.reply(query, listOf(TestPackets.cname(null, "region1.app-measurement.com"), TestPackets.a("region1.app-measurement.com", "192.0.2.10")))
        return NetEvent(t, "metrics.shop.test").withAnswer(DnsAnswers.parse(reply, query))
    }

    @Test
    fun `a cloaked tracker after reject gives C2 with alias evidence`() {
        val r = engine.evaluate(listOf(session(AuditPlan.REJECT, listOf(NetEvent(1 * s, "api.shop.test"), cloaked(9 * s)), rejectAt5)))
        val c2 = r.findings.single { it.checkId == CheckId.C2 }
        assertEquals(listOf("Google Analytics for Firebase"), c2.entities)
        val line = c2.evidence.single()
        assertTrue(line, line.startsWith("metrics.shop.test (Google Analytics for Firebase) — alias observed: metrics.shop.test → region1.app-measurement.com"))
    }

    @Test
    fun `a cloaked tracker before consent gives C1 with alias evidence and C10`() {
        val r = engine.evaluate(listOf(session(AuditPlan.REJECT, listOf(cloaked(1 * s), NetEvent(3 * s, "api.shop.test")), rejectAt5)))
        val c1 = r.findings.single { it.checkId == CheckId.C1 }
        assertTrue(c1.evidence.single().contains("alias observed: metrics.shop.test → region1.app-measurement.com"))
        assertTrue(r.findings.any { it.checkId == CheckId.C10 })
    }

    @Test
    fun `the same first-party lookup without an alias stays clean`() {
        val r = engine.evaluate(listOf(session(AuditPlan.REJECT, listOf(NetEvent(1 * s, "api.shop.test"), NetEvent(9 * s, "metrics.shop.test")), rejectAt5)))
        assertTrue(r.findings.isEmpty())
        assertEquals(100, r.score)
    }

    @Test
    fun `direct tracker evidence text is unchanged when it also has an alias`() {
        val direct = NetEvent(9 * s, "graph.facebook.com", cnameChain = listOf("graph.facebook.com", "star.c10r.facebook.com"))
        val c2 = engine.evaluate(listOf(session(AuditPlan.REJECT, listOf(direct, NetEvent(1 * s, "api.shop.test")), rejectAt5)))
            .findings.single { it.checkId == CheckId.C2 }
        assertFalse(c2.evidence.single().contains("alias"))
    }

    @Test
    fun `a malformed reply attributes nothing and leaves the first-party lookup clean`() {
        val query = TestPackets.dnsQuery("metrics.shop.test", 1)
        val reply = TestPackets.reply(query, listOf(TestPackets.cname(null, "region1.app-measurement.com")))
        val event = NetEvent(9 * s, "metrics.shop.test").withAnswer(DnsAnswers.parse(reply.copyOf(reply.size - 3), query))
        assertTrue(event.cnameChain.isEmpty())
        val r = engine.evaluate(listOf(session(AuditPlan.REJECT, listOf(NetEvent(1 * s, "api.shop.test"), event), rejectAt5)))
        assertTrue(r.findings.isEmpty())
        assertNull(r.findings.firstOrNull { it.checkId == CheckId.C2 })
    }
}
