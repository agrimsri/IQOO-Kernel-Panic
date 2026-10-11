package com.dpdpxray.app.ai

import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.netcapture.NetAssessment
import com.dpdpxray.core.netcapture.NetFindings
import com.dpdpxray.core.netcapture.NetModelBrief
import com.dpdpxray.core.netcapture.PhaseTraffic
import com.dpdpxray.core.netcapture.RiskLevel
import com.dpdpxray.core.trackers.TrackerCatalog
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Turns a saved audit's connections into the few findings worth showing. Rules decide what is a finding and how sure
 * we are; the on-device model (when loaded) may only reword the explanation and rank the candidates. Nothing leaves the
 * phone: there is no remote model path.
 */
object NetIntelligence {
    private const val MODEL_TIMEOUT_MS = 25_000L

    /** Instant, deterministic result from every plan that ran the full-relay capture. */
    fun rules(sessions: List<AuditSession>, catalog: TrackerCatalog): NetAssessment {
        val parts = sessions.filter { it.captureDepth == "full-relay" }.map { NetFindings.assess(it, catalog) }
        if (parts.isEmpty()) return NetAssessment(RiskLevel.UNKNOWN, emptyList(), listOf("No plan in this audit ran the connection-level capture."), emptyList())
        val findings = parts.flatMap { it.findings }
            .sortedWith(compareByDescending<com.dpdpxray.core.netcapture.NetFinding> { it.severity.ordinal }.thenByDescending { it.confidence.ordinal }.thenByDescending { it.bytesUp })
            .distinctBy { it.id }
        val level = when {
            findings.any { it.severity == RiskLevel.HIGH } -> RiskLevel.HIGH
            findings.any { it.severity == RiskLevel.MEDIUM } -> RiskLevel.MEDIUM
            findings.isNotEmpty() -> RiskLevel.LOW
            parts.any { it.level == RiskLevel.CLEAN } -> RiskLevel.CLEAN
            else -> RiskLevel.UNKNOWN
        }
        val traffic = parts.flatMap { it.traffic }.groupBy { it.phase }.map { (p, l) ->
            PhaseTraffic(p, l.sumOf { it.connections }, l.sumOf { it.bytesUp }, l.sumOf { it.bytesDown })
        }
        return NetAssessment(level, findings, parts.flatMap { it.limitations }.distinct(), traffic)
    }

    /** Asks the loaded on-device model to explain and rank; returns [rules] output unchanged if it is absent, slow or odd. */
    suspend fun refine(a: NetAssessment, llm: LlmEngine): NetAssessment {
        if (a.findings.isEmpty() || !llm.isReady) return a
        val reply = withTimeoutOrNull(MODEL_TIMEOUT_MS) { llm.generate(NetModelBrief.SYSTEM, NetModelBrief.prompt(a.findings, a.limitations)) }
        return a.copy(findings = NetModelBrief.apply(a.findings, reply))
    }
}
