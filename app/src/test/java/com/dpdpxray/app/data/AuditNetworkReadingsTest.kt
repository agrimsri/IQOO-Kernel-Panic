package com.dpdpxray.app.data

import com.dpdpxray.app.netproof.NetworkProof
import com.dpdpxray.app.netproof.ProofStatus
import com.dpdpxray.core.capture.CaptureSessions
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.CaptureHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AuditNetworkReadingsTest {
    @get:Rule val temp = TemporaryFolder()

    private fun session(id: String = "plan-c", start: Long = 10, end: Long = 100, payload: Long = 112, datagrams: Long = 4) =
        AuditSession(id, "demo.test", "Demo", AuditPlan.SILENT, captureHealth = CaptureHealth(
            captureStartedNanos = start, captureEndedNanos = end,
            dnsUpstreamPayloadBytesSent = payload, dnsUpstreamDatagramsSent = datagrams,
        ))

    private fun reading(id: String = "plan-c", start: Long = 10, end: Long = 100, txStart: Long? = 1000, txEnd: Long? = 1224) =
        SessionNetworkReadings(id, start, end, txStart, txEnd, 10, 14, start, end, end)

    @Test fun `sidecar round trip preserves nullable raw counters`() {
        val input = listOf(reading(), reading("plan-b", 200, 400, null, -1))
        AuditNetworkReadings.save(temp.root, input)
        assertEquals(input, AuditNetworkReadings.load(temp.root))
    }

    @Test fun `old audits without sidecar never acquire invented counters`() {
        val readings = AuditNetworkReadings.load(temp.root)
        assertTrue(readings.isEmpty())
        assertTrue(AuditNetworkReadings.proof(listOf(session()), readings) is NetworkProof.Unavailable)
    }

    @Test fun `corrupt sidecar leaves the audit evidence usable but proof unavailable`() {
        File(temp.root, AuditNetworkReadings.FILE_NAME).writeText("{broken")
        assertTrue(AuditNetworkReadings.load(temp.root).isEmpty())
        assertTrue(AuditNetworkReadings.proof(listOf(session()), AuditNetworkReadings.load(temp.root)) is NetworkProof.Unavailable)
    }

    @Test fun `unknown schema does not silently interpret future readings`() {
        File(temp.root, AuditNetworkReadings.FILE_NAME).writeText(
            """{"schemaVersion":99,"readings":[{"sessionId":"plan-c","captureStartedNanos":10,"captureEndedNanos":100,"txBytesStart":1000,"txBytesEnd":1224,"txPacketsStart":10,"txPacketsEnd":14}]}""",
        )
        assertTrue(AuditNetworkReadings.load(temp.root).isEmpty())
    }

    @Test fun `each plan uses its own UID delta and DNS header overhead`() {
        val sessions = listOf(session(), session("plan-b", 200, 400, 132, 2))
        val readings = listOf(reading("plan-b", 200, 400, 2000, 2188), reading())
        val proof = AuditNetworkReadings.proof(sessions, readings) as NetworkProof.Measured
        assertEquals(412L, proof.totalTxBytes)
        assertEquals(244L, proof.dnsPayloadBytes)
        assertEquals(6L, proof.dnsDatagrams)
        assertEquals(412L, proof.dnsBytesOnWire)
        assertEquals(0L, proof.remainderBytes)
    }

    @Test fun `missing and unsupported upload readings are unavailable not zero`() {
        listOf(reading(txStart = null), reading(txEnd = null), reading(txStart = -1)).forEach {
            assertTrue(AuditNetworkReadings.proof(listOf(session()), listOf(it)) is NetworkProof.Unavailable)
        }
    }

    @Test fun `counter reset is not a negative upload measurement`() {
        assertTrue(AuditNetworkReadings.proof(listOf(session()), listOf(reading(txEnd = 900))) is NetworkProof.Unavailable)
    }

    @Test fun `capture window mismatch refuses unrelated readings`() {
        assertTrue(AuditNetworkReadings.proof(listOf(session()), listOf(reading(end = 999))) is NetworkProof.Unavailable)
    }

    @Test fun `duplicate session readings are ambiguous`() {
        assertTrue(AuditNetworkReadings.proof(listOf(session()), listOf(reading(), reading())) is NetworkProof.Unavailable)
    }

    @Test fun `overlapping plan windows cannot double count uploads`() {
        val sessions = listOf(session(), session("plan-b", 50, 200))
        assertTrue(AuditNetworkReadings.proof(sessions, listOf(reading(), reading("plan-b", 50, 200))) is NetworkProof.Unavailable)
    }

    @Test fun `missing health never becomes measured zero DNS traffic`() {
        assertTrue(AuditNetworkReadings.proof(listOf(session().copy(captureHealth = null)), listOf(reading())) is NetworkProof.Unavailable)
    }

    @Test fun `negative residual remains visible and inconsistent`() {
        val proof = AuditNetworkReadings.proof(listOf(session(payload = 2100, datagrams = 3)), listOf(reading(txEnd = 1050))) as NetworkProof.Measured
        assertEquals(-2134L, proof.remainderBytes)
        assertEquals(ProofStatus.INCONSISTENT, proof.status)
    }

    @Test fun `UID read after VPN stops cannot be labelled with the earlier stopped window`() {
        var now = 10L
        val capture = CaptureSessions(clock = { now })
        capture.captureStarted()
        capture.upstreamSent(40)
        now = 50
        capture.captureStopped("VPN revoked")
        now = 100
        val health = capture.snapshot().health
        assertEquals(50L, health.captureEndedNanos)
        val upload = reading(end = 50, txEnd = 1500).copy(snapshotRequestedNanos = 100, txSamplingEndedNanos = 110)
        assertTrue(AuditNetworkReadings.proof(listOf(session().copy(captureHealth = health)), listOf(upload)) is NetworkProof.Unavailable)
    }

    @Test fun `restarted capture cannot explain UID bytes transmitted during its gap`() {
        var now = 10L
        val capture = CaptureSessions(clock = { now })
        capture.captureStarted()
        now = 20
        capture.captureStopped()
        now = 30
        capture.captureStarted()
        now = 100
        val health = capture.snapshot().health
        assertTrue(health.notes.isNotEmpty())
        assertTrue(AuditNetworkReadings.proof(listOf(session().copy(captureHealth = health)), listOf(reading())) is NetworkProof.Unavailable)
    }

    @Test fun `unknown sampling times cannot assert a matching interval`() {
        assertTrue(AuditNetworkReadings.proof(listOf(session()), listOf(reading().copy(txSamplingStartedNanos = null))) is NetworkProof.Unavailable)
    }

    @Test fun `UID samples must bracket a still running capture snapshot`() {
        listOf(
            reading().copy(txSamplingStartedNanos = 11),
            reading().copy(snapshotRequestedNanos = 101),
            reading().copy(txSamplingEndedNanos = 99),
        ).forEach { assertTrue(AuditNetworkReadings.proof(listOf(session()), listOf(it)) is NetworkProof.Unavailable) }
    }

    @Test fun `overlapping UID sampling windows cannot double count bytes even if captures do not overlap`() {
        val sessions = listOf(session(), session("plan-b", 200, 400))
        val first = reading().copy(txSamplingEndedNanos = 210)
        val second = reading("plan-b", 200, 400).copy(txSamplingStartedNanos = 190)
        assertTrue(AuditNetworkReadings.proof(sessions, listOf(first, second)) is NetworkProof.Unavailable)
    }

    @Test fun `unsupported packet counters do not become measured zero or fifteen packets`() {
        listOf(-1L to -1L, -1L to 14L).forEach { (start, end) ->
            val proof = AuditNetworkReadings.proof(listOf(session()), listOf(reading().copy(txPacketsStart = start, txPacketsEnd = end))) as NetworkProof.Measured
            assertEquals(224L, proof.totalTxBytes)
            assertEquals(null, proof.totalTxPackets)
            assertEquals(null, proof.packetRemainder)
        }
    }
}
