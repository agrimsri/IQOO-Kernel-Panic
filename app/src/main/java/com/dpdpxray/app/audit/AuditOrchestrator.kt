package com.dpdpxray.app.audit

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.dpdpxray.app.AppGraph
import com.dpdpxray.app.ai.BackendKind
import com.dpdpxray.app.ai.LlmStatus
import com.dpdpxray.app.capture.CaptureBus
import com.dpdpxray.core.netcapture.FlowPhases
import com.dpdpxray.core.netcapture.PhaseClock
import com.dpdpxray.app.capture.CaptureState
import com.dpdpxray.app.capture.XrayVpnService
import com.dpdpxray.app.consent.ConsentAccessibilityService
import com.dpdpxray.app.consent.ConsentBus
import com.dpdpxray.app.control.DeviceChecks
import com.dpdpxray.app.control.ShizukuBridge
import com.dpdpxray.app.data.AuditMeta
import com.dpdpxray.app.data.SessionNetworkReadings
import com.dpdpxray.app.netproof.UidTx
import com.dpdpxray.app.report.EvidenceReport
import com.dpdpxray.core.model.AppManifestFacts
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.report.MarkdownReport
import com.dpdpxray.core.rules.RulesEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.File

data class AuditConfig(
    val targetPackage: String,
    val targetLabel: String,
    val plans: List<AuditPlan>,
    val childFacing: Boolean = false,
    val includeGms: Boolean = false,
    val autoDrive: Boolean = false,
    /** Explicit developer-test approval; never inferred from Shizuku being available. */
    val resetAppData: Boolean = false,
    val fastPreview: Boolean = false,
) {
    init {
        require(!fastPreview || plans == listOf(AuditPlan.ACCEPT)) {
            "Fast Preview is a single Accept-plan check, not a Full audit."
        }
    }
}

sealed interface AuditProgress {
    data object Idle : AuditProgress
    data class Running(
        val config: AuditConfig,
        val planIndex: Int,
        val plan: AuditPlan,
        val step: String,
        val sessionStartNanos: Long = 0,
    ) : AuditProgress
    data class Done(val auditId: String) : AuditProgress
    /** [need] is set when a missing permission or helper stopped the check; [config] lets it start again once that is fixed. */
    data class Failed(val message: String, val need: AccessNeed? = null, val config: AuditConfig? = null) : AuditProgress
}

/**
 * Runs audit plans end to end: capture → (load AI) → per plan: reset app → launch → consent detection → AI review →
 * agent or user choice → observe → evaluate → report. Runs in the application scope so it survives the UI going to
 * the background; capture stays up for the whole audit (see [startCapture]).
 */
class AuditOrchestrator(private val context: Context, private val graph: AppGraph) {
    private val _progress = MutableStateFlow<AuditProgress>(AuditProgress.Idle)
    val progress: StateFlow<AuditProgress> = _progress.asStateFlow()
    private var job: Job? = null

    private val planCapture = PlanCaptureRecorder(UidTx::now, CaptureBus::reset, CaptureBus::snapshot, SystemClock::elapsedRealtimeNanos)

    private data class CapturedPlan(val session: AuditSession, val upload: SessionNetworkReadings)

    @Synchronized
    fun start(config: AuditConfig) {
        if (_progress.value is AuditProgress.Running) return
        // Set before launching so a double tap cannot start two audits.
        _progress.value = AuditProgress.Running(config, 0, config.plans.first(), "Starting")
        job = graph.scope.launch {
            try {
                run(config)
            } catch (e: CancellationException) {
                _progress.value = AuditProgress.Idle
            } catch (e: Exception) {
                _progress.value = AuditProgress.Failed(e.message ?: e.javaClass.simpleName, (e as? AccessNeededException)?.need, config)
                returnToXray()
            }
        }
    }

    fun cancel() {
        val j = job
        if (j != null && j.isActive) j.cancel() else _progress.value = AuditProgress.Idle
        returnToXray()
    }

    fun acknowledge() {
        if (_progress.value !is AuditProgress.Running) _progress.value = AuditProgress.Idle
    }

