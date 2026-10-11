package com.dpdpxray.app.quarantine

import com.dpdpxray.core.netcapture.FlowTracker
import com.dpdpxray.core.quarantine.PacketHeader
import com.dpdpxray.core.quarantine.QuarantineObservations
import com.dpdpxray.core.quarantine.QuarantineRecorder
import com.dpdpxray.core.quarantine.QuarantineStopReason
import com.dpdpxray.core.quarantine.QuarantineUiRules
import com.dpdpxray.core.quarantine.SensitiveSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Addresses of the quarantine tunnel. The resolver addresses are where Android sends the app's DNS. */
object QuarantineNet {
    const val TUNNEL_V4 = "10.111.0.2"
    const val RESOLVER_V4 = "10.111.0.53"
    const val TUNNEL_V6 = "fd00:111::2"
    const val RESOLVER_V6 = "fd00:111::53"
    val RESOLVERS: Set<String> = setOf(RESOLVER_V4, RESOLVER_V6)
}

/** State of the quarantine tunnel, always tagged with the run it belongs to. */
sealed interface QuarantineTunnel {
    data object Idle : QuarantineTunnel
    data class Starting(val runId: Long) : QuarantineTunnel
    data class Running(val runId: Long, val targetPackage: String) : QuarantineTunnel

    /** [reason] null: stopped because the run asked for it. [message] explains a failure to start. */
    data class Ended(val runId: Long, val reason: QuarantineStopReason?, val message: String? = null) : QuarantineTunnel
}

/**
 * Process-wide hand-off between the VPN service (packets), the accessibility service (dialogs/settings screens), the
 * runner and the UI for ONE quarantine run at a time. Everything carries the run id, so a late packet or a slow tunnel
 * shutdown from an earlier run can never change a newer one. Pure Kotlin: no Android calls.
 */
object QuarantineBus {
    private class ActiveRun(val id: Long, val targetPackage: String, val targetLabel: String, val recorder: QuarantineRecorder) {
        /** Connections the tunnel answered on the phone (sinkhole relay); never forwarded. */
        val flows = FlowTracker(maxFlows = MAX_CONNECTIONS)

        fun observations() = recorder.snapshot().copy(connections = flows.snapshot(), connectionsRead = true)
    }

    /** At most this many answered connections per run; later ones are still blocked and counted as packets. */
    const val MAX_CONNECTIONS = 500

    private val lock = Any()
    private var nextRunId = 1L
    private var active: ActiveRun? = null

    private val _tunnel = MutableStateFlow<QuarantineTunnel>(QuarantineTunnel.Idle)
    val tunnel: StateFlow<QuarantineTunnel> = _tunnel.asStateFlow()

    private val _live = MutableStateFlow(QuarantineObservations())

    /** The current run's observations, refreshed by [publish] (the runner calls it a few times a second). */
    val live: StateFlow<QuarantineObservations> = _live.asStateFlow()

    /** The package whose screens and dialogs are being watched, or null outside a quarantine run. */
    @Volatile
    var watchedPackage: String? = null
        private set

    /** Starts recording a new run and returns its id. Any earlier run stops recording. */
    fun open(targetPackage: String, targetLabel: String): Long = synchronized(lock) {
        val id = nextRunId++
        active = ActiveRun(id, targetPackage, targetLabel, QuarantineRecorder(QuarantineNet.RESOLVERS))
        watchedPackage = targetPackage
        _live.value = QuarantineObservations()
        _tunnel.value = QuarantineTunnel.Starting(id)
        id
    }

    /** Stops recording [runId] and returns what it saw; null if that run is not the active one. */
    fun close(runId: Long): QuarantineObservations? = synchronized(lock) {
        val run = active?.takeIf { it.id == runId } ?: return@synchronized null
        active = null
        watchedPackage = null
        val final = run.observations()
        _live.value = final
        if (_tunnel.value.let { it is QuarantineTunnel.Starting || it is QuarantineTunnel.Running }) {
            _tunnel.value = QuarantineTunnel.Ended(runId, null)
        }
        final
    }

    fun isCurrent(runId: Long): Boolean = synchronized(lock) { active?.id == runId }

    fun tunnelRunning(runId: Long, targetPackage: String) = synchronized(lock) {
        if (active?.id == runId && _tunnel.value == QuarantineTunnel.Starting(runId)) {
            _tunnel.value = QuarantineTunnel.Running(runId, targetPackage)
        }
    }

    /** The tunnel for [runId] went down (or never came up). Only the first end of a live tunnel is kept. */
    fun tunnelEnded(runId: Long, reason: QuarantineStopReason?, message: String? = null): Boolean = synchronized(lock) {
        val t = _tunnel.value
        val live = (t is QuarantineTunnel.Starting && t.runId == runId) || (t is QuarantineTunnel.Running && t.runId == runId)
        if (live) _tunnel.value = QuarantineTunnel.Ended(runId, reason, message)
        live
    }

    fun lookup(runId: Long, tNanos: Long, hostname: String, queryType: Int, resolver: String) {
        recorderFor(runId)?.recordDns(tNanos, hostname, queryType, resolver)
    }

    fun dropped(runId: Long, tNanos: Long, header: PacketHeader?) {
        recorderFor(runId)?.recordPacket(tNanos, header)
    }

    /** The connection tracker of [runId] while it is the active run, for the tunnel's sinkhole relay. */
    fun flowTracker(runId: Long): FlowTracker? = synchronized(lock) { active?.takeIf { it.id == runId }?.flows }

    /** A permission-controller dialog's visible text while the current run is active. */
    fun permissionDialog(tNanos: Long, controllerPackage: String, text: String): Boolean {
        val run = synchronized(lock) { active } ?: return false
        val names = QuarantineUiRules.namesTarget(text, run.targetLabel, run.targetPackage)
        return run.recorder.recordPermissionDialog(tNanos, controllerPackage, text, names)
    }

    fun settingsScreen(tNanos: Long, kind: SensitiveSettings, title: String): Boolean =
        synchronized(lock) { active }?.recorder?.recordSettingsScreen(tNanos, kind, title) ?: false

    fun targetOnScreen(tNanos: Long) {
        synchronized(lock) { active }?.recorder?.recordTargetOnScreen(tNanos)
    }

    /** Copies the current run's observations into [live]. */
    fun publish() {
        val run = synchronized(lock) { active } ?: return
        _live.value = run.observations()
    }

    private fun recorderFor(runId: Long): QuarantineRecorder? = synchronized(lock) { active?.takeIf { it.id == runId }?.recorder }
}
