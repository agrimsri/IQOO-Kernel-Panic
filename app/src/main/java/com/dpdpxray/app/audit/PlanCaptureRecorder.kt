package com.dpdpxray.app.audit

import com.dpdpxray.app.data.SessionNetworkReadings
import com.dpdpxray.app.netproof.UidTx
import com.dpdpxray.core.capture.CaptureSnapshot

/** Keeps platform UID sampling and capture boundaries in one place. */
internal class PlanCaptureRecorder(
    private val readUid: () -> UidTx,
    private val resetCapture: () -> Unit,
    private val snapshotCapture: () -> CaptureSnapshot,
    private val clock: () -> Long,
) {
    data class Start(val tx: UidTx, val samplingStartedNanos: Long?)
    data class End(val snapshot: CaptureSnapshot, val reading: SessionNetworkReadings)

    fun start(): Start {
        val started = clock()
        val tx = readUid()
        resetCapture()
        return Start(tx, started)
    }

    fun finish(sessionId: String, start: Start): End {
        val requested = clock()
        val snapshot = snapshotCapture()
        val tx = readUid()
        val ended = clock()
        return End(snapshot, SessionNetworkReadings(
            sessionId, snapshot.health.captureStartedNanos, snapshot.health.captureEndedNanos,
            start.tx.bytes, tx.bytes, start.tx.packets, tx.packets,
            start.samplingStartedNanos, requested, ended,
        ))
    }
}
