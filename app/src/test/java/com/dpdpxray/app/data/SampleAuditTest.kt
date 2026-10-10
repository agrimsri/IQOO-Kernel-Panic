package com.dpdpxray.app.data

import com.dpdpxray.app.report.SampleReport
import com.dpdpxray.core.audit.PhaseAssigner
import com.dpdpxray.core.dpdp.CitationService
import com.dpdpxray.core.dpdp.DpdpCorpus
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.Phase
import com.dpdpxray.core.report.AuditJson
import com.dpdpxray.core.report.MarkdownReport
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.trackers.TrackerCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleAuditTest {
    private val nowMs = 1_791_552_600_000L

    @Test
    fun `default sample keeps its original plans events and score`() {
        val sessions = SampleAudit.sessions(nowMs)
        assertEquals(listOf("sample-c", "sample-b", "sample-a"), sessions.map { it.id })
        assertEquals(listOf(AuditPlan.SILENT, AuditPlan.REJECT, AuditPlan.ACCEPT), sessions.map { it.plan })
        assertEquals(listOf(6, 6, 6), sessions.map { it.netEvents.size })
        assertTrue(sessions.all { it.targetLabel == "LeakyShop (sample)" && it.startedAtEpochMs == nowMs })
        assertTrue(sessions.flatMap { it.netEvents }.all { it.cnameChain.isEmpty() && it.answerIps.isEmpty() })
        // The in-app sample has additional inferred notice findings; it is not the 21-point live demo fixture.
        assertEquals(8, RulesEngine(TrackerCatalog.loadDefault()).evaluate(sessions).score)
    }

    @Test
    fun `alias sample contains a synthetic chain across all plans`() {
        val sessions = SampleAudit.sessions(nowMs, SampleAudit.Variant.ALIAS)
        val aliases = sessions.flatMap { it.netEvents }.filter { it.hostname == "metrics.leakyshop.example" }
        assertEquals(4, aliases.size)
        assertTrue(aliases.all { it.cnameChain == listOf("metrics.leakyshop.example", "graph.facebook.com") })
        assertTrue(aliases.all { it.answerIps.isEmpty() && it.disguisedVia == null })
        assertEquals(listOf("sample-alias-c", "sample-alias-b", "sample-alias-a"), sessions.map { it.id })
        assertTrue(sessions.all { it.targetLabel.contains("sample") && it.notes.any { n -> n.contains("Synthetic alias") } })
    }

    @Test
    fun `alias sample preserves the reject timing for phase classification`() {
        val reject = SampleAudit.sessions(nowMs, SampleAudit.Variant.ALIAS).single { it.plan == AuditPlan.REJECT }
        assertEquals(8_300_000_000L, reject.consentChoice!!.tNanos)
        val aliases = PhaseAssigner.assign(reject.netEvents, reject.consentEvents)
            .filter { it.hostname == "metrics.leakyshop.example" }
        assertEquals(listOf(800_000_000L, 9_400_000_000L), aliases.map { it.tNanos })
        assertEquals(listOf(Phase.BEFORE_CONSENT, Phase.AFTER_REJECT), aliases.map { it.phase })
    }

    @Test
    fun `endpoint sample contains documentation addresses without inventing unknown endpoints`() {
        val events = SampleAudit.sessions(nowMs, SampleAudit.Variant.ENDPOINTS).flatMap { it.netEvents }
        assertEquals(listOf("192.0.2.10"), events.first { it.hostname == "api.leakyshop.example" }.answerIps)
        assertEquals(listOf("198.51.100.20"), events.first { it.hostname == "app-measurement.com" }.answerIps)
        assertEquals(listOf("203.0.113.30", "2001:db8::30"), events.first { it.hostname == "metrics.leakyshop.example" }.answerIps)
        assertTrue(events.filter { it.hostname == "launches.appsflyersdk.com" }.all { it.answerIps.isEmpty() })
        assertTrue(events.first { it.hostname == "metrics.leakyshop.example" }.cnameChain.isNotEmpty())
    }

    @Test
    fun `fixture serialization retains endpoint and alias evidence`() {
        val sessions = SampleAudit.sessions(nowMs, SampleAudit.Variant.ENDPOINTS)
        val decoded = AuditJson.decodeSessions(AuditJson.encodeSessions(sessions))
        assertEquals(sessions, decoded)
        assertEquals(listOf("sample-endpoints-c", "sample-endpoints-b", "sample-endpoints-a"), decoded.map { it.id })
        val alias = decoded.single { it.plan == AuditPlan.REJECT }.netEvents.single { it.tNanos == 9_400_000_000L }
        assertEquals(listOf("metrics.leakyshop.example", "graph.facebook.com"), alias.cnameChain)
        assertEquals(listOf("203.0.113.30", "2001:db8::30"), alias.answerIps)
        assertTrue(decoded.all { it.notes.any { n -> n.contains("not real server locations") } })
    }

    @Test
    fun `synthetic fixtures never claim measured capture health or change the baseline`() {
        SampleAudit.Variant.entries.forEach { variant ->
            SampleAudit.sessions(nowMs, variant).forEach { assertNull(it.captureHealth) }
        }
        val baseline = SampleAudit.sessions(nowMs)
        assertTrue(baseline.flatMap { it.netEvents }.all { it.cnameChain.isEmpty() && it.answerIps.isEmpty() && it.disguisedVia == null })
        assertTrue(baseline.all { it.notes.size == 1 && it.targetLabel == "LeakyShop (sample)" })
    }

    @Test
    fun `fixture report retains the synthetic evidence warning`() {
        val sessions = SampleAudit.sessions(nowMs, SampleAudit.Variant.ENDPOINTS)
        val result = RulesEngine(TrackerCatalog.loadDefault()).evaluate(sessions)
        val markdown = SampleReport.annotate(
            MarkdownReport.build(result, sessions, emptyList(), CitationService(DpdpCorpus.loadDefault())), sessions,
        )
        assertTrue(markdown.contains("LeakyShop (alias + IP sample)"))
        assertTrue(markdown.contains("Synthetic alias"))
        assertTrue(markdown.contains("not real server locations"))
    }
}
