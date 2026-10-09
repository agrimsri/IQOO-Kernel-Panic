package com.dpdpxray.app.capture

import android.os.SystemClock
import android.util.Log
import com.dpdpxray.core.capture.CaptureSessions
import com.dpdpxray.core.capture.CaptureSnapshot
import com.dpdpxray.core.capture.LookupTicket
import com.dpdpxray.core.dns.DnsAnswer
import com.dpdpxray.core.model.NetEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface CaptureState {
    data object Idle : CaptureState
    data object Starting : CaptureState
    data class Running(val targetPackage: String) : CaptureState
    data class Error(val message: String) : CaptureState
}

/**
 * Process-wide hand-off between the VPN service and the audit orchestrator/UI. Each [reset] starts a new capture
 * session (bounded to [CaptureSessions.DEFAULT_CAPACITY] events); replies that arrive afterwards for an earlier
 * session are ignored. [snapshot] returns the current session's events and [com.dpdpxray.core.model.CaptureHealth].
 */
object CaptureBus {
    private const val TAG = "TraceVeilCapture"

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    private val _events = MutableStateFlow<List<NetEvent>>(emptyList())

    /** The current session's events, at most [CaptureSessions.DEFAULT_CAPACITY] (the earliest are kept). */
    val events: StateFlow<List<NetEvent>> = _events.asStateFlow()

    private val sessions = CaptureSessions(clock = SystemClock::elapsedRealtimeNanos, onEvents = { _events.value = it })

    /** Closes the current session and starts a new one; it is measured from now if capture is running. */
    fun reset() {
        log("closed", sessions.snapshot())
        sessions.reset()
    }

    /** Immutable events and health of the current session, taken at one instant. */
    fun snapshot(): CaptureSnapshot = sessions.snapshot()

    fun setState(s: CaptureState) {
        val wasRunning = _state.value is CaptureState.Running
        // Measurement changes before observers see the new state, so a reset that reacts to Running is measured.
        when {
            s is CaptureState.Running -> sessions.captureStarted()
            wasRunning && s is CaptureState.Error -> sessions.captureStopped(s.message).also { log("stopped", sessions.snapshot()) }
            wasRunning -> sessions.captureStopped().also { log("stopped", sessions.snapshot()) }
        }
        _state.value = s
    }

    internal fun record(event: NetEvent): LookupTicket = sessions.recordLookup(event)

    internal fun malformedPacket() = sessions.malformedPacket()

    internal fun replyReceived(ticket: LookupTicket, answer: DnsAnswer) = sessions.replyReceived(ticket, answer)

    internal fun relayFailed(ticket: LookupTicket) = sessions.relayFailed(ticket)

    internal fun upstreamSent(payloadBytes: Int) = sessions.upstreamSent(payloadBytes)

    internal fun upstreamReceived(payloadBytes: Int) = sessions.upstreamReceived(payloadBytes)

    /** Counts only: no hostnames or addresses leave this object through the log. */
    private fun log(what: String, s: CaptureSnapshot) {
        val h = s.health
        if (h.captureStartedNanos == null) return
        Log.i(
            TAG,
            "session ${s.sessionId} $what: lookups=${h.dnsQueriesParsed} kept=${h.eventsRecorded} dropped=${h.eventsDropped} " +
                "malformedPackets=${h.malformedPackets} replies=${h.dnsResponsesRelayed} unreadReplies=${h.malformedResponses} " +
                "noReply=${h.dnsRelayFailures} withAddresses=${s.events.count { it.answerIps.isNotEmpty() }} " +
                "aliased=${s.events.count { it.cnameChain.isNotEmpty() }} " +
                "status=${s.events.groupingBy { it.answerStatus?.name ?: "null" }.eachCount()} " +
                "upstreamSent=${h.dnsUpstreamDatagramsSent}/${h.dnsUpstreamPayloadBytesSent}B " +
                "upstreamReceived=${h.dnsUpstreamDatagramsReceived}/${h.dnsUpstreamPayloadBytesReceived}B " +
                "windowMs=${(h.captureEndedNanos!! - h.captureStartedNanos!!) / 1_000_000} notes=${h.notes.size}",
        )
    }
}
