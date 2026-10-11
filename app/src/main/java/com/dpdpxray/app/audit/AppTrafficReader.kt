package com.dpdpxray.app.audit

import android.net.TrafficStats
import com.dpdpxray.app.control.ShizukuBridge
import com.dpdpxray.core.capture.SocketTable
import com.dpdpxray.core.model.AppTraffic

/**
 * The audited app's own network use during a plan, for the low-visibility diagnosis: "no lookups, but the app was
 * online" points at its own DNS or fixed addresses, "no lookups and offline" at a quiet app.
 *
 * Android's per-app byte counters are tried first; current Android versions report them only for the calling app, so
 * for another app they read as unsupported and stay unknown (null). The open-connection count comes from the kernel's
 * socket table through Shizuku, as a number only. Nothing here is sent anywhere.
 */
internal object AppTrafficReader {
    data class Mark(val rx: Long?, val tx: Long?)

    fun mark(uid: Int?): Mark = if (uid == null) Mark(null, null)
        else Mark(read { TrafficStats.getUidRxBytes(uid) }, read { TrafficStats.getUidTxBytes(uid) })

    /** The app's open connections now; null without Shizuku or when the table cannot be read. */
    suspend fun connections(uid: Int?): Int? {
        uid ?: return null
        if (!ShizukuBridge.hasPermission()) return null
        return ShizukuBridge.socketTables()?.let { SocketTable.openConnections(it, uid) }
    }

    /** What was read between [start] and now; null when nothing could be read at all (unknown, never zero). */
    fun traffic(uid: Int?, start: Mark, connections: Int?): AppTraffic? {
        val end = mark(uid)
        fun delta(a: Long?, b: Long?) = if (a != null && b != null && b >= a) b - a else null
        val rx = delta(start.rx, end.rx)
        val tx = delta(start.tx, end.tx)
        return if (rx == null && tx == null && connections == null) null else AppTraffic(rx, tx, connections)
    }

    private fun read(f: () -> Long): Long? = runCatching(f).getOrNull()?.takeIf { it >= 0 && it != TrafficStats.UNSUPPORTED.toLong() }
}
