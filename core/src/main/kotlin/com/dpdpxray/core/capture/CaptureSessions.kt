package com.dpdpxray.core.capture

import com.dpdpxray.core.dns.DnsAnswer
import com.dpdpxray.core.dns.withAnswer
import com.dpdpxray.core.model.AnswerStatus
import com.dpdpxray.core.model.CaptureHealth
import com.dpdpxray.core.model.NetEvent
import java.util.Collections
import java.util.Locale

/** A recorded lookup's place in its session, used to attach the resolver's reply later. */
class LookupTicket internal constructor(internal val session: CaptureSession, internal val index: Int) {
    val sessionId: Long get() = session.id

    /** The buffer was full: the lookup was counted but its event was not kept. */
    val dropped: Boolean get() = index < 0
}

/**
 * One session's evidence at a point in time. Both parts come from the same instant and never change afterwards.
 * [health] covers [CaptureHealth.captureStartedNanos]..[CaptureHealth.captureEndedNanos]; while capture is still
 * running the end is the snapshot time.
 */
data class CaptureSnapshot(val sessionId: Long, val events: List<NetEvent>, val health: CaptureHealth)

/**
 * Bounded, thread-safe capture state for one audit session at a time. [reset] closes the current session and opens a
 * new one; replies that arrive later for a closed session are ignored, so they can never change the new session.
 *
 * Buffer: at most [capacity] events per session. When full, **newer events are dropped** and counted in
 * `eventsDropped`; the earliest events are kept because consent findings depend on launch-to-choice timing.
 *
 * Counters are null until measurement begins (capture running for this session) and start from zero then.
 * Lookup, reply and failure counters belong to the lookup's own session. Upstream byte counters belong to the session
 * being measured when the bytes were sent or received, so they match that session's time window.
 *
 * Times come from [clock], which must be the clock of [NetEvent.tNanos] (`SystemClock.elapsedRealtimeNanos`).
 */
class CaptureSessions(
    val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long,
    /** Receives the current session's events after every change to them, under the internal lock; keep it cheap. */
    private val onEvents: (List<NetEvent>) -> Unit = {},
) {
    private val lock = Any()
    private var nextId = 1L
    private var running = false
    private var current = CaptureSession(nextId++, capacity)

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    val currentSessionId: Long get() = synchronized(lock) { current.id }

    /** Closes the current session and starts a new one, measuring at once if capture is running. Returns its id. */
    fun reset(): Long = synchronized(lock) {
        val now = clock()
        current.close(now)
        current = CaptureSession(nextId++, capacity)
        if (running) current.begin(now)
        publish()
        current.id
    }

    /** The tunnel is up: measurement begins (or resumes) for the current session. */
    fun captureStarted() = synchronized(lock) {
        running = true
        current.begin(clock())
    }

    /** The tunnel is down. A [reason] marks an abnormal stop and is kept as a note. */
    fun captureStopped(reason: String? = null) = synchronized(lock) {
        if (!running) return@synchronized
        running = false
        current.end(clock(), reason)
    }

    /** A tunnel packet that could not be parsed as a DNS question. */
    fun malformedPacket() = synchronized(lock) {
        current.ensureMeasuring(clock())
        current.malformedPackets++
    }

    /** Records a parsed lookup in the current session; the ticket says whether its event was kept. */
    fun recordLookup(event: NetEvent): LookupTicket = synchronized(lock) {
        val s = current
        s.ensureMeasuring(clock())
        s.queriesParsed++
        s.firstLookup = minOf(s.firstLookup ?: event.tNanos, event.tNanos)
        s.lastLookup = maxOf(s.lastLookup ?: event.tNanos, event.tNanos)
        val ticket = if (s.events.size < capacity) {
            s.events += event
            LookupTicket(s, s.events.lastIndex)
        } else {
            s.eventsDropped++
            LookupTicket(s, -1)
        }
        publish()
        ticket
    }

    /** A resolver reply was relayed to the app. False when the ticket's session is closed and the reply was ignored. */
    fun replyReceived(ticket: LookupTicket, answer: DnsAnswer): Boolean = synchronized(lock) {
        val s = ticket.session
        if (s.closed) return@synchronized false
        s.responsesRelayed++
        if (answer.status != AnswerStatus.COMPLETE) s.malformedResponses++
        if (!ticket.dropped) {
            s.events[ticket.index] = s.events[ticket.index].withAnswer(answer)
            publish()
        }
        true
    }

    /** Every resolver failed for this lookup; the app got SERVFAIL. False when the session is closed. */
    fun relayFailed(ticket: LookupTicket): Boolean = synchronized(lock) {
        val s = ticket.session
        if (s.closed) return@synchronized false
        s.relayFailures++
        if (!ticket.dropped) {
            s.events[ticket.index] = s.events[ticket.index].copy(answerStatus = AnswerStatus.NO_REPLY, rcode = null)
            publish()
        }
        true
    }

    /** One datagram of [payloadBytes] DNS message bytes was sent to an upstream resolver (each attempt counts). */
    fun upstreamSent(payloadBytes: Int) = synchronized(lock) {
        val s = current
        if (!s.measuring) return@synchronized
        s.upstreamDatagramsSent++
        s.upstreamBytesSent += payloadBytes
    }

    /** One datagram of [payloadBytes] DNS message bytes was received from an upstream resolver. */
    fun upstreamReceived(payloadBytes: Int) = synchronized(lock) {
        val s = current
        if (!s.measuring) return@synchronized
        s.upstreamDatagramsReceived++
        s.upstreamBytesReceived += payloadBytes
    }

    fun events(): List<NetEvent> = synchronized(lock) { current.frozenEvents() }

    fun snapshot(): CaptureSnapshot = synchronized(lock) {
        CaptureSnapshot(current.id, current.frozenEvents(), current.health(clock()))
    }

    private fun publish() = onEvents(current.frozenEvents())

    companion object {
        const val DEFAULT_CAPACITY = 5_000
    }
}

