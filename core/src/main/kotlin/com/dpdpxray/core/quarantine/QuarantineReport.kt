package com.dpdpxray.core.quarantine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class QuarantineStopReason(val label: String) {
    TIMER("The planned time ended"),
    USER("Stopped by you"),
    VPN_REVOKED("Android or another VPN turned the quarantine off"),
    TUNNEL_CLOSED("The quarantine tunnel closed unexpectedly"),
    FAILED("The run could not continue"),
}

@Serializable
enum class TimelineKind { APP_ON_SCREEN, LOOKUP, BLOCKED, PERMISSION_DIALOG, SETTINGS_SCREEN, CONNECTION }

/** One line of the behaviour timeline; [atMs] is measured from the start of the run. */
@Serializable
data class TimelineEntry(val atMs: Long, val kind: TimelineKind, val text: String, val evidence: QuarantineEvidence)

/** A saved quarantine run: what one app tried to do while its internet was cut off. Not a verdict on the app. */
@Serializable
data class QuarantineReport(
    val schemaVersion: Int = SCHEMA_VERSION,
    val id: String,
    val targetPackage: String,
    val targetLabel: String,
    val startedAtEpochMs: Long,
    val startedAtNanos: Long,
    val endedAtNanos: Long,
    val plannedDurationMs: Long,
    val stopReason: QuarantineStopReason,
    val resolverAddresses: List<String>,
    val observations: QuarantineObservations,
    val summary: QuarantineSummary,
    val timeline: List<TimelineEntry>,
    val limits: List<String>,
    val notes: List<String> = emptyList(),
    val deviceModel: String? = null,
    val androidVersion: String? = null,
    val privateDnsActive: Boolean? = null,
) {
    /** How long the app was actually quarantined. */
    val observedMs: Long get() = ((endedAtNanos - startedAtNanos) / 1_000_000).coerceAtLeast(0)

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

object QuarantineReports {
    /** Shown with every live run and saved in every report. */
    val LIMITS: List<String> = listOf(
        "Not a malware verdict and not a full sandbox: it records what this app tried to reach during this run only.",
        "Only this app's own internet is cut off. SMS, phone calls, and requests it hands to other apps or system " +
            "services (for example Android's download manager or push messaging) are neither blocked nor seen.",
        "After quarantine ends the app has its normal internet again and may then send what it could not send now.",
        "Apps that wait longer than this run, need a login, or wait for a remote command may show little or nothing.",
        "Encrypted DNS inside the app (DNS over HTTPS) shows as a connection to the resolver (its name read from the TLS " +
            "hello), never as the names looked up through it.",
        "To see which server a connection was for, TraceVeil answers the app's connections on the phone itself and closes " +
            "them right after the first message; nothing is forwarded. HTTPS contents are never readable (no certificate is " +
            "installed): only the server name in the TLS or QUIC hello, and for plain HTTP the request line and a few headers, " +
            "with every value replaced by its kind. Request bodies are not read.",
        "IPv6 connections, and connections to the DNS ports 53 and 853, are recorded only by address, port and protocol.",
        "Every lookup is answered \"does not exist\", so the app may give up early or try other addresses. " +
            "Attempts are not proof that any data would have been sent.",
        "If the app was already open before the run, it may reuse addresses or connections from before.",
        "Packet counts include Android's automatic retries; they are not separate decisions by the app.",
        "Permission dialogs and settings screens are recognised from Android's own screens and English titles; " +
            "other languages or some phone brands' screens may be missed. A settings screen opening does not show " +
            "whether the app or you opened it.",
    )

    val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    fun build(
        id: String,
        targetPackage: String,
        targetLabel: String,
        startedAtEpochMs: Long,
        startedAtNanos: Long,
        endedAtNanos: Long,
        plannedDurationMs: Long,
        stopReason: QuarantineStopReason,
        resolverAddresses: Set<String>,
        observations: QuarantineObservations,
        notes: List<String> = emptyList(),
        deviceModel: String? = null,
        androidVersion: String? = null,
        privateDnsActive: Boolean? = null,
    ) = QuarantineReport(
        id = id,
        targetPackage = targetPackage,
        targetLabel = targetLabel,
        startedAtEpochMs = startedAtEpochMs,
        startedAtNanos = startedAtNanos,
        endedAtNanos = endedAtNanos,
        plannedDurationMs = plannedDurationMs,
        stopReason = stopReason,
        resolverAddresses = resolverAddresses.sorted(),
        observations = observations,
        summary = QuarantineSummary.of(observations, resolverAddresses),
        timeline = QuarantineTimeline.build(observations, startedAtNanos),
        limits = LIMITS,
        notes = notes,
        deviceModel = deviceModel,
        androidVersion = androidVersion,
        privateDnsActive = privateDnsActive,
    )

    fun encode(report: QuarantineReport): String = json.encodeToString(QuarantineReport.serializer(), report)

    fun decode(text: String): QuarantineReport = json.decodeFromString(QuarantineReport.serializer(), text)
}

/** Builds the behaviour timeline: the first time of each distinct thing the run saw, in time order. */
object QuarantineTimeline {
    fun build(o: QuarantineObservations, startNanos: Long): List<TimelineEntry> {
        fun at(t: Long) = ((t - startNanos) / 1_000_000).coerceAtLeast(0)
        fun times(n: Long, unit: String) = if (n > 1) " · $n $unit" else ""
        val entries = mutableListOf<Pair<Long, TimelineEntry>>()
        o.targetOnScreenNanos?.let {
            entries += it to TimelineEntry(at(it), TimelineKind.APP_ON_SCREEN, "The app's window appeared", QuarantineEvidence.OBSERVED)
        }
        o.domains.forEach { d ->
            entries += d.firstNanos to TimelineEntry(
                at(d.firstNanos), TimelineKind.LOOKUP,
                "Tried to look up ${d.hostname} (answered: does not exist)${times(d.count, "lookups")}", QuarantineEvidence.OBSERVED,
            )
        }
        o.blocked.forEach { b ->
            val who = if (b.role == DestinationRole.PHONE_RESOLVER) " (the phone's own resolver)" else ""
            entries += b.firstNanos to TimelineEntry(
                at(b.firstNanos), TimelineKind.BLOCKED,
                "Blocked ${b.protocol.label} to ${b.endpoint}$who${times(b.packets, "packets")}", QuarantineEvidence.OBSERVED,
            )
        }
        o.permissionDialogs.forEach { p ->
            val whose = if (p.namesTarget) "" else " (does not name this app)"
            entries += p.firstNanos to TimelineEntry(
                at(p.firstNanos), TimelineKind.PERMISSION_DIALOG,
                "Permission dialog$whose: \"${p.text}\"${times(p.timesShown.toLong(), "times")}", QuarantineEvidence.OBSERVED,
            )
        }
        o.connections.forEach { f ->
            val text = QuarantineConnections.timelineText(f) ?: return@forEach
            val t = f.http.firstOrNull()?.tNanos?.takeIf { it > 0 } ?: f.startNanos
            entries += t to TimelineEntry(at(t), TimelineKind.CONNECTION, text, QuarantineEvidence.OBSERVED)
        }
        o.settingsScreens.forEach { s ->
            entries += s.firstNanos to TimelineEntry(
                at(s.firstNanos), TimelineKind.SETTINGS_SCREEN,
                "${s.kind.title} screen opened${times(s.timesShown.toLong(), "times")}", QuarantineEvidence.OBSERVED,
            )
        }
        return entries.sortedBy { it.first }.map { it.second }
    }
}