    private suspend fun run(config: AuditConfig) {
        val auditId = "audit-${System.currentTimeMillis()}"
        val dir = graph.repository.newAuditDir(auditId)
        val capturedPlans = try {
            // Capture starts while TraceVeil is still on screen: Android does not let a background app start a
            // foreground service, and the running capture service keeps TraceVeil allowed to work between plans.
            _progress.value = AuditProgress.Running(config, 0, config.plans.first(), "Starting per-app DNS capture")
            startCapture(config)
            if (graph.settings.settings.value.autoLoadModel && graph.llm.status.value is LlmStatus.NotLoaded) {
                _progress.value = AuditProgress.Running(config, 0, config.plans.first(), "Loading on-device AI model")
                graph.llm.load(BackendKind.GPU)
            }
            // C13: allow-listed SDK facts from the installed manifest, read once (read-only; null when unreadable).
            val manifest = withContext(Dispatchers.IO) { runCatching { ManifestFactsReader.read(context, config.targetPackage) }.getOrNull() }
            config.plans.mapIndexed { i, plan ->
                ConsentPlanRetry.capture(plan, config.targetPackage, { config.resetAppData && ShizukuBridge.hasPermission() }, { it.session }) { attempt ->
                    runPlan(config, plan, i, auditId, dir, attempt, manifest)
                }.let { attempts ->
                    if (attempts.size == 1) attempts else attempts.mapIndexed { attempt, captured ->
                        val note = if (attempt == 0) "Consent screen missed; first attempt retained and one fresh-state demo retry requested."
                            else "Bounded fresh-state demo retry (1 of 1); the failed first attempt remains in this audit."
                        captured.copy(session = captured.session.copy(notes = captured.session.notes + note))
                    }
                }
            }.flatten()
        } finally {
            runCatching { XrayVpnService.stop(context) }
            ConsentBus.disarm()
        }

        val sessions = capturedPlans.map { it.session }

        _progress.value = AuditProgress.Running(config, config.plans.lastIndex, config.plans.last(), "Evaluating DPDP checks")
        val catalog = graph.catalog()
        val result = RulesEngine(catalog).evaluate(sessions)
        val fixes = graph.fixCatalog.fixesFor(result, catalog)
        val timingProfile = AuditTimingPolicy.profile(config)
        val markdown = EvidenceReport.annotate(MarkdownReport.build(result, sessions, fixes, graph.citations()), sessions, timingProfile)
        val meta = AuditMeta(
            id = auditId, targetPackage = config.targetPackage, targetLabel = config.targetLabel,
            createdAtMs = System.currentTimeMillis(), score = result.score, band = result.band,
            findingCount = result.findings.size, plans = config.plans.map { it.label },
            timingProfile = timingProfile,
            inconclusiveReason = com.dpdpxray.core.rules.Inconclusive.reason(result.findings)?.name,
        )
        graph.repository.save(meta, sessions, result, markdown, capturedPlans.map { it.upload })
        _progress.value = AuditProgress.Done(auditId)
        returnToXray()
    }

