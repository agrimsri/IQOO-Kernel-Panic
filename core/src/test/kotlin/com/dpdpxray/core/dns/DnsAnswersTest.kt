package com.dpdpxray.core.dns

import com.dpdpxray.core.dns.TestPackets.a
import com.dpdpxray.core.dns.TestPackets.aaaa
import com.dpdpxray.core.dns.TestPackets.cname
import com.dpdpxray.core.dns.TestPackets.dnsQuery
import com.dpdpxray.core.dns.TestPackets.reply
import com.dpdpxray.core.model.AnswerStatus
import com.dpdpxray.core.model.NetEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DnsAnswersTest {
    private val query = dnsQuery("metrics.shop.test", 1)

    @Test
    fun `reads A records for the queried name`() {
        val q = dnsQuery("example.com", 1)
        val r = DnsAnswers.parse(reply(q, listOf(a(null, "93.184.216.34"), a(null, "93.184.216.35"))), q)
        assertEquals(AnswerStatus.COMPLETE, r.status)
        assertEquals("example.com", r.questionName)
        assertEquals(listOf("93.184.216.34", "93.184.216.35"), r.answerIps)
        assertTrue(r.cnameChain.isEmpty())
        assertEquals(0, r.rcode)
    }

    @Test
    fun `formats AAAA addresses in canonical compressed form`() {
        val q = dnsQuery("example.com", 28)
        val r = DnsAnswers.parse(reply(q, listOf(aaaa(null, 0x2001, 0xdb8, 0, 0, 0, 0, 0, 0x10), aaaa(null, 0x2001, 0xdb8, 0, 1, 0, 0, 1, 0))), q)
        assertEquals(listOf("2001:db8::10", "2001:db8:0:1::1:0"), r.answerIps)
    }

    @Test
    fun `follows a CNAME chain from the queried name to its addresses`() {
        val r = DnsAnswers.parse(
            reply(query, listOf(
                cname(null, "t.tracker.example"),
                cname("t.tracker.example", "edge.cdn.example"),
                a("edge.cdn.example", "192.0.2.10"),
            )),
            query,
        )
        assertEquals(AnswerStatus.COMPLETE, r.status)
        assertEquals(listOf("metrics.shop.test", "t.tracker.example", "edge.cdn.example"), r.cnameChain)
        assertEquals(listOf("192.0.2.10"), r.answerIps)
    }

    @Test
    fun `follows records by owner even when they are out of order and differently cased`() {
        val r = DnsAnswers.parse(
            reply(query, listOf(
                a("EDGE.cdn.example", "192.0.2.10"),
                cname("T.Tracker.Example", "edge.cdn.example"),
                cname("Metrics.Shop.Test", "t.tracker.example"),
            )),
            query,
        )
        assertEquals(listOf("metrics.shop.test", "t.tracker.example", "edge.cdn.example"), r.cnameChain)
        assertEquals(listOf("192.0.2.10"), r.answerIps)
    }

    @Test
    fun `records owned by other names and other sections are never attributed`() {
        val r = DnsAnswers.parse(
            reply(
                query,
                answers = listOf(a(null, "192.0.2.1"), a("unrelated.example", "198.51.100.1"), cname("other.example", "tracker.example")),
                authority = listOf(a(null, "198.51.100.2")),
                additional = listOf(a(null, "198.51.100.3"), a("tracker.example", "198.51.100.4")),
            ),
            query,
        )
        assertEquals(listOf("192.0.2.1"), r.answerIps)
        assertTrue(r.cnameChain.isEmpty())
    }

    @Test
    fun `chain is capped at eight aliases and then attributes no addresses`() {
        val names = listOf("metrics.shop.test") + (1..10).map { "n$it.example" }
        val records = names.zipWithNext { from, to -> cname(from, to) } + a(names.last(), "192.0.2.10")
        val r = DnsAnswers.parse(reply(query, records), query)
        assertEquals(AnswerStatus.PARTIAL, r.status)
        assertEquals(1 + DnsAnswers.MAX_CHAIN, r.cnameChain.size)
        assertEquals(names.take(1 + DnsAnswers.MAX_CHAIN), r.cnameChain)
        assertTrue(r.answerIps.isEmpty())
    }

    @Test
    fun `a CNAME loop stops with a partial result`() {
        val r = DnsAnswers.parse(
            reply(query, listOf(cname(null, "b.example"), cname("b.example", "metrics.shop.test"), a(null, "192.0.2.10"))),
            query,
        )
        assertEquals(AnswerStatus.PARTIAL, r.status)
        assertEquals(listOf("metrics.shop.test", "b.example"), r.cnameChain)
        assertTrue(r.answerIps.isEmpty())
    }

    @Test
    fun `a reply to a different query is not attributed`() {
        val good = listOf(a(null, "192.0.2.10"))
        assertEquals(AnswerStatus.MISMATCHED, DnsAnswers.parse(reply(query, good, id = 0x9999), query).status)
        val otherQuestion = dnsQuery("other.example", 1)
        val wrongName = DnsAnswers.parse(reply(otherQuestion, good), query)
        assertEquals(AnswerStatus.MISMATCHED, wrongName.status)
        assertTrue(wrongName.answerIps.isEmpty())
        val wrongType = DnsAnswers.parse(reply(dnsQuery("metrics.shop.test", 28), good), query)
        assertEquals(AnswerStatus.MISMATCHED, wrongType.status)
        assertEquals(AnswerStatus.MISMATCHED, DnsAnswers.parse(reply(query, good, flags = 0x0100), query).status)
    }

    @Test
    fun `NXDOMAIN is complete with no answers and its rcode`() {
        val r = DnsAnswers.parse(reply(query, emptyList(), flags = 0x8183), query)
        assertEquals(AnswerStatus.COMPLETE, r.status)
        assertEquals(3, r.rcode)
        assertTrue(r.answerIps.isEmpty() && r.cnameChain.isEmpty())
    }

    @Test
    fun `the truncation flag makes the result partial but keeps what was read`() {
        val r = DnsAnswers.parse(reply(query, listOf(a(null, "192.0.2.10")), flags = 0x8380), query)
        assertEquals(AnswerStatus.PARTIAL, r.status)
        assertEquals(listOf("192.0.2.10"), r.answerIps)
    }

    @Test
    fun `an A record with the wrong length is skipped`() {
        val r = DnsAnswers.parse(reply(query, listOf(TestPackets.Rr(null, 1, byteArrayOf(1, 2, 3)), a(null, "192.0.2.10"))), query)
        assertEquals(AnswerStatus.PARTIAL, r.status)
        assertEquals(listOf("192.0.2.10"), r.answerIps)
    }

    @Test
    fun `self and forward compression pointers do not hang`() {
        val full = reply(query, listOf(a(null, "192.0.2.10")))
        val ownerAt = query.size
        val self = full.copyOf().also { it[ownerAt] = (0xC0 or (ownerAt shr 8)).toByte(); it[ownerAt + 1] = ownerAt.toByte() }
        val forward = full.copyOf().also { it[ownerAt] = 0xC0.toByte(); it[ownerAt + 1] = (ownerAt + 4).toByte() }
        for (bad in listOf(self, forward)) {
            val r = DnsAnswers.parse(bad, query)
            assertNotEquals(AnswerStatus.COMPLETE, r.status)
            assertTrue(r.answerIps.isEmpty())
        }
    }

    @Test
    fun `every truncation point returns without throwing and invents nothing`() {
        val full = reply(query, listOf(cname(null, "t.tracker.example"), a("t.tracker.example", "192.0.2.10"), a("t.tracker.example", "192.0.2.11")))
        val complete = DnsAnswers.parse(full, query)
        assertEquals(AnswerStatus.COMPLETE, complete.status)
        for (cut in 0 until full.size) {
            val r = DnsAnswers.parse(full.copyOf(cut), query)
            assertNotEquals("cut at $cut", AnswerStatus.COMPLETE, r.status)
            assertTrue(complete.answerIps.containsAll(r.answerIps))
            assertTrue(r.cnameChain.isEmpty() || complete.cnameChain.take(r.cnameChain.size) == r.cnameChain)
        }
    }

    @Test
    fun `random and mutated input never throws`() {
        val rnd = Random(20261010)
        val base = reply(query, listOf(cname(null, "t.tracker.example"), a("t.tracker.example", "192.0.2.10"), aaaa("t.tracker.example", 0x2001, 0xdb8)))
        repeat(5_000) {
            val bytes = if (it % 2 == 0) rnd.nextBytes(rnd.nextInt(0, 600))
            else base.copyOf().also { b -> repeat(rnd.nextInt(1, 6)) { b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() } }
            val r = DnsAnswers.parse(bytes, query)
            assertTrue(r.cnameChain.size <= 1 + DnsAnswers.MAX_CHAIN)
            assertTrue(r.answerIps.size <= DnsAnswers.MAX_IPS)
        }
        DnsAnswers.parse(base, ByteArray(3)) // unreadable query
    }

    @Test
    fun `only verified answers are attached to an event`() {
        val event = NetEvent(1, "metrics.shop.test")
        val ok = DnsAnswers.parse(reply(query, listOf(cname(null, "t.tracker.example"), a("t.tracker.example", "192.0.2.10"))), query)
        val attached = event.withAnswer(ok)
        assertEquals(listOf("metrics.shop.test", "t.tracker.example"), attached.cnameChain)
        assertEquals(listOf("192.0.2.10"), attached.answerIps)
        assertEquals(AnswerStatus.COMPLETE, attached.answerStatus)
        assertEquals(0, attached.rcode)

        val mismatched = DnsAnswers.parse(reply(query, listOf(a(null, "192.0.2.10")), id = 0x9999), query)
        assertEquals(event.copy(answerStatus = AnswerStatus.MISMATCHED), event.withAnswer(mismatched))

        val otherQuery = dnsQuery("other.example", 1)
        val validForAnotherHost = DnsAnswers.parse(reply(otherQuery, listOf(a(null, "192.0.2.9"))), otherQuery)
        assertEquals(AnswerStatus.COMPLETE, validForAnotherHost.status)
        assertEquals("a valid reply for another name is a mismatch for this event", event.copy(answerStatus = AnswerStatus.MISMATCHED), event.withAnswer(validForAnotherHost))
    }

    @Test
    fun `status and rcode survive even when no address can be attributed`() {
        val event = NetEvent(1, "metrics.shop.test")
        val nx = event.withAnswer(DnsAnswers.parse(reply(query, emptyList(), flags = 0x8183), query))
        assertEquals(AnswerStatus.COMPLETE, nx.answerStatus)
        assertEquals("NXDOMAIN is identifiable", 3, nx.rcode)
        assertTrue(nx.answerIps.isEmpty())

        val noData = event.withAnswer(DnsAnswers.parse(reply(query, emptyList()), query))
        assertEquals(AnswerStatus.COMPLETE to 0, noData.answerStatus to noData.rcode)

        val looping = event.withAnswer(DnsAnswers.parse(reply(query, listOf(cname(null, "b.example"), cname("b.example", "metrics.shop.test"))), query))
        assertEquals(AnswerStatus.PARTIAL, looping.answerStatus)
        assertEquals(listOf("metrics.shop.test", "b.example"), looping.cnameChain)
        assertTrue(looping.answerIps.isEmpty())

        val malformed = event.withAnswer(DnsAnswers.parse(ByteArray(5), query))
        assertEquals(AnswerStatus.MALFORMED, malformed.answerStatus)
        assertEquals("no rcode from an unreadable reply", null, malformed.rcode)

        val wrongId = event.withAnswer(DnsAnswers.parse(reply(query, emptyList(), flags = 0x8183, id = 0x9999), query))
        assertEquals("rcode of someone else's reply is not kept", AnswerStatus.MISMATCHED to null, wrongId.answerStatus to wrongId.rcode)
    }
}