/** Mutable state of one session. Every access happens under [CaptureSessions]' lock. */
internal class CaptureSession(val id: Long, private val capacity: Int) {
    val events = ArrayList<NetEvent>(minOf(capacity, 256))
    var closed = false
    private var startedAt: Long? = null
    private var endedAt: Long? = null
    private val notes = mutableListOf<String>()

    var queriesParsed = 0L
    var malformedPackets = 0L
    var responsesRelayed = 0L
    var relayFailures = 0L
    var malformedResponses = 0L
    var eventsDropped = 0L
    var firstLookup: Long? = null
    var lastLookup: Long? = null
    var upstreamBytesSent = 0L
    var upstreamDatagramsSent = 0L
    var upstreamBytesReceived = 0L
    var upstreamDatagramsReceived = 0L

    val measuring: Boolean get() = startedAt != null && endedAt == null && !closed

    fun begin(now: Long) {
        val start = startedAt
        val end = endedAt
        when {
            start == null -> startedAt = now
            end != null -> {
                notes += "Capture restarted after a ${secs(now - end)} s gap; lookups during the gap were not seen."
                endedAt = null
            }
        }
    }

    /** Packets arrived although no start was signalled for this session: measure from now and say so. */
    fun ensureMeasuring(now: Long) {
        if (startedAt != null) return
        startedAt = now
        notes += "Capture start was not signalled; counters begin at the first packet."
    }

    fun end(now: Long, reason: String?) {
        val start = startedAt ?: return
        if (endedAt != null) return
        endedAt = now
        if (reason != null) notes += "Capture stopped ${secs(now - start)} s after it started: $reason."
    }

    fun close(now: Long) {
        if (startedAt != null && endedAt == null) endedAt = now
        closed = true
    }

    fun frozenEvents(): List<NetEvent> = Collections.unmodifiableList(ArrayList(events))

    fun health(now: Long): CaptureHealth {
        val start = startedAt
            ?: return CaptureHealth(
                eventBufferCapacity = capacity,
                notes = notes + "Capture was not running during this session; nothing was measured.",
            )
        return CaptureHealth(
            captureStartedNanos = start,
            captureEndedNanos = endedAt ?: now,
            dnsQueriesParsed = queriesParsed,
            malformedPackets = malformedPackets,
            dnsResponsesRelayed = responsesRelayed,
            dnsRelayFailures = relayFailures,
            malformedResponses = malformedResponses,
            eventsRecorded = events.size.toLong(),
            eventsDropped = eventsDropped,
            eventBufferCapacity = capacity,
            firstLookupNanos = firstLookup,
            lastLookupNanos = lastLookup,
            dnsUpstreamPayloadBytesSent = upstreamBytesSent,
            dnsUpstreamDatagramsSent = upstreamDatagramsSent,
            dnsUpstreamPayloadBytesReceived = upstreamBytesReceived,
            dnsUpstreamDatagramsReceived = upstreamDatagramsReceived,
            notes = notes.toList(),
        )
    }

    private fun secs(nanos: Long) = String.format(Locale.US, "%.1f", nanos / 1e9)
}
