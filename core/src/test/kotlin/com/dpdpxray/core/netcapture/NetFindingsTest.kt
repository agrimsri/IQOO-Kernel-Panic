package com.dpdpxray.core.netcapture

import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.Category
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.Phase
import com.dpdpxray.core.trackers.TrackerCatalog
import com.dpdpxray.core.trackers.TrackerEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetFindingsTest {
    private val catalog = TrackerCatalog(
        listOf(
            TrackerEntry("AdCo", Category.ADVERTISING, listOf("ads.tracker.example")),
            TrackerEntry("Metrix", Category.ANALYTICS, listOf("collect.metrix.example")),
            TrackerEntry("PicCDN", Category.CONTENT_DELIVERY, listOf("img.cdn.example")),
        ),
    )
    private val s = 1_000_000_000L
    private val choice = 10 * s

    private fun flow(
        id: Long, host: String, phase: Phase, up: Long = 0, down: Long = 0, tls: TlsStatus = TlsStatus.SNI_ONLY, start: Long = choice + s,
        http: List<HttpExchange> = emptyList(), port: Int = 443, ip: String = "203.0.113.$id", noLookup: Boolean = false, proto: FlowProtocol = FlowProtocol.TCP,
    ) = FlowRecord(id, proto, ip, port, start, start + s, up, down, sni = host, tls = tls, http = http, phase = phase, noLookup = noLookup)

    private fun session(flows: List<FlowRecord>, depth: String? = "full-relay", reject: Boolean = true) = AuditSession(
        id = "t", targetPackage = "com.demo", targetLabel = "Demo", plan = AuditPlan.REJECT, startedAtNanos = 0, endedAtNanos = 60 * s,
        consentEvents = listOf(
            ConsentEvent(5 * s, ConsentKind.SCREEN_APPEARED),
            ConsentEvent(choice, if (reject) ConsentKind.REJECT else ConsentKind.ACCEPT),
        ),
        flows = flows, captureDepth = depth,
    )

    @Test
    fun `encrypted tracker traffic after reject is high risk with medium confidence`() {
        val a = NetFindings.assess(session(listOf(flow(1, "ads.tracker.example", Phase.AFTER_REJECT, up = 2400, down = 300))), catalog)
        val f = a.findings.single { it.kind == NetFindingKind.TRACKING_AFTER_REJECT }
        assertEquals(RiskLevel.HIGH, a.level); assertEquals(NetConfidence.MEDIUM, f.confidence)
        assertTrue(f.evidence.any { "after Reject" in it }); assertTrue("not readable" in f.limitation)
        assertFalse(f.whatHappened.contains("personal data"))
    }

    @Test
    fun `an observed post after reject raises confidence to high`() {
        val post = HttpExchange(choice + 2 * s, "POST", "ads.tracker.example", "/collect", phase = Phase.AFTER_REJECT)
        val f = NetFindings.assess(session(listOf(flow(1, "ads.tracker.example", Phase.AFTER_REJECT, up = 900, tls = TlsStatus.CLEARTEXT, http = listOf(post), port = 80))), catalog)
            .findings.single { it.kind == NetFindingKind.TRACKING_AFTER_REJECT }
        assertEquals(NetConfidence.HIGH, f.confidence); assertTrue(f.evidence.any { it.startsWith("POST /collect") })
    }

    @Test
    fun `first party and content-delivery traffic after reject raises nothing`() {
        val a = NetFindings.assess(
            session(listOf(flow(1, "api.demo.example", Phase.AFTER_REJECT, up = 800, down = 9000), flow(2, "img.cdn.example", Phase.AFTER_REJECT, up = 300, down = 90000))),
            catalog.withFirstParty(setOf("demo.example")),
        )
        assertEquals(RiskLevel.CLEAN, a.level); assertTrue(a.findings.isEmpty())
    }

    @Test
    fun `tracker contact with no bytes is only medium`() {
        val a = NetFindings.assess(session(listOf(flow(1, "collect.metrix.example", Phase.AFTER_REJECT, up = 0))), catalog)
        assertEquals(RiskLevel.MEDIUM, a.level); assertEquals(NetConfidence.LOW, a.findings.single().confidence)
    }

    @Test
    fun `tracker traffic before the choice is its own finding, not an after-reject one`() {
        val a = NetFindings.assess(session(listOf(flow(1, "ads.tracker.example", Phase.BEFORE_CONSENT, up = 500, start = 2 * s))), catalog)
        assertEquals(listOf(NetFindingKind.TRACKING_BEFORE_CHOICE), a.findings.map { it.kind })
    }

    @Test
    fun `sensitive values in a cleartext url are flagged without keeping the value`() {
        val h = HttpExchange(choice + s, "GET", "stats.unknown.example", "/p?aid=[device-id]&mail=[email]", sensitive = setOf(SensitiveKind.DEVICE_ID, SensitiveKind.EMAIL), phase = Phase.AFTER_REJECT)
        val f = NetFindings.assess(session(listOf(flow(1, "stats.unknown.example", Phase.AFTER_REJECT, up = 400, tls = TlsStatus.CLEARTEXT, http = listOf(h), port = 80))), catalog)
            .findings.single { it.kind == NetFindingKind.SENSITIVE_IN_REQUEST }
        assertEquals(RiskLevel.HIGH, f.severity); assertTrue("email address" in f.whatHappened)
        assertFalse(f.evidence.joinToString().contains("@"))
    }

    @Test
    fun `dns-only audits report unknown, never clean`() {
        val a = NetFindings.assess(session(emptyList(), depth = "dns-only"), catalog)
        assertEquals(RiskLevel.UNKNOWN, a.level); assertTrue(a.limitations.any { "DNS-only" in it })
    }

    @Test
    fun `encrypted dns resolver is flagged as bypassing observable dns`() {
        val a = NetFindings.assess(session(listOf(flow(1, "cloudflare-dns.com", Phase.AFTER_REJECT, up = 300, down = 500, ip = "1.1.1.1"))), catalog)
        assertTrue(a.findings.any { it.kind == NetFindingKind.ENCRYPTED_DNS_BYPASS })
    }

    @Test
    fun `small unknown traffic is not flagged but a large one-way upload is, at low confidence`() {
        val small = NetFindings.assess(session(listOf(flow(1, "backend.unknown.example", Phase.AFTER_ACCEPT, up = 1200, down = 800))), catalog, )
        assertTrue(small.findings.isEmpty())
        val big = NetFindings.assess(session(listOf(flow(1, "backend.unknown.example", Phase.AFTER_REJECT, up = 90_000, down = 500))), catalog)
        val f = big.findings.single(); assertEquals(NetFindingKind.UNKNOWN_DESTINATION_UPLOAD, f.kind); assertEquals(NetConfidence.LOW, f.confidence)
    }

    @Test
    fun `tracker volume that does not fall after reject is called out as an inference`() {
        val a = NetFindings.assess(
            session(listOf(flow(1, "ads.tracker.example", Phase.BEFORE_CONSENT, up = 1000, start = 2 * s), flow(2, "collect.metrix.example", Phase.AFTER_REJECT, up = 900))), catalog,
        )
        assertTrue(a.findings.any { it.kind == NetFindingKind.NO_DROP_AFTER_REJECT })
    }

    @Test
    fun `findings are ranked most severe first`() {
        val a = NetFindings.assess(
            session(listOf(flow(1, "backend.unknown.example", Phase.AFTER_REJECT, up = 90_000, down = 100), flow(2, "ads.tracker.example", Phase.AFTER_REJECT, up = 800))), catalog,
        )
        assertEquals(RiskLevel.HIGH, a.findings.first().severity)
    }
}

