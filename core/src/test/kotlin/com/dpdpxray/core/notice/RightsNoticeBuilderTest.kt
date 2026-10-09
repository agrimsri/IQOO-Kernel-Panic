package com.dpdpxray.core.notice

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class RightsNoticeBuilderTest {
    private val S = 1_000_000_000L
    private val catalog = TrackerCatalog.loadDefault()
    private val corpus = DpdpCorpus.loadDefault()
    private val builder = RightsNoticeBuilder(catalog, corpus, ZoneId.of("Asia/Kolkata"))
    private val engine = RulesEngine(catalog)

    private fun net(t: Double, host: String, via: String? = null) = NetEvent(tNanos = (t * S).toLong(), hostname = host, viaPackage = via)

    /**
     * Test-only copy of the Plan B session in app/data/SampleAudit.kt (owned by Nitya), with a fixed start time so the
     * text is reproducible. Clearly sample data, not a device observation.
     */
    private val sampleReject = AuditSession(
        id = "sample-b", targetPackage = "com.leakyshop.demo", targetLabel = "LeakyShop (sample)", plan = AuditPlan.REJECT,
        startedAtNanos = 0, endedAtNanos = 35 * S, startedAtEpochMs = 1_791_570_081_266,
        netEvents = listOf(
            net(0.4, "api.leakyshop.example"), net(0.6, "app-measurement.com"), net(0.8, "graph.facebook.com"),
            net(9.4, "graph.facebook.com"), net(11.2, "app-measurement.com"), net(12.0, "launches.appsflyersdk.com"),
        ),
        consentEvents = listOf(
            ConsentEvent((2.1 * S).toLong(), ConsentKind.SCREEN_APPEARED),
            ConsentEvent((6.0 * S).toLong(), ConsentKind.SETTINGS, "Manage"),
            ConsentEvent((8.3 * S).toLong(), ConsentKind.REJECT, "Reject all"),
        ),
    )
    private val sampleAccept = sampleReject.copy(
        id = "sample-a", plan = AuditPlan.ACCEPT,
        netEvents = listOf(net(0.6, "app-measurement.com"), net(5.6, "googleads.g.doubleclick.net")),
        consentEvents = listOf(ConsentEvent((4.9 * S).toLong(), ConsentKind.ACCEPT, "Accept & Continue")),
    )

    private fun notice(vararg sessions: AuditSession) = builder.build(engine.evaluate(sessions.toList()), sessions.toList())

    @Test
    fun `sample leaky audit produces the golden notice`() {
        val golden = javaClass.getResource("/notice/sample-leaky-notice.txt")!!.readText().replace("\r\n", "\n")
        val n = notice(sampleAccept, sampleReject)
        assertNotNull(n)
        assertEquals(golden, n!!.text)
        assertEquals(listOf("s6(4)", "s6(6)", "s6(10)", "r3(c)"), n.citedIds)
        assertEquals("com.leakyshop.demo", n.packageName)
        val choice = n.choices.single()
        assertEquals(1_791_570_089_566L, choice.rejectEpochMs)
        assertEquals(listOf(1_100L, 2_900L, 3_700L), choice.contacts.map { it.firstDelayNanos / 1_000_000 })
    }

    @Test
    fun `no C2 finding returns null`() {
        assertNull(notice(sampleAccept))
        val fixed = sampleReject.copy(netEvents = listOf(net(0.4, "api.leakyshop.example"), net(9.0, "api.leakyshop.example")))
        assertNull(notice(fixed))
    }

    @Test
    fun `a C2 finding without an observed reject in these sessions returns null`() {
        val result = engine.evaluate(listOf(sampleReject))
        assertTrue(result.findings.any { it.checkId == CheckId.C2 })
        assertNull(builder.build(result, listOf(sampleAccept)))
    }

    @Test
    fun `missing cited passage fails construction`() {
        val withoutR3c = DpdpCorpus(corpus.passages.filterNot { it.id == "r3(c)" })
        val e = assertThrows(IllegalStateException::class.java) { RightsNoticeBuilder(catalog, withoutR3c) }
        assertTrue(e.message!!.contains("r3(c)"))
    }

    @Test
    fun `cites only passages that exist in the corpus`() {
        val n = notice(sampleReject)!!
        n.citedIds.forEach { id ->
            val p = corpus.byId(id)
            assertNotNull("cited id $id is in passages.json", p)
            assertTrue("text names ${p!!.source}", n.text.contains(p.source))
        }
        val sectionMentions = Regex("(Section|Rule) [0-9]+\\([0-9a-z]+\\)").findAll(n.text).map { it.value }.toSet()
        val allowed = n.citedIds.map { corpus.byId(it)!!.source.substringAfter(", ") }.toSet()
        assertEquals(allowed, sectionMentions)
    }

    @Test
    fun `no sentence asserts a violation or a data transfer`() {
        val text = notice(sampleReject)!!.text.lowercase()
        listOf("violat", "illegal", "unlawful", "breach", "no lawful ground", "sent your data", "were sent", "was sent to", "penalt", "crore")
            .forEach { assertFalse("must not contain '$it'", text.contains(it)) }
    }

    @Test
    fun `only trackers after the reject and before any later accept are listed`() {
        val s = sampleReject.copy(
            netEvents = listOf(
                net(8.0, "graph.facebook.com"),            // before the reject
                net(8.3, "app-measurement.com"),           // same instant as the reject: after it, as PhaseAssigner treats it
                net(9.0, "api.leakyshop.example"),         // not a tracker
                net(9.5, "launches.appsflyersdk.com"),
                net(20.0, "googleads.g.doubleclick.net"),  // after the user later accepted
            ),
            consentEvents = sampleReject.consentEvents + ConsentEvent(15 * S, ConsentKind.ACCEPT, "Accept"),
        )
        val contacts = notice(s)!!.choices.single().contacts
        assertEquals(listOf("app-measurement.com", "launches.appsflyersdk.com"), contacts.map { it.hostname })
        assertEquals(listOf(0L, 1_200L), contacts.map { it.firstDelayNanos / 1_000_000 })
    }

    @Test
    fun `an accept before the reject means no declined choice is claimed`() {
        val s = sampleReject.copy(
            consentEvents = listOf(ConsentEvent(5 * S, ConsentKind.ACCEPT, "Accept"), ConsentEvent((8.3 * S).toLong(), ConsentKind.REJECT, "Reject all")),
        )
        assertNull(builder.build(engine.evaluate(listOf(sampleReject)), listOf(s)))
    }

    @Test
    fun `repeat lookups are counted and Play services lookups are attributed`() {
        val s = sampleReject.copy(
            netEvents = listOf(
                net(9.0, "app-measurement.com"), net(10.0, "app-measurement.com"), net(12.5, "app-measurement.com"),
                net(9.9, "graph.facebook.com", via = "com.google.android.gms"),
            ),
        )
        val text = notice(s)!!.text
        assertTrue(text, text.contains("- app-measurement.com (Google Analytics for Firebase): 3 lookups, the first 0.7 s after I declined"))
        assertTrue(text, text.contains("- graph.facebook.com (Meta (Facebook SDK)), looked up by com.google.android.gms: 1.6 s after I declined"))
    }

    @Test
    fun `an inferred reject and an unknown wall clock are stated, not hidden`() {
        val s = sampleReject.copy(
            startedAtEpochMs = 0,
            consentEvents = listOf(ConsentEvent((8.3 * S).toLong(), ConsentKind.REJECT, "(your tap, inferred when the dialog closed)")),
        )
        val n = notice(s)!!
        val choice = n.choices.single()
        assertNull(choice.rejectEpochMs)
        assertTrue(choice.inferred)
        assertTrue(n.text, n.text.contains("I declined consent in LeakyShop (sample) (Plan B · Reject). The date and time of that choice were not recorded."))
        assertTrue(n.text, n.text.contains("TraceVeil inferred the moment of my choice from the consent dialog closing; it did not receive the tap itself."))
        assertFalse(n.text.contains("by choosing"))
    }

    @Test
    fun `an observed alias is mentioned`() {
        val s = sampleReject.copy(
            netEvents = listOf(NetEvent(9 * S, "metrics.leakyshop.example", entity = "AppsFlyer",
                category = com.dpdpxray.core.model.Category.ATTRIBUTION, disguisedVia = "appsflyersdk.com")),
        )
        assertTrue(notice(s)!!.text.contains("- metrics.leakyshop.example (AppsFlyer, alias observed for appsflyersdk.com): 0.7 s after I declined"))
    }
}
