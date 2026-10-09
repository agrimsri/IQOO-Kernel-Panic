package com.dpdpxray.app.netproof

import com.dpdpxray.core.model.CaptureHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkProofTest {
    // 40 datagrams × 28 header bytes = 1,120 bytes of IPv4/UDP overhead on 2,000 payload bytes.
    private fun session(txStart: Long? = 10_000, txEnd: Long? = 13_120, payload: Long? = 2_000, datagrams: Long? = 40, pktStart: Long? = 100, pktEnd: Long? = 140) =
        SessionCounters(UidTx(txStart, pktStart), UidTx(txEnd, pktEnd), payload, datagrams)

    private fun measured(vararg s: SessionCounters, online: List<OnlineUse> = emptyList()) =
        NetworkProofCalculator.compute(s.toList(), online) as NetworkProof.Measured

    @Test
    fun `exact DNS relay leaves a zero remainder that is measured, not assumed`() {
        val p = measured(session())
        assertEquals(3_120, p.totalTxBytes)
        assertEquals(3_120, p.dnsBytesOnWire)
        assertEquals(0, p.remainderBytes)
        assertEquals(ProofStatus.WITHIN_TOLERANCE, p.status)
        assertEquals(0L, p.packetRemainder)
    }

    @Test
    fun `sessions are summed`() {
        val p = measured(session(), session(txStart = 50_000, txEnd = 53_120))
        assertEquals(6_240, p.totalTxBytes)
        assertEquals(80, p.dnsDatagrams)
        assertEquals(0, p.remainderBytes)
    }

    @Test
    fun `unexplained upload above tolerance is reported, not hidden`() {
        val p = measured(session(txEnd = 13_120 + 50_000))
        assertEquals(50_000, p.remainderBytes)
        assertEquals(ProofStatus.ABOVE_TOLERANCE, p.status)
    }

    @Test
    fun `negative remainder is kept negative and flagged inconsistent`() {
        val p = measured(session(txEnd = 10_100))
        assertEquals(100 - 3_120L, p.remainderBytes)
        assertEquals(ProofStatus.INCONSISTENT, p.status)
    }

    @Test
    fun `missing or unsupported counters make the proof unavailable, never zero`() {
        listOf(
            session(txStart = null), session(txEnd = null), session(payload = null), session(datagrams = null),
            session(txStart = UidTx.UNSUPPORTED), session(txEnd = UidTx.UNSUPPORTED),
        ).forEach { assertTrue(it.toString(), NetworkProofCalculator.compute(listOf(it)) is NetworkProof.Unavailable) }
    }

    @Test
    fun `counter reset during a session is unavailable`() {
        val r = NetworkProofCalculator.compute(listOf(session(txStart = 90_000, txEnd = 1_000)))
        assertTrue((r as NetworkProof.Unavailable).reason.contains("reset"))
    }

    @Test
    fun `no sessions is unavailable`() = assertTrue(NetworkProofCalculator.compute(emptyList()) is NetworkProof.Unavailable)

    @Test
    fun `packet counters are optional`() {
        val p = measured(session(pktStart = null))
        assertEquals(null, p.packetRemainder)
        assertEquals(ProofStatus.WITHIN_TOLERANCE, p.status)
    }

    @Test
    fun `optional online use gets its own line and is subtracted only when measured`() {
        val p = measured(session(txEnd = 13_120 + 5_000), online = listOf(OnlineUse("Map lookup", 5_000)))
        assertEquals(0, p.remainderBytes)
        assertEquals(listOf(OnlineUse("Map lookup", 5_000)), p.online)
    }

    @Test
    fun `unmeasured online use makes the remainder uncertain`() {
        val p = measured(session(txEnd = 13_120 + 5_000), online = listOf(OnlineUse("Map lookup", null)))
        assertEquals(ProofStatus.UNCERTAIN, p.status)
    }

    @Test
    fun `builds from the A0 capture health counters`() {
        val h = CaptureHealth(dnsUpstreamPayloadBytesSent = 2_000, dnsUpstreamDatagramsSent = 40)
        val s = SessionCounters.from(h, UidTx(10_000, null), UidTx(13_120, null))
        assertEquals(0, measured(s).remainderBytes)
        assertTrue(NetworkProofCalculator.compute(listOf(SessionCounters.from(CaptureHealth(), UidTx(1, 1), UidTx(2, 2)))) is NetworkProof.Unavailable)
    }

    @Test
    fun `card text states figures and limits but never claims nothing was sent`() {
        val proofs = listOf(
            NetworkProofCalculator.compute(listOf(session())),
            NetworkProofCalculator.compute(listOf(session(txEnd = 99_000))),
            NetworkProofCalculator.compute(listOf(session(txEnd = 10_100))),
            NetworkProofCalculator.compute(listOf(session(txEnd = null))),
        )
        proofs.forEach { p ->
            val text = NetworkProofText.lines(p).joinToString("\n")
            assertFalse(text, Regex("nothing (else )?(was )?(sent|uploaded)|0 bytes leave|no data (left|leaves)", RegexOption.IGNORE_CASE).containsMatchIn(text))
            assertTrue(text, text.contains("estimate", ignoreCase = true) || p is NetworkProof.Unavailable)
        }
        val ok = NetworkProofText.lines(proofs[0]).joinToString("\n")
        assertTrue(ok, ok.contains("3,120 bytes") && ok.contains("40 datagrams") && ok.contains("± "))
    }
}
