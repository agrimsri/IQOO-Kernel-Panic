package com.dpdpxray.core.rules

import com.dpdpxray.core.dns.DnsAnswers
import com.dpdpxray.core.dns.TestPackets
import com.dpdpxray.core.dns.withAnswer
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.EvidenceType
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.PenaltyBand
import com.dpdpxray.core.model.Severity
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C11 (tracker reached through a CNAME alias) and C12 (tracker lookups in the background window). DNS replies are built
 * from literal packets and parsed by the capture's own reader, like on the phone. Test data, not device replays.
 */
class AliasBackgroundChecksTest {
    private val s = 1_000_000_000L
    private val engine = RulesEngine(TrackerCatalog.loadDefault().withFirstParty(setOf("shop.test")))

    private fun session(
        plan: AuditPlan,
        events: List<NetEvent>,
        consent: List<ConsentEvent>,
        ended: Long = 40 * s,
        background: Long? = null,
        fresh: Boolean? = true,
    ) = AuditSession(
        id = plan.name, targetPackage = "com.shop.test", targetLabel = "Shop", plan = plan, endedAtNanos = ended,
        netEvents = events, consentEvents = consent, backgroundStartedNanos = background, freshState = fresh,
    )

    private val screen = ConsentEvent(2 * s, ConsentKind.SCREEN_APPEARED)
    private val rejectAt5 = listOf(screen, ConsentEvent(5 * s, ConsentKind.REJECT, "Reject all"))
    private val acceptAt5 = listOf(screen, ConsentEvent(5 * s, ConsentKind.ACCEPT, "Accept"))

    /** A reply read by the capture's parser: [host] aliased through [chain] to an address. */
    private fun replied(t: Long, host: String, vararg chain: String): NetEvent {
        val query = TestPackets.dnsQuery(host, 1)
        val records = chain.mapIndexed { i, target -> TestPackets.cname(if (i == 0) null else chain[i - 1], target) } +
            TestPackets.a(chain.lastOrNull() ?: host, "192.0.2.10")
        return NetEvent(t, host).withAnswer(DnsAnswers.parse(TestPackets.reply(query, records), query))
    }

    // --- C11 ---

    @Test
    fun `first-party name aliased to Firebase before consent gives C11 info next to C1 and the score is unchanged`() {
        val events = listOf(replied(1 * s, "metrics.shop.test", "region1.app-measurement.com"), NetEvent(3 * s, "api.shop.test"))
        val r = engine.evaluate(listOf(session(AuditPlan.REJECT, events, rejectAt5)))
        val c11 = r.findings.single { it.checkId == CheckId.C11 }
        assertEquals(Severity.INFO, c11.severity)
        assertEquals(EvidenceType.OBSERVED, c11.evidenceType)
        assertEquals(PenaltyBand.NONE, c11.penaltyBand)
        assertTrue(c11.dpdpRefs.isEmpty())
        assertEquals(listOf("Google Analytics for Firebase"), c11.entities)
        val line = c11.evidence.single()
        assertTrue(line, line.contains("alias observed: metrics.shop.test → region1.app-measurement.com"))
        assertTrue(line, line.contains("4.0 s before the consent choice"))
        assertTrue(r.findings.any { it.checkId == CheckId.C1 })
        // C1 High (15) + C10 inferred Medium (4); C11 adds nothing.
        assertEquals(81, r.score)
    }

    @Test
    fun `aliased tracker after Reject gives C11 next to C2`() {
        val events = listOf(NetEvent(1 * s, "api.shop.test"), replied(9 * s, "events.shop.test", "edge.shop.test", "region1.app-measurement.com"))
        val r = engine.evaluate(listOf(session(AuditPlan.REJECT, events, rejectAt5)))
        val c11 = r.findings.single { it.checkId == CheckId.C11 }
        assertTrue(c11.evidence.single().contains("events.shop.test → edge.shop.test → region1.app-measurement.com"))
        assertTrue(c11.evidence.single().contains("after the user chose reject"))
        assertTrue(r.findings.any { it.checkId == CheckId.C2 })
    }

    @Test
    fun `aliased tracker after Accept is not C11`() {
        val events = listOf(NetEvent(1 * s, "api.shop.test"), replied(9 * s, "metrics.shop.test", "region1.app-measurement.com"))
        assertNull(engine.evaluate(listOf(session(AuditPlan.ACCEPT, events, acceptAt5))).findings.firstOrNull { it.checkId == CheckId.C11 })
    }

