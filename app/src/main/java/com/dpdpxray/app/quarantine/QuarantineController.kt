package com.dpdpxray.app.quarantine

import android.content.Context
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import com.dpdpxray.app.audit.AuditProgress
import com.dpdpxray.app.capture.CaptureBus
import com.dpdpxray.app.capture.CaptureState
import com.dpdpxray.app.capture.XrayVpnService
import com.dpdpxray.app.control.DeviceChecks
import com.dpdpxray.app.control.ShizukuBridge
import com.dpdpxray.app.graph
import com.dpdpxray.core.quarantine.QuarantineObservations
import com.dpdpxray.core.quarantine.QuarantineReport
import com.dpdpxray.core.quarantine.QuarantineReports
import com.dpdpxray.core.quarantine.QuarantineStopReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

sealed interface QuarantineRunState {
    data object Idle : QuarantineRunState

    /** [startedAtNanos] is set once the tunnel is up (elapsedRealtimeNanos), before the app is opened. */
    data class Running(
        val runId: Long,
        val targetPackage: String,
        val targetLabel: String,
        val step: String,
        val plannedMs: Long,
        val startedAtNanos: Long? = null,
    ) : QuarantineRunState

    data class Finished(val report: QuarantineReport, val saved: Boolean, val path: String) : QuarantineRunState
    data class Failed(val targetPackage: String, val message: String) : QuarantineRunState
}

/**
 * Runs one user-started quarantine: tunnel up → open the app → watch for the planned time or until Stop → tunnel down
 * (normal networking returns) → save the behaviour report. Never starts by itself, never taps, never forwards traffic.
 * Runs in its own scope so it survives the UI going to the background while the person uses the quarantined app.
 */
object QuarantineController {
    const val DEFAULT_DURATION_MS = 60_000L
    val DURATIONS_MS = listOf(60_000L, 120_000L, 300_000L)
    private const val TUNNEL_START_TIMEOUT_MS = 6_000L
    private const val TICK_MS = 250L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<QuarantineRunState>(QuarantineRunState.Idle)
    val state: StateFlow<QuarantineRunState> = _state.asStateFlow()

    @Volatile
    private var stopRequested = false

    val isActive: Boolean get() = _state.value is QuarantineRunState.Running

    /** Returns null when the run started, otherwise why it could not. */
    @Synchronized
    fun start(context: Context, targetPackage: String, targetLabel: String, durationMs: Long = DEFAULT_DURATION_MS): String? {
        val app = context.applicationContext
        if (isActive) return "A quarantine run is already active. Stop it first."
        if (!QuarantineRoutes.isValidPackage(targetPackage) || targetPackage == app.packageName) return "This app cannot be quarantined."
        if (com.dpdpxray.app.control.DeviceChecks.otherVpnActive(app)) return com.dpdpxray.app.ui.AuditStartReadiness.OTHER_VPN
        if (VpnService.prepare(app) != null) return "Allow the VPN permission first."
        val capture = CaptureBus.state.value
        if (capture is CaptureState.Running || capture is CaptureState.Starting || app.graph.orchestrator.progress.value is AuditProgress.Running) {
            return "An audit is running. Let it finish or stop it before a quarantine run."
        }
        stopRequested = false
        val runId = QuarantineBus.open(targetPackage, targetLabel)
        _state.value = QuarantineRunState.Running(runId, targetPackage, targetLabel, "Cutting off $targetLabel's internet", durationMs)
        scope.launch { run(app, runId, targetPackage, targetLabel, durationMs) }
        return null
    }

    /** Ends the run now; the report is still saved. */
    fun stop() {
        stopRequested = true
    }

    /** Clears a finished or failed run from the screen (the saved report stays on the phone). */
    fun acknowledge() {
        if (_state.value !is QuarantineRunState.Running) _state.value = QuarantineRunState.Idle
    }

    fun store(context: Context) = QuarantineStore(File(context.applicationContext.filesDir, QuarantineStore.DIR))

