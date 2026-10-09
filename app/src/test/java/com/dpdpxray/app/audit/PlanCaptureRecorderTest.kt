package com.dpdpxray.app.audit

import com.dpdpxray.app.data.AuditNetworkReadings
import com.dpdpxray.app.netproof.NetworkProof
import com.dpdpxray.app.netproof.UidTx
import com.dpdpxray.core.capture.CaptureSessions
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanCaptureRecorderTest {
    private fun session(end: PlanCaptureRecorder.End) = AuditSession(
        "plan-c", "demo.test", "Demo", AuditPlan.SILENT,
        captureHealth = end.snapshot.health,
    )

    @Test fun `upload sent immediately after reset is included in the UID delta`() {
        var now = 10L
        var bytes = 1000L
        val capture = CaptureSessions(clock = { now })
        capture.captureStarted()
        val recorder = PlanCaptureRecorder(
            readUid = { UidTx(bytes, null) },
            resetCapture = { capture.reset(); capture.upstreamSent(40); bytes += 68 },
            snapshotCapture = capture::snapshot,
            clock = { now },
        )
        val start = recorder.start()
        now = 100
        val end = recorder.finish("plan-c", start)
        val proof = AuditNetworkReadings.proof(listOf(session(end)), listOf(end.reading)) as NetworkProof.Measured
        assertEquals(68L, proof.totalTxBytes)
        assertEquals(68L, proof.dnsBytesOnWire)
        assertEquals(0L, proof.remainderBytes)
    }

    @Test fun `sampling timestamps preserve send uncertainty around the capture snapshot`() {
        var now = 10L
        var bytes = 1000L
        val capture = CaptureSessions(clock = { now })
        capture.captureStarted()
        val recorder = PlanCaptureRecorder(
            readUid = { now += 5; UidTx(bytes, null) },
            resetCapture = capture::reset,
            snapshotCapture = { capture.snapshot().also { now += 10; bytes += 20 } },
            clock = { now },
        )
        val start = recorder.start()
        capture.upstreamSent(40)
        bytes += 68
        now = 100
        val end = recorder.finish("plan-c", start)
        assertEquals(15L, end.reading.captureStartedNanos)
        assertEquals(100L, end.reading.captureEndedNanos)
        assertEquals(10L, end.reading.txSamplingStartedNanos)
        assertEquals(100L, end.reading.snapshotRequestedNanos)
        assertEquals(115L, end.reading.txSamplingEndedNanos)
        val proof = AuditNetworkReadings.proof(listOf(session(end)), listOf(end.reading)) as NetworkProof.Measured
        assertEquals(20L, proof.remainderBytes)
    }

    @Test fun `recorder retains stopped evidence but refuses later UID totals`() {
        var now = 10L
        var bytes = 1000L
        val capture = CaptureSessions(clock = { now })
        capture.captureStarted()
        val recorder = PlanCaptureRecorder({ UidTx(bytes, null) }, { capture.reset() }, capture::snapshot, { now })
        val start = recorder.start()
        capture.upstreamSent(40)
        bytes += 68
        now = 50
        capture.captureStopped("VPN revoked")
        bytes += 500
        now = 100
        val end = recorder.finish("plan-c", start)
        assertEquals(50L, end.snapshot.health.captureEndedNanos)
        assertEquals(1568L, end.reading.txBytesEnd)
        assertTrue(AuditNetworkReadings.proof(listOf(session(end)), listOf(end.reading)) is NetworkProof.Unavailable)
    }
}