    private suspend fun runPlan(
        config: AuditConfig, plan: AuditPlan, index: Int, auditId: String, dir: File, attempt: Int = 0, manifest: AppManifestFacts? = null,
    ): CapturedPlan = coroutineScope {
        val t = AuditTimingPolicy.forAudit(config)
        val pkg = config.targetPackage
        val sessionId = "$auditId-${plan.name}" + if (attempt == 0) "" else "-retry-$attempt"
        val notes = mutableListOf<String>()
        if (config.fastPreview) notes += AuditTimingPolicy.coverageHint(AuditTimingPolicy.FAST_PREVIEW)
        fun step(s: String, start: Long = 0) {
            _progress.value = AuditProgress.Running(config, index, plan, s, start)
        }

        step(if (config.resetAppData) "Resetting authorised test app ${config.targetLabel}" else "Keeping ${config.targetLabel}'s current data")
        var freshState = false // true only when an approved reset actually cleared the app's data
        if (config.resetAppData && ShizukuBridge.hasPermission()) {
            val stopped = ShizukuBridge.forceStop(pkg)
            if (stopped.isSuccess) ShizukuBridge.clearAppData(pkg).onSuccess { freshState = true }.onFailure { notes += "Could not clear app data: ${it.message}" }
            else notes += "Could not clear app data because stopping the test app failed: ${stopped.exceptionOrNull()?.message}"
        } else {
            notes += "App data was not cleared (reset not approved or Shizuku not authorised) — results reflect the app's current state."
        }

        check(CaptureBus.state.value is CaptureState.Running) { (CaptureBus.state.value as? CaptureState.Error)?.message ?: "Capture stopped unexpectedly" }
        val captureStart = planCapture.start()
        ConsentBus.arm(pkg, File(dir, "shots/$sessionId"))
        if (!config.autoDrive && plan != AuditPlan.SILENT) {
            ConsentBus.expectedChoice = if (plan == AuditPlan.ACCEPT) ConsentKind.ACCEPT else ConsentKind.REJECT
        }
        val t0 = SystemClock.elapsedRealtimeNanos()
        val epoch = System.currentTimeMillis()

        step("Launching ${config.targetLabel}", t0)
        check(launchTarget(pkg)) { "Cannot launch $pkg" }
        val onScreen = withTimeoutOrNull(LAUNCH_CHECK_MS) {
            while (!ConsentBus.targetSeen.value) {
                withContext(Dispatchers.Main.immediate) { ConsentAccessibilityService.instance?.probeNow() }
                if (!ConsentBus.targetSeen.value) delay(250)
            }
            true
        } ?: false
        if (!onScreen) {
            val message = "${config.targetLabel} did not come to the screen. Android may have blocked opening it from the background — start Shizuku, or keep TraceVeil open and retry."
            val need = if (ConsentAccessibilityService.instance == null) AccessNeed.WATCHER
            else AccessNeeds.shizuku(ShizukuBridge.isRunning(), ShizukuBridge.hasPermission())
            throw if (need != null) AccessNeededException(need, message) else IllegalStateException(message)
        }

        step("Waiting for a consent screen", t0)
        // A sign-in, code, CAPTCHA or fingerprint screen can stand between launch and the consent screen: skip it with a
        // harmless button when there is one, otherwise ask the person to complete it and keep watching meanwhile.
        val waited = ConsentWaitAssist(
            probe = {
                withContext(Dispatchers.Main.immediate) { ConsentAccessibilityService.instance?.probeNow() }
                ConsentBus.events.value.firstOrNull { it.kind == ConsentKind.SCREEN_APPEARED || it.kind == ConsentKind.MANUAL_MARK }
            },
            detectBlocker = { withContext(Dispatchers.Main.immediate) { ConsentAccessibilityService.instance?.blockerNow() } },
            // A skip button first; failing that, a profile form is completed with test values (never identifying fields).
            trySkip = { withContext(Dispatchers.Main.immediate) { ConsentAccessibilityService.instance?.let { it.trySkipBlocker() ?: it.fillFormAndContinue() } } },
            onHandoff = { kind ->
                // Say which field to fill in, using the app's own label for it, on screen and in the notification.
                val hindi = graph.settings.settings.value.guideLanguage == "hi"
                val fields = withContext(Dispatchers.Main.immediate) {
                    val svc = ConsentAccessibilityService.instance
                    val found = svc?.blockerFields().orEmpty()
                    svc?.showBlockerPrompt(
                        BlockerCopy.promptTitle(kind, found, hindi), BlockerCopy.promptBody(hindi),
                        BlockerCopy.promptStop(hindi), BlockerCopy.promptHide(hindi),
                    ) { this@AuditOrchestrator.cancel() }
                    found
                }
                step(BlockerCopy.waitingStep(kind, fields), t0)
                BlockerNotifier.post(context, config.targetLabel, kind, fields)
            },
            onResume = {
                withContext(Dispatchers.Main.immediate) { ConsentAccessibilityService.instance?.hideBlockerPrompt() }
                BlockerNotifier.clear(context)
                step("Waiting for a consent screen", t0)
            },
            clockMs = SystemClock::elapsedRealtime, pause = { delay(it) },
            interactive = plan != AuditPlan.SILENT,
        ).await(t.waitConsentMs)
        withContext(Dispatchers.Main.immediate) { ConsentAccessibilityService.instance?.hideBlockerPrompt() }
        BlockerNotifier.clear(context)
        notes += BlockerCopy.notes(waited, blocksPlan = plan != AuditPlan.SILENT)
        val appeared = waited.consent

        // A child of this plan: cancelling the audit cancels the review too.
        val review = appeared?.let { ev -> async { graph.screenAuditor.audit(ev.tree, ev.screenshotPath?.let(::File)) } }

        when (plan) {
            AuditPlan.ACCEPT, AuditPlan.REJECT -> {
                val kind = if (plan == AuditPlan.ACCEPT) ConsentKind.ACCEPT else ConsentKind.REJECT
                if (appeared != null && config.autoDrive) {
                    step("Agent is choosing: ${kind.name.lowercase()}", t0)
                    delay(t.dwellMs)
                    driveChoice(kind, notes)
                } else if (appeared != null) {
                    step("Your turn: tap ${if (kind == ConsentKind.ACCEPT) "Accept" else "Reject"} in the app", t0)
                    val chosen = ConsentScreenWaiter(
                        probe = {
                            withContext(Dispatchers.Main.immediate) { ConsentAccessibilityService.instance?.probeNow() }
                            ConsentBus.events.value.firstOrNull { it.kind == ConsentKind.ACCEPT || it.kind == ConsentKind.REJECT }
                        }, clockMs = SystemClock::elapsedRealtime, pause = { delay(it) },
                    ).await(45_000)
                    if (chosen == null) notes += "No consent choice was recorded within 45 s."
                }
                step(if (ConsentBus.choiceMade) "Observing traffic after the recorded choice" else "Observing traffic without a verified consent choice", t0)
                t.observe(plan)
            }
            AuditPlan.SILENT -> {
                step("Observing without touching the app", t0)
                t.observe(plan)
            }
        }

        // C12: Home, confirm the app left the screen, keep capturing. Added after the window above, never taken from it.
        var backgroundStartedNanos: Long? = null
        if (t.backgroundMs > 0) {
            step("Sending ${config.targetLabel} to the background", t0)
            when (val o = backgroundStep(pkg).run()) {
                is BackgroundStep.Outcome.Confirmed -> {
                    backgroundStartedNanos = o.atNanos
                    notes += BackgroundStep.confirmedNote(o.atNanos - t0, t.backgroundMs)
                    step("Watching ${config.targetLabel} in the background", t0)
                    delay(t.backgroundMs)
                }
                is BackgroundStep.Outcome.NotConfirmed -> notes += BackgroundStep.notConfirmedNote(o.reason)
            }
        }

        // One immutable boundary: events and health cannot drift while the remaining model review finishes.
        val capturedBoundary = planCapture.finish(sessionId, captureStart)
        val snapshot = capturedBoundary.snapshot
        val tEnd = snapshot.health.captureEndedNanos ?: SystemClock.elapsedRealtimeNanos()
        val captured = snapshot.events
        val consentEvents = ConsentBus.events.value
        val deep = CaptureBus.deepCapture
        val flows = if (deep) FlowPhases.assign(CaptureBus.flowSnapshot(), PhaseClock.from(consentEvents)) else emptyList()
        if (ConsentBus.unconfirmedDialogClosedAtNanos != null && !ConsentBus.choiceMade) {
            notes += "Consent dialog closed without an observed button action; the choice remains unknown."
        }
        ConsentBus.disarm()
        step("Finishing AI review of the consent screen", t0)
        val screen = review?.let { d -> withTimeoutOrNull(REVIEW_TIMEOUT_MS) { d.await() }.also { if (it == null) d.cancel() } }
        if (appeared == null) notes += "No consent screen detected within ${t.waitConsentMs / 1000} s."
        else if (screen == null) notes += "Consent-screen review unavailable; deterministic checks only."
        if (!graph.llm.isReady && appeared != null) notes += "On-device AI model not loaded — screen review used the accessibility tree only."
        if (consentEvents.any { it.nodeText?.contains("inferred") == true }) notes += "The consent choice was inferred from the dialog closing (this app does not report taps)."

        val session = AuditSession(
            id = sessionId,
            targetPackage = pkg,
            targetLabel = config.targetLabel,
            plan = plan,
            childFacing = config.childFacing,
            startedAtNanos = t0,
            endedAtNanos = tEnd,
            startedAtEpochMs = epoch,
            netEvents = captured,
            consentEvents = consentEvents,
            screenAudit = screen,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = Build.VERSION.RELEASE,
            privateDnsActive = DeviceChecks.privateDnsActive(context),
            notes = notes,
            captureHealth = snapshot.health,
            freshState = freshState,
            backgroundStartedNanos = backgroundStartedNanos,
            permissionDialogsWatched = ConsentAccessibilityService.instance != null,
            manifestFacts = manifest,
            flows = flows,
            captureDepth = if (deep) "full-relay" else "dns-only",
        )
        CapturedPlan(session, capturedBoundary.reading)
    }

