package com.dpdpxray.app.automation

import android.content.Context
import android.content.Intent
import com.dpdpxray.app.AppGraph
import com.dpdpxray.app.BuildConfig
import com.dpdpxray.app.audit.AuditConfig
import com.dpdpxray.app.audit.AuditProgress
import com.dpdpxray.app.control.DeviceChecks
import com.dpdpxray.app.control.ShizukuBridge
import com.dpdpxray.app.evidence.AndroidEvidenceKey
import com.dpdpxray.app.evidence.EvidenceFiles
import com.dpdpxray.app.graph
import com.dpdpxray.app.quarantine.QuarantineController
import com.dpdpxray.app.report.AuditJsonExport
import com.dpdpxray.core.automation.AutomationContract
import com.dpdpxray.core.automation.AutomationStatus
import com.dpdpxray.core.automation.AutomationStatuses
import com.dpdpxray.core.automation.BundleSignatures
import com.dpdpxray.core.automation.PhoneReadiness
import com.dpdpxray.core.automation.RefusalCode
import com.dpdpxray.core.automation.RunDecision
import com.dpdpxray.core.automation.RunRequest
import com.dpdpxray.core.automation.RunRequestInput
import com.dpdpxray.core.automation.RunRequests
import com.dpdpxray.core.export.AuditBundles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/** The audit a runner request starts: the same [AuditConfig] the check setup sheet builds for the same choices. */
object AutomationAudits {
    fun config(request: RunRequest, label: String, resetAppData: Boolean) = AuditConfig(
        targetPackage = request.packageName,
        targetLabel = label,
        plans = request.mode.plans,
        childFacing = request.childFacing,
        includeGms = false,
        autoDrive = request.autoTap,
        resetAppData = resetAppData,
        fastPreview = request.mode.fastPreview,
    )

    /** "Plan B · Reject (2 of 3): Waiting for a consent screen": the live screen's step, for the laptop's log. */
    fun step(p: AuditProgress.Running): String = "${p.plan.label} (${p.planIndex + 1} of ${p.config.plans.size}): ${p.step}"
}

/** Intent extras in and out. Only plain strings and booleans are read; nothing from the intent becomes a path or command. */
object AutomationIntents {
    /** Asks MainActivity to show the live screen of the check that is already running. */
    const val EXTRA_OPEN_LIVE = "com.dpdpxray.app.extra.OPEN_LIVE_CHECK"

    fun opensLive(intent: Intent?): Boolean =
        intent != null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0 && intent.getBooleanExtra(EXTRA_OPEN_LIVE, false)

    fun input(intent: Intent): RunRequestInput {
        val extras = runCatching { intent.extras }.getOrNull()
        fun text(key: String): String? = runCatching { extras?.getString(key) }.getOrNull()
        @Suppress("DEPRECATION")
        fun raw(key: String): Any? = runCatching { extras?.get(key) }.getOrNull()
        return RunRequestInput(
            token = text(AutomationContract.EXTRA_TOKEN),
            packageName = text(AutomationContract.EXTRA_PACKAGE),
            mode = text(AutomationContract.EXTRA_MODE),
            autoTap = raw(AutomationContract.EXTRA_AUTO_TAP),
            childFacing = raw(AutomationContract.EXTRA_CHILD_FACING),
            runId = text(AutomationContract.EXTRA_RUN_ID),
        )
    }
}

/**
 * Accepts or refuses one runner request and reports it in automation/<runId>/status.json. An accepted request starts
 * the normal audit through [com.dpdpxray.app.audit.AuditOrchestrator.start], exactly as the check setup sheet does;
 * a watcher in the app scope mirrors its progress into status.json and writes bundle.json when the audit is saved.
 */
object AutomationRunner {
    private const val KEEP_RUNS = 20
    private const val SHIZUKU_WAIT_MS = 3_000L

