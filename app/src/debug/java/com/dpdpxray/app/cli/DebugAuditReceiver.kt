package com.dpdpxray.app.cli

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dpdpxray.app.audit.AuditConfig
import com.dpdpxray.app.audit.AuditProgress
import com.dpdpxray.app.control.DeviceChecks
import com.dpdpxray.app.graph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Debug-only entry point for `traceveil audit`: `adb shell am broadcast -a com.dpdpxray.app.debug.RUN_AUDIT ...`.
 * Starts the normal [com.dpdpxray.app.audit.AuditOrchestrator], mirrors its progress into `status.json` and writes
 * `report.json` under `<external files>/reports/<request_id>/`. Uploads nothing and never force-stops any app.
 */
class DebugAuditReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CliAuditRequest.ACTION) return
        val parsed = CliAuditRequest.parse(
            intent.getStringExtra(CliAuditRequest.EXTRA_REQUEST_ID), intent.getStringExtra(CliAuditRequest.EXTRA_PACKAGE),
            intent.getStringExtra(CliAuditRequest.EXTRA_PLANS), intent.getStringExtra(CliAuditRequest.EXTRA_AUTO_DRIVE),
            intent.getStringExtra(CliAuditRequest.EXTRA_RESET),
        )
        val request = (parsed as? CliAuditRequest.Parsed.Ok)?.request
            ?: return reject(parsed.reason())
        val app = context.applicationContext
        val root = app.getExternalFilesDir(null)?.let { java.io.File(it, "reports") } ?: return reject("external files directory unavailable")
        val store = CliReportStore(root)
        val g = app.graph

        val problem = when {
            app.packageManager.getLaunchIntentForPackage(request.targetPackage) == null -> "${request.targetPackage} is not installed or has no launcher activity"
            !DeviceChecks.vpnReady(app) -> "VPN permission not granted: open TraceVeil once and allow it"
            !DeviceChecks.accessibilityOn(app) -> "TraceVeil accessibility service is off: enable it in Accessibility settings"
            g.orchestrator.progress.value is AuditProgress.Running -> "another audit is already running"
            else -> null
        }
        if (problem != null) {
            runCatching { store.write(request.requestId, CliAuditReport.STATUS_FILE, CliAuditReport.status(request.requestId, "failed", error = problem)) }
            return reject(problem)
        }
        runCatching { store.write(request.requestId, CliAuditReport.STATUS_FILE, CliAuditReport.status(request.requestId, "accepted")) }
            .onFailure { return reject("cannot write status: ${it.message}") }

        val label = runCatching {
            val pm = app.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(request.targetPackage, 0)).toString()
        }.getOrDefault(request.targetPackage)

        g.scope.launch {
            val collector = launch {
                g.orchestrator.progress.collect { p ->
                    if (p is AuditProgress.Running) {
                        runCatching { store.write(request.requestId, CliAuditReport.STATUS_FILE, CliAuditReport.status(request.requestId, "running", step = p.step)) }
                    }
                }
            }
            g.orchestrator.start(AuditConfig(request.targetPackage, label, request.plans, autoDrive = request.autoDrive, resetAppData = request.resetAppData))
            val end = g.orchestrator.progress.first { it is AuditProgress.Done || it is AuditProgress.Failed || it is AuditProgress.Idle }
            collector.cancel()
            runCatching {
                when (end) {
                    is AuditProgress.Done -> {
                        val bundle = g.repository.load(end.auditId)
                        if (bundle == null) {
                            store.write(request.requestId, CliAuditReport.STATUS_FILE, CliAuditReport.status(request.requestId, "failed", auditId = end.auditId, error = "saved audit could not be read"))
                        } else {
                            store.write(request.requestId, CliAuditReport.REPORT_FILE, CliAuditReport.build(request.requestId, bundle, g.catalog()))
                            store.write(request.requestId, CliAuditReport.STATUS_FILE, CliAuditReport.status(request.requestId, "done", auditId = end.auditId))
                        }
                    }
                    is AuditProgress.Failed -> store.write(request.requestId, CliAuditReport.STATUS_FILE, CliAuditReport.status(request.requestId, "failed", error = end.message))
                    else -> store.write(request.requestId, CliAuditReport.STATUS_FILE, CliAuditReport.status(request.requestId, "failed", error = "audit was cancelled"))
                }
            }
            if (end is AuditProgress.Done || end is AuditProgress.Failed) g.orchestrator.acknowledge()
        }
        setResult(CliAuditRequest.RESULT_ACCEPTED, "accepted", null)
    }

    private fun reject(reason: String) = setResult(CliAuditRequest.RESULT_REJECTED, reason, null)

    private fun CliAuditRequest.Parsed.reason() = (this as CliAuditRequest.Parsed.Rejected).reason
}