    /** The background step wired to the accessibility service (Home action and window list) on the main thread. */
    private fun backgroundStep(pkg: String) = BackgroundStep(
        pressHome = { withContext(Dispatchers.Main.immediate) { ConsentAccessibilityService.instance?.pressHome() == true } },
        targetOnScreen = { withContext(Dispatchers.Main.immediate) { ConsentAccessibilityService.instance?.packageOnScreen(pkg) } },
        clockNanos = SystemClock::elapsedRealtimeNanos,
        pause = { delay(it) },
    )

    private suspend fun startCapture(config: AuditConfig) {
        CaptureBus.reset()
        runCatching { XrayVpnService.start(context, config.targetPackage, config.includeGms) }
            .onFailure { throw IllegalStateException("Could not start capture: ${it.message}") }
        val state = withTimeoutOrNull(6_000) { CaptureBus.state.first { it is CaptureState.Running || it is CaptureState.Error } }
        if (state !is CaptureState.Running) {
            val message = (state as? CaptureState.Error)?.message ?: "Capture did not start — check VPN permission"
            val need = when {
                DeviceChecks.otherVpnActive(context) -> AccessNeed.OTHER_VPN
                android.net.VpnService.prepare(context) != null -> AccessNeed.VPN
                else -> null
            }
            throw if (need != null) AccessNeededException(need, message) else IllegalStateException(message)
        }
    }