    /** Call on the main thread while the runner screen is in the foreground (the audit starts from there). */
    suspend fun handle(context: Context, input: RunRequestInput): RunDecision {
        // Shizuku hands its binder to a newly started process a moment after it starts. Without this short wait, the
        // first request after a cold start would read as "Shizuku not ready" (a full run refused, a quick run not reset).
        awaitShizukuBinder(SHIZUKU_WAIT_MS)
        return decide(context, input)
    }

    private suspend fun awaitShizukuBinder(timeoutMs: Long) {
        if (ShizukuBridge.isRunning()) return
        val received = CompletableDeferred<Unit>()
        val listener = Shizuku.OnBinderReceivedListener { received.complete(Unit) }
        try {
            if (runCatching { Shizuku.addBinderReceivedListenerSticky(listener) }.isFailure) return
            withTimeoutOrNull(timeoutMs) { received.await() }
        } finally {
            runCatching { Shizuku.removeBinderReceivedListener(listener) }
        }
    }

    private fun decide(context: Context, input: RunRequestInput): RunDecision {
        val app = context.applicationContext
        val graph = app.graph
        val files = AutomationFiles.forContext(app) ?: return RunDecision.Refused(
            null, RefusalCode.NO_STORAGE, "TraceVeil cannot write to its folder on this phone's shared storage.", "Check the phone's storage, then run again.",
        )
        val settings = AutomationStore.get(app).settings.value
        val readiness = PhoneReadiness(
            automationEnabled = settings.enabled,
            pairingToken = settings.token,
            ownPackage = app.packageName,
            resetAllowlist = settings.resetAllowlist,
            vpnPrepared = DeviceChecks.vpnReady(app),
            consentWatcherOn = DeviceChecks.accessibilityOn(app),
            shizukuReady = ShizukuBridge.hasPermission(),
            busy = busy(graph),
        )
        val pm = app.packageManager
        val decision = RunRequests.decide(
            input, readiness,
            isInstalled = { pkg -> runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess },
            isLaunchable = { pkg -> pm.getLaunchIntentForPackage(pkg) != null },
            runIdUsed = files::exists,
        )
        val now = System.currentTimeMillis()
        return when (decision) {
            is RunDecision.Refused -> decision.also { r ->
                if (r.runId != null) runCatching { files.writeStatus(AutomationStatuses.refused(r, input, now, BuildConfig.VERSION_NAME)) }
            }
            // Accepted means the token matched; it also signs this run's bundle.json for the laptop.
            is RunDecision.Accepted -> start(app, graph, files, decision, now, token = input.token.orEmpty())
        }
    }

    private fun busy(graph: AppGraph): String? = when {
        QuarantineController.isActive -> "a quarantine run"
        else -> (graph.orchestrator.progress.value as? AuditProgress.Running)?.let { "a check of ${it.config.targetLabel}" }
    }

