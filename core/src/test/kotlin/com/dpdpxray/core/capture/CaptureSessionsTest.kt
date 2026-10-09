package com.dpdpxray.core.capture

import com.dpdpxray.core.dns.DnsAnswers
import com.dpdpxray.core.dns.TestPackets
import com.dpdpxray.core.model.AnswerStatus
import com.dpdpxray.core.model.NetEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class CaptureSessionsTest {
    private val S = 1_000_000_000L
    private val now = AtomicLong(100 * S)
    private val published = mutableListOf<List<NetEvent>>()
    private fun sessions(capacity: Int = CaptureSessions.DEFAULT_CAPACITY) =
        CaptureSessions(capacity, clock = { now.get() }, onEvents = { synchronized(published) { published += it } })

    private val query = TestPackets.dnsQuery("metrics.shop.test", 1)
    private fun lookup(t: Long) = NetEvent(t, "metrics.shop.test")
    private fun answer(vararg ips: String) = DnsAnswers.parse(TestPackets.reply(query, ips.map { TestPackets.a(null, it) }), query)

    @Test
    fun `before capture starts nothing is measured and nothing reads as zero`() {
        val h = sessions().snapshot().health
        assertNull(h.captureStartedNanos)
        assertNull(h.captureEndedNanos)
        assertNull(h.dnsQueriesParsed)
        assertNull(h.malformedPackets)
        assertNull(h.eventsRecorded)
        assertNull(h.eventsDropped)
        assertNull(h.dnsUpstreamPayloadBytesSent)
        assertNull(h.dnsUpstreamDatagramsSent)
        assertEquals(CaptureSessions.DEFAULT_CAPACITY, h.eventBufferCapacity)
        assertTrue(h.notes.single().contains("not running"))
    }

    @Test
    fun `counters start at zero when measurement begins and the window runs to the snapshot`() {
        val c = sessions()
        now.set(105 * S); c.captureStarted()
        now.set(140 * S)
        val h = c.snapshot().health
        assertEquals(105 * S, h.captureStartedNanos)
        assertEquals(140 * S, h.captureEndedNanos)
        listOf(h.dnsQueriesParsed, h.malformedPackets, h.dnsResponsesRelayed, h.dnsRelayFailures, h.malformedResponses,
            h.eventsRecorded, h.eventsDropped, h.dnsUpstreamPayloadBytesSent, h.dnsUpstreamDatagramsSent,
            h.dnsUpstreamPayloadBytesReceived, h.dnsUpstreamDatagramsReceived).forEach { assertEquals(0L, it) }
        assertNull("no lookup yet", h.firstLookupNanos)
        assertTrue(h.notes.isEmpty())
    }

    @Test
    fun `lookups and malformed packets are counted with first and last lookup times`() {
        val c = sessions().also { it.captureStarted() }
        c.recordLookup(lookup(110 * S)); c.malformedPacket(); c.recordLookup(lookup(112 * S)); c.recordLookup(lookup(111 * S))
        val h = c.snapshot().health
        assertEquals(3L, h.dnsQueriesParsed)
        assertEquals(1L, h.malformedPackets)
        assertEquals(3L, h.eventsRecorded)
        assertEquals(110 * S, h.firstLookupNanos)
        assertEquals(112 * S, h.lastLookupNanos)
    }

    @Test
    fun `the buffer keeps exactly 5000 events and counts every later one as dropped`() {
        val c = sessions().also { it.captureStarted() }
        repeat(5_000) { assertFalse(c.recordLookup(lookup(it.toLong())).dropped) }
        assertEquals(0L, c.snapshot().health.eventsDropped)
        val overflow = c.recordLookup(lookup(5_000))
        assertTrue(overflow.dropped)
        repeat(9) { c.recordLookup(lookup(5_001L + it)) }
        val snap = c.snapshot()
        assertEquals(5_000, snap.events.size)
        assertEquals(5_000L, snap.health.eventsRecorded)
        assertEquals(10L, snap.health.eventsDropped)
        assertEquals(5_010L, snap.health.dnsQueriesParsed)
        assertEquals("oldest kept, newest dropped", 4_999L, snap.events.last().tNanos)
        assertEquals("dropped lookups still count for timing", 5_009L, snap.health.lastLookupNanos)
    }

    @Test
    fun `replies attach to their event and are classified by outcome`() {
        val c = sessions(capacity = 2).also { it.captureStarted() }
        val ok = c.recordLookup(lookup(1)); val bad = c.recordLookup(lookup(2)); val dropped = c.recordLookup(lookup(3))
        assertTrue(c.replyReceived(ok, answer("192.0.2.10")))
        assertTrue(c.replyReceived(bad, DnsAnswers.parse(ByteArray(4), query)))
        assertTrue(c.replyReceived(dropped, answer("192.0.2.11")))
        val snap = c.snapshot()
        assertEquals(listOf("192.0.2.10"), snap.events[0].answerIps)
        assertEquals(AnswerStatus.COMPLETE, snap.events[0].answerStatus)
        assertEquals(AnswerStatus.MALFORMED, snap.events[1].answerStatus)
        assertEquals(3L, snap.health.dnsResponsesRelayed)
        assertEquals("PARTIAL/MISMATCHED/MALFORMED replies count", 1L, snap.health.malformedResponses)
    }

    @Test
    fun `resolver failures mark the lookup as no reply`() {
        val c = sessions().also { it.captureStarted() }
        val t = c.recordLookup(lookup(1))
        assertTrue(c.relayFailed(t))
        val snap = c.snapshot()
        assertEquals(AnswerStatus.NO_REPLY, snap.events.single().answerStatus)
        assertNull(snap.events.single().rcode)
        assertEquals(1L, snap.health.dnsRelayFailures)
        assertEquals(0L, snap.health.dnsResponsesRelayed)
    }

    @Test
    fun `every upstream attempt is counted, including retries and timeouts`() {
        val c = sessions().also { it.captureStarted() }
        c.recordLookup(lookup(1))
        repeat(3) { c.upstreamSent(query.size) } // two resolvers timed out, the third answered
        c.upstreamReceived(120)
        val h = c.snapshot().health
        assertEquals(3L, h.dnsUpstreamDatagramsSent)
        assertEquals(3L * query.size, h.dnsUpstreamPayloadBytesSent)
        assertEquals(1L, h.dnsUpstreamDatagramsReceived)
        assertEquals(120L, h.dnsUpstreamPayloadBytesReceived)
    }

    @Test
    fun `upstream bytes outside a measurement window are not attributed to any session`() {
        val c = sessions()
        c.upstreamSent(40)
        c.captureStarted(); c.captureStopped()
        c.upstreamSent(40)
        assertEquals(0L, c.snapshot().health.dnsUpstreamPayloadBytesSent)
    }

    @Test
    fun `a late reply from the previous session cannot touch the new one`() {
        val c = sessions().also { it.captureStarted() }
        val old = c.recordLookup(lookup(1))
        val firstId = c.currentSessionId
        now.set(200 * S); c.reset()
        val fresh = c.recordLookup(lookup(201 * S))
        assertFalse(c.replyReceived(old, answer("198.51.100.1")))
        assertFalse(c.relayFailed(old))
        val snap = c.snapshot()
        assertTrue(snap.sessionId != firstId)
        assertEquals(1, snap.events.size)
        assertNull(snap.events.single().answerStatus)
        assertEquals(0L, snap.health.dnsResponsesRelayed)
        assertEquals(0L, snap.health.dnsRelayFailures)
        assertTrue(c.replyReceived(fresh, answer("192.0.2.10")))
    }

    @Test
    fun `reset while capturing starts measuring the new session immediately`() {
        val c = sessions().also { it.captureStarted() }
        c.recordLookup(lookup(1)); c.upstreamSent(30)
        now.set(300 * S); c.reset()
        val h = c.snapshot().health
        assertEquals(300 * S, h.captureStartedNanos)
        assertEquals(0L, h.dnsQueriesParsed)
        assertEquals(0L, h.dnsUpstreamPayloadBytesSent)
        assertTrue(c.snapshot().events.isEmpty())
    }

    @Test
    fun `reset without a running capture leaves the new session unmeasured`() {
        val c = sessions()
        c.reset()
        assertNull(c.snapshot().health.dnsQueriesParsed)
        now.set(150 * S); c.captureStarted()
        assertEquals(150 * S, c.snapshot().health.captureStartedNanos)
    }

    @Test
    fun `an abnormal stop and a restart are recorded as notes`() {
        val c = sessions()
        now.set(100 * S); c.captureStarted()
        now.set(112 * S); c.captureStopped("VPN was turned off by the system or the user")
        val stopped = c.snapshot().health
        assertEquals(112 * S, stopped.captureEndedNanos)
        assertTrue(stopped.notes.single().contains("12.0 s"))
        assertTrue(stopped.notes.single().contains("VPN was turned off"))
        now.set(120 * S); c.captureStarted()
        val restarted = c.snapshot().health
        assertEquals(100 * S, restarted.captureStartedNanos)
        assertEquals(2, restarted.notes.size)
        assertTrue(restarted.notes[1].contains("8.0 s"))
    }

    @Test
    fun `a normal stop ends the window without a note`() {
        val c = sessions()
        now.set(100 * S); c.captureStarted()
        now.set(130 * S); c.captureStopped()
        now.set(500 * S)
        val h = c.snapshot().health
        assertEquals(130 * S, h.captureEndedNanos)
        assertTrue(h.notes.isEmpty())
    }

    @Test
    fun `packets that arrive before the start signal still begin measurement, with a note`() {
        val c = sessions()
        now.set(101 * S); c.recordLookup(lookup(101 * S))
        val h = c.snapshot().health
        assertEquals(101 * S, h.captureStartedNanos)
        assertEquals(1L, h.dnsQueriesParsed)
        assertTrue(h.notes.single().contains("first packet"))
    }

    @Test
    fun `snapshots are immutable and do not change afterwards`() {
        val c = sessions().also { it.captureStarted() }
        c.recordLookup(lookup(1))
        val snap = c.snapshot()
        c.recordLookup(lookup(2))
        assertEquals(1, snap.events.size)
        assertEquals(1L, snap.health.dnsQueriesParsed)
        assertThrows(UnsupportedOperationException::class.java) { (snap.events as MutableList<NetEvent>).add(lookup(3)) }
    }

    @Test
    fun `listeners only ever see the current session`() {
        val c = sessions().also { it.captureStarted() }
        val old = c.recordLookup(lookup(1))
        c.reset()
        synchronized(published) { published.clear() }
        c.replyReceived(old, answer("198.51.100.1"))
        assertTrue("late reply published nothing", synchronized(published) { published.isEmpty() })
        c.recordLookup(lookup(2))
        assertEquals(listOf(2L), synchronized(published) { published.last().map { it.tNanos } })
    }

    @Test
    fun `concurrent lookups, replies and byte counts stay exact`() {
        val c = sessions().also { it.captureStarted() }
        val threads = 8
        val perThread = 1_000
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        repeat(threads) {
            pool.execute {
                start.await()
                repeat(perThread) { i ->
                    val t = c.recordLookup(lookup(i.toLong()))
                    c.upstreamSent(30)
                    if (i % 2 == 0) { c.upstreamReceived(50); c.replyReceived(t, answer("192.0.2.10")) } else c.relayFailed(t)
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        val snap = c.snapshot()
        val h = snap.health
        assertEquals(8_000L, h.dnsQueriesParsed)
        assertEquals(5_000L, h.eventsRecorded)
        assertEquals(3_000L, h.eventsDropped)
        assertEquals(5_000, snap.events.size)
        assertEquals(4_000L, h.dnsResponsesRelayed)
        assertEquals(4_000L, h.dnsRelayFailures)
        assertEquals(8_000L, h.dnsUpstreamDatagramsSent)
        assertEquals(240_000L, h.dnsUpstreamPayloadBytesSent)
        assertEquals(200_000L, h.dnsUpstreamPayloadBytesReceived)
        assertTrue(snap.events.all { it.answerStatus == AnswerStatus.COMPLETE || it.answerStatus == AnswerStatus.NO_REPLY })
    }

    @Test
    fun `replies racing a reset never leak into the new session`() {
        val c = sessions().also { it.captureStarted() }
        val oldTickets = (0 until 2_000).map { c.recordLookup(lookup(it.toLong())) }
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        oldTickets.chunked(500).forEach { chunk -> pool.execute { start.await(); chunk.forEach { c.replyReceived(it, answer("198.51.100.1")) } } }
        start.countDown()
        c.reset()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        val h = c.snapshot().health
        assertEquals(0L, h.dnsResponsesRelayed)
        assertEquals(0L, h.malformedResponses)
        assertTrue(c.snapshot().events.isEmpty())
    }
}
