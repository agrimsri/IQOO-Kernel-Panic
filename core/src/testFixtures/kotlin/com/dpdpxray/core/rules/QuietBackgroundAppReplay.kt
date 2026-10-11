package com.dpdpxray.core.rules

import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.BackgroundWatch
import com.dpdpxray.core.model.BlockedStep
import com.dpdpxray.core.model.CaptureHealth
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.PlanConditions
import com.dpdpxray.core.model.RestartChoice
import com.dpdpxray.core.model.StartMode
import com.dpdpxray.core.netcapture.FlowProtocol
import com.dpdpxray.core.netcapture.FlowRecord
import com.dpdpxray.core.netcapture.TlsStatus

/**
 * Test data shaped like a real app that works in the background: an attendance tracker that runs a foreground service,
 * is a device admin and sits on an "enter organiser passcode" screen. A Quick check left it running (a restart would have
 * switched off its device admin and foreground service, and the person chose not to restart it) and saw one lookup of
 * its own server in a 15-second window: too little to judge. Such an app talks to its server every few minutes in the
 * background, so a 10-minute background watch of the same app sees 14 lookups of its own server and 2 of a catalogued
 * analytics host. Names are placeholders under `.example`, apart from the catalogued analytics host.
 */
object QuietBackgroundAppReplay {
    private const val S = 1_000_000_000L
    const val PACKAGE = "com.example.rollcall"
    const val LABEL = "RollCall"
    const val OWN_HOST = "api.rollcall.example"
    const val ANALYTICS_HOST = "app-measurement.com"
    const val ANALYTICS_ENTITY = "Google Analytics for Firebase"
    const val WATCH_MS = 600_000L

    private val leftRunning = PlanConditions(
        startMode = StartMode.NOT_RESTARTED, runningBeforeStart = true,
        restartProblem = "restarting it would switch off its device admin and foreground service",
        privateDnsAtStart = false, privateDnsAtEnd = false, otherVpnAtEnd = false, networkAtStart = true, networkAtEnd = true,
        phoneLocked = false, appCrashed = false,
        blocker = BlockedStep(kind = "BIOMETRIC", handedOff = true, waitedMs = 12_000, consentReached = false),
        restartSideEffects = listOf("DEVICE_ADMIN", "FOREGROUND_SERVICE"), restartChoice = RestartChoice.SKIPPED_BY_PERSON,
    )

    private fun health(seconds: Long, lookups: Int) = CaptureHealth(
        captureStartedNanos = 0, captureEndedNanos = seconds * S, dnsQueriesParsed = lookups.toLong(), malformedPackets = 0,
        hiddenDnsPackets = 0, nonDnsPackets = 0, dnsResponsesRelayed = lookups.toLong(), dnsRelayFailures = 0,
    )

    private fun flow(id: Long, at: Long, host: String) = FlowRecord(
        id = id, protocol = FlowProtocol.TCP, dstIp = "203.0.113.${10 + id}", dstPort = 443, startNanos = at, endNanos = at + 2 * S,
        bytesUp = 900, bytesDown = 2_400, sni = host, dnsName = host, tls = TlsStatus.SNI_ONLY,
    )

    /** The Quick check as seen on the phone: one lookup and one connection in 15 seconds; inconclusive (V0). */
    fun quickCheck(): AuditSession = AuditSession(
        id = "rollcall-REJECT", targetPackage = PACKAGE, targetLabel = LABEL, plan = AuditPlan.REJECT,
        startedAtNanos = 0, endedAtNanos = 15 * S, startedAtEpochMs = 1_791_800_000_000,
        netEvents = listOf(NetEvent(4 * S, OWN_HOST)), freshState = false, permissionDialogsWatched = true,
        captureHealth = health(15, 1), privateDnsActive = false, conditions = leftRunning,
        flows = listOf(flow(1, 4 * S + S / 10, OWN_HOST)), captureDepth = "full-relay",
        notes = listOf("Not restarted before this plan: you chose to check without restarting, because restarting RollCall would switch off its device admin and foreground service."),
    )

    /**
     * A 10-minute background watch of the same app, the phone locked for part of it: 14 lookups of its own server and 2 of
     * a catalogued analytics host, all while the app was off screen.
     */
    fun backgroundWatch(): AuditSession {
        val own = (0 until 14).map { i -> NetEvent((20 + i * 40L) * S, OWN_HOST) }
        val analytics = listOf(NetEvent(250 * S, ANALYTICS_HOST), NetEvent(520 * S, ANALYTICS_HOST))
        return AuditSession(
            id = "rollcall-WATCH", targetPackage = PACKAGE, targetLabel = LABEL, plan = AuditPlan.SILENT,
            startedAtNanos = 0, endedAtNanos = WATCH_MS * 1_000_000, startedAtEpochMs = 1_791_800_600_000,
            netEvents = (own + analytics).sortedBy { it.tNanos }, freshState = false,
            captureHealth = health(WATCH_MS / 1000, 16), privateDnsActive = false, backgroundStartedNanos = S / 2,
            conditions = PlanConditions(
                startMode = StartMode.NOT_RESTARTED, runningBeforeStart = true, privateDnsAtStart = false, privateDnsAtEnd = false,
                otherVpnAtEnd = false, networkAtStart = true, networkAtEnd = true, phoneLocked = true,
            ),
            flows = listOf(flow(1, 20 * S + S / 10, OWN_HOST), flow(2, 250 * S + S / 10, ANALYTICS_HOST), flow(3, 520 * S + S / 10, ANALYTICS_HOST)),
            captureDepth = "full-relay",
            watch = BackgroundWatch(plannedMs = WATCH_MS, watchedMs = WATCH_MS),
        )
    }

    /** A 10-minute watch that still saw only one lookup and one connection: inconclusive, worded as what the watch saw. */
    fun quietWatch(): AuditSession = backgroundWatch().copy(
        id = "rollcall-WATCH-quiet",
        netEvents = listOf(NetEvent(300 * S, OWN_HOST)),
        captureHealth = health(WATCH_MS / 1000, 1),
        flows = listOf(flow(1, 300 * S + S / 10, OWN_HOST)),
    )
}