    private fun start(app: Context, graph: AppGraph, files: AutomationFiles, accepted: RunDecision.Accepted, now: Long, token: String): RunDecision {
        val request = accepted.request
        val pm = app.packageManager
        val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(request.packageName, 0)).toString() }.getOrDefault(request.packageName)
        val config = AutomationAudits.config(request, label, accepted.resetAppData)
        val running = AutomationStatuses.running(request, accepted.resetAppData, now, BuildConfig.VERSION_NAME)
        // Without a status file the laptop could not follow the run, so nothing starts.
        runCatching { files.writeStatus(running) }.onFailure {
            return RunDecision.Refused(null, RefusalCode.NO_STORAGE, "TraceVeil could not write its status file: ${it.message}", "Check the phone's storage, then run again.")
        }
        runCatching { files.prune(KEEP_RUNS, request.runId) }

        graph.orchestrator.start(config)
        val after = graph.orchestrator.progress.value
        if (after is AuditProgress.Running && after.config !== config) {
            val reason = "Another check started on this phone at the same moment (${after.config.targetLabel})."
            val fix = "Let it finish or stop it on the phone, then run again."
            runCatching { files.writeStatus(AutomationStatuses.failed(running, RefusalCode.BUSY, reason, fix, System.currentTimeMillis())) }
            return RunDecision.Refused(request.runId, RefusalCode.BUSY, reason, fix)
        }
        graph.scope.launch(Dispatchers.IO) { watch(graph, files, running, config, now, token) }
        return accepted
    }

    private suspend fun watch(graph: AppGraph, files: AutomationFiles, initial: AutomationStatus, config: AuditConfig, startedMs: Long, token: String) {
        var status = initial
        try {
            val end = graph.orchestrator.progress
                .transformWhile { p -> emit(p); p is AuditProgress.Running && p.config === config }
                .onEach { p ->
                    if (p is AuditProgress.Running && p.config === config) {
                        val step = AutomationAudits.step(p)
                        if (step != status.step) {
                            status = AutomationStatuses.step(status, step, System.currentTimeMillis())
                            runCatching { files.writeStatus(status) }
                        }
                    }
                }
                .last()
            status = when (end) {
                is AuditProgress.Done -> finished(graph, files, status, end.auditId, token)
                is AuditProgress.Failed -> AutomationStatuses.failed(
                    status, RefusalCode.AUDIT_FAILED, "The check stopped on the phone: ${end.message}",
                    "Read the message on the phone, fix what it names and run again.", System.currentTimeMillis(),
                )
                // The live screen acknowledges Done at once, so a saved audit may be all that is left of it.
                else -> savedSince(graph, config.targetPackage, startedMs)?.let { finished(graph, files, status, it, token) }
                    ?: AutomationStatuses.failed(
                        status, RefusalCode.CANCELLED, "The check was stopped on the phone before it finished.",
                        "Run again and leave the phone untouched until the check finishes.", System.currentTimeMillis(),
                    )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = AutomationStatuses.failed(
                status, RefusalCode.RESULT_UNREADABLE, "TraceVeil could not report this run: ${e.message ?: e.javaClass.simpleName}",
                "The audit may still be saved on the phone; open Reports there.", System.currentTimeMillis(),
            )
        }
        runCatching { files.writeStatus(status) }
    }

    private suspend fun savedSince(graph: AppGraph, pkg: String, startedMs: Long): String? {
        graph.repository.refresh()
        return graph.repository.audits.value.firstOrNull { !it.sample && it.targetPackage == pkg && it.createdAtMs >= startedMs }?.id
    }

    /**
     * bundle.json first, then the "done" status carrying its signature (HMAC with the run's token), so "done" always
     * means the bundle is there and the laptop can tell it was written by TraceVeil for this run.
     */
    private suspend fun finished(graph: AppGraph, files: AutomationFiles, status: AutomationStatus, auditId: String, token: String): AutomationStatus {
        val bundle = graph.repository.load(auditId) ?: return AutomationStatuses.failed(
            status, RefusalCode.RESULT_UNREADABLE, "The check finished but its saved files could not be read back.",
            "Open the audit in TraceVeil (Reports) and use Export audit data (JSON).", System.currentTimeMillis(),
        )
        val written = files.writeBundle(status.runId, AuditBundles.encode(AuditJsonExport.build(bundle, BuildConfig.VERSION_NAME)))
        // Also sign the exact bytes with the phone's hardware key when it can (traceveil-ci verify reads it); the HMAC
        // below stays this run's check, and a phone that cannot sign simply writes no signature file.
        EvidenceFiles.signatureText(written, AutomationContract.BUNDLE_FILE, AndroidEvidenceKey(graph.app), BuildConfig.VERSION_NAME)
            ?.let { text -> runCatching { files.writeSignature(status.runId, text) } }
        return AutomationStatuses.done(
            status, auditId, bundle.result.score, bundle.result.band.name, bundle.result.findings.size, System.currentTimeMillis(),
            bundleSignature = BundleSignatures.sign(token, written),
        )
    }
}