class NetModelBriefTest {
    private fun f(id: String, kind: NetFindingKind, sev: RiskLevel, conf: NetConfidence = NetConfidence.MEDIUM, up: Long = 100) = NetFinding(
        id, kind, sev, "t", id, null, Category.UNKNOWN, Phase.AFTER_REJECT, 0, "what", "rule why", listOf("ev"), conf, "lim", NetAction.INVESTIGATE, up,
    )

    @Test
    fun `prompt carries only redacted candidate summaries`() {
        val x = f("TRACKING_AFTER_REJECT:ads.example", NetFindingKind.TRACKING_AFTER_REJECT, RiskLevel.HIGH).copy(evidence = listOf("GET /p?m=a@b.org tok abcdefghijklmnopqrstuvwxyz0123456789ZZ"))
        val p = NetModelBrief.prompt(listOf(x), listOf("IPv4 only"))
        assertFalse(p.contains("a@b.org")); assertFalse(p.contains("abcdefghijklmnopqrstuvwxyz0123456789ZZ")); assertTrue("IPv4 only" in p)
    }

    @Test
    fun `model can reword and rank but not invent or remove proven findings`() {
        val a = f("A", NetFindingKind.TRACKING_AFTER_REJECT, RiskLevel.HIGH)
        val b = f("B", NetFindingKind.UNKNOWN_DESTINATION_UPLOAD, RiskLevel.LOW)
        val reply = """noise {"findings":[{"id":"A","verdict":"expected","priority":1,"why":"This advertising server was contacted after you said no."},
            {"id":"B","verdict":"expected","priority":1,"why":"Probably the app's own server."},{"id":"ZZZ","verdict":"suspicious","priority":5,"why":"invented finding here"}]} tail"""
        val out = NetModelBrief.apply(listOf(a, b), reply)
        assertEquals(listOf("A"), out.map { it.id })               // B demoted away; A kept despite "expected"
        assertEquals("This advertising server was contacted after you said no.", out.single().whyItMatters)
        assertEquals(FindingSource.MODEL_ASSISTED, out.single().source); assertEquals(a.confidence, out.single().confidence)
    }

    @Test
    fun `overclaiming or malformed replies leave the rule text alone`() {
        val a = f("A", NetFindingKind.TRACKING_AFTER_REJECT, RiskLevel.HIGH)
        val bad = NetModelBrief.apply(listOf(a), """{"findings":[{"id":"A","why":"The app stole your personal data illegally."}]}""")
        assertEquals("rule why", bad.single().whyItMatters); assertEquals(FindingSource.RULE, bad.single().source)
        assertEquals(listOf(a), NetModelBrief.apply(listOf(a), "not json at all"))
        assertEquals(listOf(a), NetModelBrief.apply(listOf(a), null))
    }

    @Test
    fun `priority orders findings of equal severity`() {
        val a = f("A", NetFindingKind.CLEARTEXT_TO_TRACKER, RiskLevel.MEDIUM); val b = f("B", NetFindingKind.CLEARTEXT_TO_TRACKER, RiskLevel.MEDIUM)
        val out = NetModelBrief.apply(listOf(a, b), """{"findings":[{"id":"A","priority":1},{"id":"B","priority":5}]}""")
        assertEquals(listOf("B", "A"), out.map { it.id })
    }
}