    private fun returnToXray() {
        graph.scope.launch {
            val own = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component?.flattenToShortString()
            val viaShell = own != null && ShizukuBridge.hasPermission() && ShizukuBridge.exec("am", "start", "-n", own).isSuccess
            if (!viaShell) DeviceChecks.bringXrayToFront(context)
        }
    }

    /** Shizuku's shell can start activities at any time; otherwise rely on the bound accessibility service exemption. */
    private suspend fun launchTarget(pkg: String): Boolean {
        if (ShizukuBridge.hasPermission()) {
            val component = context.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString()
            if (component != null && ShizukuBridge.exec("am", "start", "-n", component).isSuccess) return true
        }
        return DeviceChecks.launch(context, pkg)
    }

    /** The agent taps the choice; a refusal hidden behind "Manage" is found by opening Manage first. */
    private suspend fun driveChoice(kind: ConsentKind, notes: MutableList<String>) {
        val svc = ConsentAccessibilityService.instance
        if (svc == null) {
            notes += "Accessibility service not connected — the agent could not tap."
            return
        }
        if (svc.tap(kind) != null) return
        if (kind == ConsentKind.REJECT) {
            if (svc.tap(ConsentKind.SETTINGS) != null) {
                ConsentBus.emit(ConsentEvent(SystemClock.elapsedRealtimeNanos(), ConsentKind.SETTINGS, "Manage"))
                delay(1_500)
                if (svc.tap(ConsentKind.REJECT) != null) {
                    notes += "Refusal was only reachable through 'Manage' (extra step)."
                    return
                }
            }
            notes += "No way to refuse consent was found on screen."
        } else {
            notes += "No Accept button found on the consent screen."
        }
    }

    companion object {
        private const val LAUNCH_CHECK_MS = 8_000L
        private const val REVIEW_TIMEOUT_MS = 90_000L
    }
}