    private suspend fun run(app: Context, runId: Long, pkg: String, label: String, durationMs: Long) {
        val epoch = System.currentTimeMillis()
        val notes = mutableListOf<String>()
        var t0: Long? = null
        try {
            XrayVpnService.startQuarantine(app, pkg, label, runId)
            val up = withTimeoutOrNull(TUNNEL_START_TIMEOUT_MS) {
                QuarantineBus.tunnel.first { (it is QuarantineTunnel.Running && it.runId == runId) || (it is QuarantineTunnel.Ended && it.runId == runId) }
            }
            if (up !is QuarantineTunnel.Running) {
                val why = (up as? QuarantineTunnel.Ended)?.message ?: "The quarantine tunnel did not start. Check the VPN permission and that no other VPN is always-on."
                fail(app, runId, pkg, why)
                return
            }
            val start = SystemClock.elapsedRealtimeNanos()
            t0 = start
            step(runId, pkg, label, durationMs, start, "Opening $label")
            if (!launch(app, pkg)) {
                notes += "TraceVeil could not open the app (it may have no launcher screen). Anything it did on its own while quarantined was still recorded."
            }
            step(runId, pkg, label, durationMs, start, "Watching $label · its internet is cut off")
            val reason = watch(runId, start, durationMs)
            val end = SystemClock.elapsedRealtimeNanos()
            finish(app, runId, pkg, label, epoch, start, end, durationMs, reason, notes)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { teardown(app, runId) }
            throw e
        } catch (e: Exception) {
            if (t0 == null) fail(app, runId, pkg, e.message ?: e.javaClass.simpleName)
            else finish(app, runId, pkg, label, epoch, t0, SystemClock.elapsedRealtimeNanos(), durationMs, QuarantineStopReason.FAILED,
                notes + "The run stopped early: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun watch(runId: Long, start: Long, durationMs: Long): QuarantineStopReason {
        while (true) {
            val tunnel = QuarantineBus.tunnel.value
            if (tunnel is QuarantineTunnel.Ended && tunnel.runId == runId) return tunnel.reason ?: QuarantineStopReason.USER
            if (stopRequested) return QuarantineStopReason.USER
            if ((SystemClock.elapsedRealtimeNanos() - start) / 1_000_000 >= durationMs) return QuarantineStopReason.TIMER
            QuarantineBus.publish()
            delay(TICK_MS)
        }
    }

    private suspend fun finish(
        app: Context, runId: Long, pkg: String, label: String, epoch: Long, start: Long, end: Long, durationMs: Long,
        reason: QuarantineStopReason, notes: List<String>,
    ) {
        val observations = teardown(app, runId) ?: run {
            _state.value = QuarantineRunState.Failed(pkg, "The run's records were no longer available; nothing was saved.")
            return
        }
        val report = QuarantineReports.build(
            id = "quarantine-$epoch", targetPackage = pkg, targetLabel = label,
            startedAtEpochMs = epoch, startedAtNanos = start, endedAtNanos = end, plannedDurationMs = durationMs,
            stopReason = reason, resolverAddresses = QuarantineNet.RESOLVERS, observations = observations, notes = notes,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}", androidVersion = Build.VERSION.RELEASE,
            privateDnsActive = runCatching { DeviceChecks.privateDnsActive(app) }.getOrNull(),
        )
        val store = store(app)
        val saved = store.save(report)
        _state.value = QuarantineRunState.Finished(report, saved, store.fileFor(report.id).absolutePath)
        returnToXray(app)
    }

    /** Closes the tunnel (normal networking returns) and stops recording. */
    private fun teardown(app: Context, runId: Long): QuarantineObservations? {
        runCatching { XrayVpnService.stopQuarantine(app) }
        return QuarantineBus.close(runId)
    }

    private fun fail(app: Context, runId: Long, pkg: String, message: String) {
        teardown(app, runId)
        _state.value = QuarantineRunState.Failed(pkg, message)
    }

    private fun step(runId: Long, pkg: String, label: String, plannedMs: Long, start: Long, text: String) {
        _state.value = QuarantineRunState.Running(runId, pkg, label, text, plannedMs, start)
    }

    /** The same launch path as audits: Shizuku's shell when authorised, otherwise a normal launch intent. */
    private suspend fun launch(app: Context, pkg: String): Boolean {
        if (ShizukuBridge.hasPermission()) {
            val component = app.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString()
            if (component != null && ShizukuBridge.exec("am", "start", "-n", component).isSuccess) return true
        }
        return withContext(Dispatchers.Main) { DeviceChecks.launch(app, pkg) }
    }

    private fun returnToXray(app: Context) {
        scope.launch {
            val own = app.packageManager.getLaunchIntentForPackage(app.packageName)?.component?.flattenToShortString()
            val viaShell = own != null && ShizukuBridge.hasPermission() && ShizukuBridge.exec("am", "start", "-n", own).isSuccess
            if (!viaShell) DeviceChecks.bringXrayToFront(app)
        }
    }
}
