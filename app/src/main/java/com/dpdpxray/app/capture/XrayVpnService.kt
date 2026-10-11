package com.dpdpxray.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.dpdpxray.app.quarantine.QuarantineBus
import com.dpdpxray.app.quarantine.QuarantineNet
import com.dpdpxray.core.dns.DnsAnswers
import com.dpdpxray.core.dns.DnsPacketCodec
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.netcapture.SinkholeConnector
import com.dpdpxray.core.netcapture.TunRelay
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import com.dpdpxray.core.quarantine.IpAddresses
import com.dpdpxray.core.quarantine.QuarantineStopReason
import com.dpdpxray.core.quarantine.SinkholeTap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * A per-app, DNS-only local VPN. Only the audited app (and optionally Google Play services) is routed in, and only
 * traffic to a fake resolver address enters the tunnel, so the app's real traffic is untouched. Every DNS question is
 * timestamped with the same clock as the consent watcher, then relayed to the real resolver. Answers go back with
 * TTL 0 so Android never caches them: every repeat lookup during the audit stays visible.
 */
class XrayVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var scope: CoroutineScope? = null

    /**
     * The relay of the current tunnel: forwarding in a full-relay audit, a sinkhole (answers on the phone, forwards
     * nothing) in quarantine; null in a DNS-only audit.
     */
    private var relay: TunRelay? = null

    /** The tunnel opened in quarantine mode, and its run; an audit START replaces [tun] and leaves this stale. */
    private var quarantineTun: ParcelFileDescriptor? = null
    private var quarantineRunId = -1L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val pkg = intent.getStringExtra(EXTRA_TARGET) ?: return START_NOT_STICKY
                startForegroundNotice(pkg)
                startCapture(pkg, intent.getBooleanExtra(EXTRA_INCLUDE_GMS, false), intent.getBooleanExtra(EXTRA_FULL_RELAY, false))
            }
            ACTION_STOP -> {
                stopTunnel()
                CaptureBus.setState(CaptureState.Idle)
                stopForeground(STOP_FOREGROUND_REMOVE)
                // Only stop if no newer START arrived after this STOP was sent.
                stopSelfResult(startId)
            }
            ACTION_QUARANTINE -> {
                val pkg = intent.getStringExtra(EXTRA_TARGET) ?: return START_NOT_STICKY
                val runId = intent.getLongExtra(EXTRA_RUN_ID, -1L)
                startQuarantineNotice(intent.getStringExtra(EXTRA_LABEL) ?: pkg)
                startQuarantine(pkg, runId)
            }
            ACTION_QUARANTINE_STOP -> {
                // Only the quarantine tunnel is ever closed here, never an audit's capture tunnel.
                val ended = endQuarantine(if (intent.getBooleanExtra(EXTRA_BY_USER, false)) QuarantineStopReason.USER else null)
                if (ended || synchronized(this) { tun == null }) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Quarantine mode: ALL traffic of [pkg] (IPv4 and IPv6) enters the tunnel and nothing is ever forwarded. DNS
     * questions are recorded and answered locally with NXDOMAIN; every other packet is recorded from its IP/transport
     * headers (destination, port, protocol). IPv4 TCP connections and QUIC datagrams are also answered on the phone by
     * a sinkhole relay ([SinkholeConnector]), which reads only the first message (TLS or QUIC server name, redacted
     * HTTP request head) and closes the connection; everything else is dropped. Only this one app loses its internet;
     * closing the tunnel restores it.
     */
    private fun startQuarantine(pkg: String, runId: Long) {
        stopTunnel()
        val builder = Builder()
            .setSession("TraceVeil quarantine · $pkg")
            .addAddress(QuarantineNet.TUNNEL_V4, 32)
            .addAddress(QuarantineNet.TUNNEL_V6, 128)
            .addDnsServer(QuarantineNet.RESOLVER_V4)
            .addDnsServer(QuarantineNet.RESOLVER_V6)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .setMtu(MTU)
            .setBlocking(true)
        try {
            builder.addAllowedApplication(pkg)
        } catch (e: Exception) {
            QuarantineBus.tunnelEnded(runId, QuarantineStopReason.FAILED, "App not found: $pkg")
            stopSelf()
            return
        }
        val fd = runCatching { builder.establish() }.getOrNull()
        if (fd == null) {
            QuarantineBus.tunnelEnded(runId, QuarantineStopReason.FAILED, "VPN permission missing or another VPN is active")
            stopSelf()
            return
        }
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val output = FileOutputStream(fd.fileDescriptor)
        val sinkhole = QuarantineBus.flowTracker(runId)?.let { tracker ->
            TunRelay(
                toTun = { pkt -> synchronized(output) { runCatching { output.write(pkt) } } },
                connector = SinkholeConnector(),
                tracker = tracker,
                clock = SystemClock::elapsedRealtimeNanos,
                executor = Dispatchers.IO.asExecutor(),
                maxTcpFlows = SINKHOLE_MAX_TCP,
            )
        }
        synchronized(this) {
            tun = fd
            scope = s
            relay = sinkhole
            quarantineTun = fd
            quarantineRunId = runId
        }
        QuarantineBus.tunnelRunning(runId, pkg)
        if (sinkhole != null) s.launch { while (isActive) { delay(15_000); sinkhole.sweep(SINKHOLE_IDLE_NANOS) } }
        s.launch { quarantineLoop(fd, s, runId, sinkhole, output) }
    }

    private fun quarantineLoop(fd: ParcelFileDescriptor, s: CoroutineScope, runId: Long, sinkhole: TunRelay?, output: FileOutputStream) {
        val input = FileInputStream(fd.fileDescriptor)
        val tap = SinkholeTap(sinkhole, QuarantineNet.RESOLVERS) { t, header -> QuarantineBus.dropped(runId, t, header) }
        val buffer = ByteArray(32767)
        try {
            while (s.isActive) {
                val n = try {
                    input.read(buffer)
                } catch (_: Exception) {
                    break
                }
                if (n < 0) break
                if (n == 0) continue
                val t = SystemClock.elapsedRealtimeNanos()
                val query = DnsPacketCodec.parse(buffer, n)
                if (query != null) {
                    QuarantineBus.lookup(runId, t, query.hostname, query.queryType, IpAddresses.format(query.dstAddress))
                    // Answered here, never forwarded: the name "does not exist" for this app during the run.
                    val answer = DnsPacketCodec.buildResponse(query, DnsPacketCodec.nxDomain(query.dnsPayload))
                    synchronized(output) { runCatching { output.write(answer) } }
                } else {
                    // Recorded from its IP/transport headers; IPv4 connections are also answered on the phone (never
                    // forwarded) so their first message names the server. Everything else is dropped.
                    tap.onPacket(t, buffer, n)
                }
            }
        } finally {
            // Ignored when the run already ended on request; otherwise the tunnel closed underneath the run.
            QuarantineBus.tunnelEnded(runId, QuarantineStopReason.TUNNEL_CLOSED)
        }
    }

    /** Closes the quarantine tunnel if it is the current one. Returns false when another tunnel (an audit) is current. */
    @Synchronized
    private fun endQuarantine(reason: QuarantineStopReason?): Boolean {
        val q = quarantineTun ?: return false
        quarantineTun = null
        if (tun !== q) return false
        QuarantineBus.tunnelEnded(quarantineRunId, reason)
        stopTunnel()
        return true
    }

    private fun startCapture(pkg: String, includeGms: Boolean, fullRelay: Boolean) {
        stopTunnel()
        val builder = Builder()
            .setSession("TraceVeil · $pkg")
            .addAddress(VPN_ADDRESS, 32)
            .addDnsServer(DNS_ADDRESS)
            // DNS-only: just the fake resolver enters the tunnel. Full relay: all IPv4 of the audited app does, and is
            // forwarded unchanged (IPv6 is not routed, so it keeps flowing directly and is not captured).
            .apply { if (fullRelay) addRoute("0.0.0.0", 0) else addRoute(DNS_ADDRESS, 32) }
            .setMtu(MTU)
            .setBlocking(true)
        try {
            builder.addAllowedApplication(pkg)
            if (includeGms) runCatching { builder.addAllowedApplication(GMS_PACKAGE) }
        } catch (e: Exception) {
            CaptureBus.setState(CaptureState.Error("App not found: $pkg"))
            stopSelf()
            return
        }
        val fd = runCatching { builder.establish() }.getOrNull()
        if (fd == null) {
            CaptureBus.setState(CaptureState.Error("VPN permission missing or another VPN is active"))
            stopSelf()
            return
        }
        val forwarder = DnsForwarder(this) { protect(it) }
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val out = FileOutputStream(fd.fileDescriptor)
        val r = if (fullRelay) {
            TunRelay(
                toTun = { pkt -> synchronized(out) { runCatching { out.write(pkt) } } },
                connector = SocketConnector({ protect(it) }, { protect(it) }),
                tracker = CaptureBus.flowTracker,
                clock = SystemClock::elapsedRealtimeNanos,
                executor = Dispatchers.IO.asExecutor(),
            )
        } else null
        synchronized(this) {
            tun = fd
            scope = s
            relay = r
        }
        CaptureBus.setState(CaptureState.Running(pkg))
        if (r != null) s.launch { while (isActive) { delay(15_000); r.sweep() } }
        s.launch { readLoop(fd, forwarder, s, r, out) }
    }

    private fun readLoop(fd: ParcelFileDescriptor, forwarder: DnsForwarder, s: CoroutineScope, relay: TunRelay?, output: FileOutputStream) {
        val input = FileInputStream(fd.fileDescriptor)
        val buffer = ByteArray(32767)
        while (s.isActive) {
            val n = try {
                input.read(buffer)
            } catch (_: Exception) {
                break
            }
            if (n <= 0) continue
            val query = DnsPacketCodec.parse(buffer, n)
            if (relay != null && (query == null || !query.dstAddress.contentEquals(FAKE_DNS_BYTES))) {
                // Everything except lookups to our own resolver address is forwarded unchanged. A lookup sent straight
                // to another resolver is recorded too (it is evidence), but not answered or altered here.
                if (query != null) CaptureBus.record(NetEvent(tNanos = SystemClock.elapsedRealtimeNanos(), hostname = query.hostname, queryType = query.queryType))
                if (!relay.onPacket(buffer, n)) CaptureBus.malformedPacket(DnsPacketCodec.classifyUnparsed(buffer, n))
                continue
            }
            if (query == null) {
                CaptureBus.malformedPacket(DnsPacketCodec.classifyUnparsed(buffer, n))
                continue
            }
            // The ticket ties the reply to this session: after a reset, a late reply is ignored.
            val ticket = CaptureBus.record(NetEvent(tNanos = SystemClock.elapsedRealtimeNanos(), hostname = query.hostname, queryType = query.queryType))
            s.launch {
                val reply = forwarder.resolve(query.dnsPayload)
                val answer = reply?.let { DnsPacketCodec.zeroTtls(it) } ?: DnsPacketCodec.servFail(query.dnsPayload)
                val packet = DnsPacketCodec.buildResponse(query, answer)
                synchronized(output) {
                    runCatching { output.write(packet) }
                }
                // After the app has its answer, so reading the records never delays the lookup.
                if (reply != null) CaptureBus.replyReceived(ticket, DnsAnswers.parse(reply, query.dnsPayload))
                else CaptureBus.relayFailed(ticket)
            }
        }
    }

    /** Called from the main thread (commands) and a binder thread (onRevoke), hence synchronized. */
    @Synchronized
    private fun stopTunnel() {
        relay?.closeAll()
        relay = null
        scope?.cancel()
        scope = null
        runCatching { tun?.close() }
        tun = null
    }

    override fun onRevoke() {
        if (endQuarantine(QuarantineStopReason.VPN_REVOKED)) {
            stopSelf()
            return
        }
        stopTunnel()
        CaptureBus.setState(CaptureState.Error("VPN was turned off by the system or the user"))
        stopSelf()
    }

    override fun onDestroy() {
        endQuarantine(QuarantineStopReason.TUNNEL_CLOSED)
        stopTunnel()
        if (CaptureBus.state.value is CaptureState.Running) CaptureBus.setState(CaptureState.Idle)
        super.onDestroy()
    }

    private fun startForegroundNotice(pkg: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Audit capture", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("TraceVeil is auditing")
            .setContentText(if (CaptureBus.deepCapture) "Forwarding and watching connections of $pkg · TraceVeil sends nothing of its own" else "Watching DNS lookups of $pkg · nothing is uploaded")
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    private fun startQuarantineNotice(label: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(QUARANTINE_CHANNEL, "Quarantine run", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, XrayVpnService::class.java).setAction(ACTION_QUARANTINE_STOP).putExtra(EXTRA_BY_USER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 2, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n = Notification.Builder(this, QUARANTINE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Quarantine: $label has no internet while TraceVeil watches")
            .setContentText("Stopping gives it its internet back. Nothing is uploaded.")
            .setOngoing(true)
            .apply { if (open != null) setContentIntent(open) }
            .addAction(Notification.Action.Builder(null, "Stop quarantine", stop).build())
            .build()
        startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    companion object {
        private const val ACTION_START = "com.dpdpxray.capture.START"
        private const val ACTION_STOP = "com.dpdpxray.capture.STOP"
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_INCLUDE_GMS = "gms"
        private const val EXTRA_FULL_RELAY = "fullRelay"
        private val FAKE_DNS_BYTES = byteArrayOf(10, 111, 0, 53)
        private const val SINKHOLE_MAX_TCP = 128
        private const val SINKHOLE_IDLE_NANOS = 30_000_000_000L
        private const val VPN_ADDRESS = "10.111.0.2"
        private const val DNS_ADDRESS = "10.111.0.53"
        private const val MTU = 1500
        private const val CHANNEL = "capture"
        private const val NOTIFICATION_ID = 7
        const val GMS_PACKAGE = "com.google.android.gms"
        private const val ACTION_QUARANTINE = "com.dpdpxray.capture.QUARANTINE"
        private const val ACTION_QUARANTINE_STOP = "com.dpdpxray.capture.QUARANTINE_STOP"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_RUN_ID = "runId"
        private const val EXTRA_BY_USER = "byUser"
        private const val QUARANTINE_CHANNEL = "quarantine"

        fun start(context: Context, targetPackage: String, includeGms: Boolean, fullRelay: Boolean = CaptureBus.deepCapture) {
            CaptureBus.setState(CaptureState.Starting)
            context.startForegroundService(
                Intent(context, XrayVpnService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_TARGET, targetPackage)
                    .putExtra(EXTRA_INCLUDE_GMS, includeGms)
                    .putExtra(EXTRA_FULL_RELAY, fullRelay),
            )
        }

        fun stop(context: Context) {
            if (CaptureBus.state.value is CaptureState.Idle) return
            context.startService(Intent(context, XrayVpnService::class.java).setAction(ACTION_STOP))
        }

        /** Starts a quarantine run's tunnel for [targetPackage]; progress is reported on [QuarantineBus.tunnel]. */
        fun startQuarantine(context: Context, targetPackage: String, targetLabel: String, runId: Long) {
            context.startForegroundService(
                Intent(context, XrayVpnService::class.java)
                    .setAction(ACTION_QUARANTINE)
                    .putExtra(EXTRA_TARGET, targetPackage)
                    .putExtra(EXTRA_LABEL, targetLabel)
                    .putExtra(EXTRA_RUN_ID, runId),
            )
        }

        /** Closes the quarantine tunnel (never an audit's), giving the app its normal networking back. */
        fun stopQuarantine(context: Context) {
            context.startService(Intent(context, XrayVpnService::class.java).setAction(ACTION_QUARANTINE_STOP))
        }
    }
}