    @Test
    fun `a catalogued tracker queried directly is not C11 even when its reply has aliases`() {
        val direct = replied(1 * s, "graph.facebook.com", "star.c10r.facebook.com")
        val r = engine.evaluate(listOf(session(AuditPlan.REJECT, listOf(direct, NetEvent(3 * s, "api.shop.test")), rejectAt5)))
        assertTrue(r.findings.any { it.checkId == CheckId.C1 })
        assertNull(r.findings.firstOrNull { it.checkId == CheckId.C11 })
    }

    @Test
    fun `an alias to a content CDN is infrastructure, not C11`() {
        val cdn = replied(1 * s, "img.shop.test", "scontent.xx.fbcdn.net")
        val r = engine.evaluate(listOf(session(AuditPlan.REJECT, listOf(cdn, NetEvent(3 * s, "api.shop.test")), rejectAt5)))
        assertTrue(r.findings.isEmpty())
    }

    @Test
    fun `without recorded replies the alias is not established and nothing is claimed`() {
        val bare = NetEvent(1 * s, "metrics.shop.test")
        val r = engine.evaluate(listOf(session(AuditPlan.REJECT, listOf(bare, NetEvent(3 * s, "api.shop.test")), rejectAt5)))
        assertTrue(r.findings.isEmpty())
    }

    @Test
    fun `kept data without a consent screen reports the aliased lookup under U1, not C11`() {
        val events = listOf(replied(1 * s, "metrics.shop.test", "region1.app-measurement.com"), NetEvent(3 * s, "api.shop.test"))
        val r = engine.evaluate(listOf(session(AuditPlan.SILENT, events, emptyList(), fresh = false)))
        assertEquals(setOf(CheckId.U1), r.findings.map { it.checkId }.toSet())
    }

    // --- C12 ---

    @Test
    fun `tracker lookups after the app went to the background after Reject give C12 info and C2`() {
        val events = listOf(
            NetEvent(1 * s, "api.shop.test"),
            NetEvent((29.5 * s).toLong(), "api.shop.test"),
            NetEvent(33 * s, "graph.facebook.com"),
        )
        val r = engine.evaluate(listOf(session(AuditPlan.REJECT, events, rejectAt5, ended = 40 * s, background = 30 * s)))
        val c12 = r.findings.single { it.checkId == CheckId.C12 }
        assertEquals(Severity.INFO, c12.severity)
        assertEquals(EvidenceType.OBSERVED, c12.evidenceType)
        assertEquals(listOf("Meta (Facebook SDK)"), c12.entities)
        assertEquals(
            listOf(
                "graph.facebook.com (Meta (Facebook SDK)) — catalogue match: advertising — looked up 33.0 s after launch, " +
                    "3.0 s after the app went to the background, 28.0 s after the user chose reject [Plan B · Reject]",
                "Plan B · Reject: watched 10.0 s in the background",
            ),
            c12.evidence,
        )
        assertTrue(r.findings.any { it.checkId == CheckId.C2 })
        assertEquals(70, r.score) // C2 only
    }

    @Test
    fun `background tracking after Accept is reported but does not change the score`() {
        val events = listOf(NetEvent(1 * s, "api.shop.test"), NetEvent(31 * s, "app-measurement.com"))
        val r = engine.evaluate(listOf(session(AuditPlan.ACCEPT, events, acceptAt5, background = 30 * s)))
        assertEquals(setOf(CheckId.C12), r.findings.map { it.checkId }.toSet())
        assertEquals(100, r.score)
    }

    @Test
    fun `lookups before the confirmed background moment are not C12`() {
        val events = listOf(NetEvent(1 * s, "api.shop.test"), NetEvent((29.9 * s).toLong(), "app-measurement.com"))
        val r = engine.evaluate(listOf(session(AuditPlan.ACCEPT, events, acceptAt5, background = 30 * s)))
        assertNull(r.findings.firstOrNull { it.checkId == CheckId.C12 })
    }

    @Test
    fun `without a recorded background step late lookups are not called background`() {
        val events = listOf(NetEvent(1 * s, "api.shop.test"), NetEvent(31 * s, "app-measurement.com"))
        val r = engine.evaluate(listOf(session(AuditPlan.ACCEPT, events, acceptAt5, background = null)))
        assertNull(r.findings.firstOrNull { it.checkId == CheckId.C12 })
    }

    @Test
    fun `only first-party lookups in the background window give no C12`() {
        val events = listOf(NetEvent(1 * s, "api.shop.test"), NetEvent(31 * s, "api.shop.test"))
        assertTrue(engine.evaluate(listOf(session(AuditPlan.ACCEPT, events, acceptAt5, background = 30 * s))).findings.isEmpty())
    }
}
