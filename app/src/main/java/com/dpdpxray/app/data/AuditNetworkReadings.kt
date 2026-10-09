package com.dpdpxray.app.data

import com.dpdpxray.app.netproof.NetworkProof
import com.dpdpxray.app.netproof.NetworkProofCalculator
import com.dpdpxray.app.netproof.SessionCounters
import com.dpdpxray.app.netproof.UidTx
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.report.AuditJson
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class SessionNetworkReadings(
    val sessionId: String,
    val captureStartedNanos: Long?,
    val captureEndedNanos: Long?,
    val txBytesStart: Long?,
    val txBytesEnd: Long?,
    val txPacketsStart: Long?,
    val txPacketsEnd: Long?,
    val txSamplingStartedNanos: Long? = null,
    val snapshotRequestedNanos: Long? = null,
    val txSamplingEndedNanos: Long? = null,
)

@Serializable
private data class NetworkReadingsDocument(val schemaVersion: Int = 1, val readings: List<SessionNetworkReadings>)

/** Local per-plan readings, paired by session and snapshot window; never substitute today's counters for an old audit. */
object AuditNetworkReadings {
    const val FILE_NAME = "network-readings.json"
    fun save(dir: File, readings: List<SessionNetworkReadings>) {
        File(dir, FILE_NAME).writeText(AuditJson.json.encodeToString(NetworkReadingsDocument.serializer(), NetworkReadingsDocument(readings = readings)))
    }

    fun load(dir: File): List<SessionNetworkReadings> = runCatching {
        val doc = AuditJson.json.decodeFromString(NetworkReadingsDocument.serializer(), File(dir, FILE_NAME).readText())
        if (doc.schemaVersion == 1) doc.readings else emptyList()
    }.getOrDefault(emptyList())

    fun proof(sessions: List<AuditSession>, readings: List<SessionNetworkReadings>): NetworkProof {
        fun unavailable(reason: String) = NetworkProof.Unavailable(reason)
        if (sessions.isEmpty()) return unavailable("No capture sessions were recorded")
        if (readings.size != sessions.size || readings.map { it.sessionId }.distinct().size != readings.size ||
            sessions.map { it.id }.distinct().size != sessions.size) {
            return unavailable("A unique upload reading was not recorded for every plan")
        }
        val byId = readings.associateBy { it.sessionId }
        val counters = sessions.map { session ->
            val health = session.captureHealth ?: return unavailable("Capture health was not recorded for this audit")
            val reading = byId[session.id] ?: return unavailable("Upload readings do not match this audit's plans")
            val start = health.captureStartedNanos
            val end = health.captureEndedNanos
            if (start == null || end == null || start < 0 || end < start ||
                start != reading.captureStartedNanos || end != reading.captureEndedNanos) {
                return unavailable("Upload readings do not match the plan's capture window")
            }
            val txStart = reading.txSamplingStartedNanos
            val requested = reading.snapshotRequestedNanos
            val txEnd = reading.txSamplingEndedNanos
            if (txStart == null || requested == null || txEnd == null || txStart < 0 ||
                txStart > start || requested < start || requested > end || txEnd < end || health.notes.isNotEmpty()) {
                return unavailable("Capture was interrupted or its upload sampling interval was not recorded")
            }
            SessionCounters.from(health,
                UidTx(reading.txBytesStart, reading.txPacketsStart?.takeIf { it >= 0 }),
                UidTx(reading.txBytesEnd, reading.txPacketsEnd?.takeIf { it >= 0 }),
            )
        }
        val windows = readings.sortedBy { it.txSamplingStartedNanos }
        if (windows.zipWithNext().any { (a, b) -> a.txSamplingEndedNanos!! > b.txSamplingStartedNanos!! }) {
            return unavailable("Plan windows overlap; upload bytes cannot be counted twice")
        }
        return NetworkProofCalculator.compute(counters)
    }
}
