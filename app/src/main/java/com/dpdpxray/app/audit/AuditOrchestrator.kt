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
import com.dpdpxray.app.consent.ConsentReappearance
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
import com.dpdpxray.core.model.PlanConditions
import com.dpdpxray.core.consent.BlockerKind
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
 * Runs audit plans end to end: capture → (load AI) → per plan: restart the app (or reset it, if approved) → launch →
 * consent detection → AI review → agent or user choice → observe → evaluate → report. Runs in the application scope so
 * it survives the UI going to the background; capture stays up for the whole audit (see [startCapture]).
 *
 * Around each plan it records the conditions a low-visibility result needs to explain itself (PlanConditions): how the
 * app was started, Private DNS, another VPN, the network, the lock screen, a crash dialog, the app's own network use
 * and the first blocker. A check that loses its app or its capture between plans keeps the plans already captured.
 */
class AuditOrchestrator(private val context: Context, private val graph: AppGraph) {
    private val _progress = MutableStateFlow<AuditProgress>(AuditProgress.Idle)
    val progress: StateFlow<AuditProgress> = _progress.asStateFlow()
    private var job: Job? = null

    private val planCapture = PlanCaptureRecorder(UidTx::now, CaptureBus::reset, CaptureBus::snapshot, SystemClock::elapsedRealtimeNanos)

    /** A restart (data kept) before every plan when Shizuku can; a reset only when the person approved it. */
    private val appStart = AppStart(
        shizukuReady = { ShizukuBridge.hasPermission() },
        processRunning = { ShizukuBridge.processRunning(it) },
        forceStop = { ShizukuBridge.forceStop(it) },
        clearData = { ShizukuBridge.clearAppData(it) },
    )

    private data class CapturedPlan(val session: AuditSession, val upload: SessionNetworkReadings)

    @Synchronized
    fun start(config: AuditConfig) {
        if (_progress.value is AuditProgress.Running) return
        // TraceVeil itself, a missing app or one with no screen cannot be checked: say so before anything starts.
        val facts = DeviceChecks.appFacts(context, config.targetPackage)
        AuditTargets.refusal(
            config.targetPackage, config.targetLabel, context.packageName,
            AuditTargets.Facts(installed = facts != null, launchable = facts?.launchable == true, system = facts?.system == true),
        )?.let {
            _progress.value = AuditProgress.Failed(it)
            return
        }
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
            val facts = DeviceChecks.appFacts(context, config.targetPackage)
            val done = mutableListOf<CapturedPlan>()
            var stopNote: String? = null
            for ((i, plan) in config.plans.withIndex()) {
                try {
                    done += ConsentPlanRetry.capture(plan, config.targetPackage, { config.resetAppData && ShizukuBridge.hasPermission() }, { it.session }) { attempt ->
                        runPlan(config, plan, i, auditId, dir, attempt, manifest, facts)
                    }.let { attempts ->
                        if (attempts.size == 1) attempts else attempts.mapIndexed { attempt, captured ->
                            val note = if (attempt == 0) "Consent screen missed; first attempt retained and one fresh-state demo retry requested."
                                else "Bounded fresh-state demo retry (1 of 1); the failed first attempt remains in this audit."
                            captured.copy(session = captured.session.copy(notes = captured.session.notes + note))
                        }
                    }
                } catch (e: PlanStopped) {
                    // The app was uninstalled, the capture stopped or the phone stayed locked: keep what was captured.
                    when (val stop = AuditTargets.stop(done.size, e.message.orEmpty())) {
                        is AuditTargets.Stop.Fail -> throw IllegalStateException(stop.message)
                        is AuditTargets.Stop.KeepPartial -> stopNote = stop.note
                    }
                    break
                }
            }
            stopNote?.let { note -> done[done.lastIndex] = done.last().let { it.copy(session = it.session.copy(notes = it.session.notes + note)) } }
            done.toList()
        } finally {
            runCatching { XrayVpnService.stop(context) }
            ConsentBus.disarm()
            BlockerNotifier.clear(context)
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
            autoDrive = config.autoDrive, includeGms = config.includeGms,
        ).let { m ->
            // Update Watch: the version this audit saw, so a later update of the app can be noticed and compared.
            val version = com.dpdpxray.core.update.AppVersion.ofSessions(sessions)
            m.copy(targetVersionName = version?.name, targetVersionCode = version?.code)
        }
        graph.repository.save(meta, sessions, result, markdown, capturedPlans.map { it.upload })
        _progress.value = AuditProgress.Done(auditId)
        returnToXray()
    }

    private suspend fun runPlan(
        config: AuditConfig, plan: AuditPlan, index: Int, auditId: String, dir: File, attempt: Int = 0, manifest: AppManifestFacts? = null,
        facts: DeviceChecks.AppFacts? = null,
    ): CapturedPlan = coroutineScope {
        val t = AuditTimingPolicy.forAudit(config)
        val pkg = config.targetPackage
        val sessionId = "$auditId-${plan.name}" + if (attempt == 0) "" else "-retry-$attempt"
        val notes = mutableListOf<String>()
        if (config.fastPreview) notes += AuditTimingPolicy.coverageHint(AuditTimingPolicy.FAST_PREVIEW)
        fun step(s: String, start: Long = 0) {
            _progress.value = AuditProgress.Running(config, index, plan, s, start)
        }

        // Before every plan: the app is still installed and the capture is still up.
        AuditTargets.beforePlan(config.targetLabel, installed = DeviceChecks.appFacts(context, pkg) != null,
            captureRunning = CaptureBus.state.value is CaptureState.Running)?.let { throw PlanStopped(it) }

        // A locked phone cannot open the app: wait for the person to unlock it, then carry on.
        if (DeviceChecks.phoneLocked(context)) {
            step("Unlock the phone to continue the check of ${config.targetLabel}")
            BlockerNotifier.postUnlock(context, config.targetLabel)
            val unlocked = Waits.until(UNLOCK_WAIT_MS, SystemClock::elapsedRealtime, { delay(it) }) { !DeviceChecks.phoneLocked(context) }
            BlockerNotifier.clear(context)
            if (!unlocked) throw PlanStopped(AuditTargets.stayedLocked(config.targetLabel))
            notes += "The phone was locked when this plan was due; TraceVeil waited for it to be unlocked before opening the app."
        }

        val privateDnsAtStart = DeviceChecks.privateDnsActive(context)
        val networkAtStart = DeviceChecks.networkOnline(context)
        step(when {
            config.resetAppData -> "Resetting authorised test app ${config.targetLabel}"
            ShizukuBridge.hasPermission() -> "Restarting ${config.targetLabel} (your data stays)"
            else -> "Keeping ${config.targetLabel}'s current data"
        })
        val started = appStart.prepare(pkg, config.resetAppData)
        notes += started.notes
        if (index == 0 && attempt == 0 && facts?.system == true) notes += AuditTargets.systemNote(config.targetLabel)
        val freshState = started.freshState // true only when an approved reset actually cleared the app's data
        val trafficStart = AppTrafficReader.mark(facts?.uid)
        var lockedDuring = false
        val connectionSamples = mutableListOf<Int>()
        suspend fun sample() {
            if (DeviceChecks.phoneLocked(context)) lockedDuring = true
            withContext(Dispatchers.IO) { AppTrafficReader.connections(facts?.uid) }?.let { connectionSamples += it }
        }

        if (CaptureBus.state.value !is CaptureState.Running) throw PlanStopped(AuditTargets.CAPTURE_STOPPED)
        val captureStart = planCapture.start()
        ConsentBus.arm(pkg, File(dir, "shots/$sessionId"), config.targetLabel)
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
            val message = AuditTargets.didNotOpen(config.targetLabel, crashed = ConsentBus.appCrashed)
            // A crash, or a later plan: end the check here and keep the plans already captured.
            if (ConsentBus.appCrashed || index > 0) throw PlanStopped(message)
            val need = if (ConsentAccessibilityService.instance == null) AccessNeed.WATCHER
            else AccessNeeds.shizuku(ShizukuBridge.isRunning(), ShizukuBridge.hasPermission())
            throw if (need != null) AccessNeededException(need, message) else IllegalStateException(message)
        }

        step("Waiting for a consent screen", t0)
        // A sign-in, code, CAPTCHA or fingerprint screen can stand between launch and the consent screen: skip it with a
        // harmless button when there is one, otherwise ask the person to complete it and keep watching meanwhile.
        val agent = BlockerAgentRunner(graph.llm, File(context.cacheDir, "blocker-step.jpg"))
        val waited = ConsentWaitAssist(
            probe = {
                withContext(Dispatchers.Main.immediate) { ConsentAccessibilityService.instance?.probeNow() }
                ConsentBus.events.value.firstOrNull { it.kind == ConsentKind.SCREEN_APPEARED || it.kind == ConsentKind.MANUAL_MARK }
            },
            // The app's own blocker screens, then Android's permission dialog over it (never answered by TraceVeil).
            detectBlocker = {
                withContext(Dispatchers.Main.immediate) {
                    ConsentAccessibilityService.instance?.let { svc -> svc.blockerNow() ?: BlockerKind.PERMISSION.takeIf { svc.permissionDialogNow() } }
                }
            },
            // A skip button first; failing that, a profile form is completed with test values (never identifying fields).
            // Cheapest first: a skip button, then the known profile form, then the on-device model for anything else.
            trySkip = { withContext(Dispatchers.Main.immediate) { ConsentAccessibilityService.instance?.let { it.trySkipBlocker() ?: it.fillFormAndContinue() ?: agent.step(it) } } },
            onHandoff = { kind ->
                // Say which field to fill in, using the app's own label for it, on screen and in the notification.
                val hindi = graph.settings.settings.value.guideLanguage == "hi"
                val fields = withContext(Dispatchers.Main.immediate) {
                    val svc = ConsentAccessibilityService.instance
                    val found = if (kind == BlockerKind.PERMISSION) emptyList() else svc?.blockerFields().orEmpty()
                    svc?.showBlockerPrompt(
                        BlockerCopy.promptTitle(kind, found, hindi), BlockerCopy.promptBody(kind, hindi, agent.lastReason.takeIf { kind != BlockerKind.PERMISSION }),
                        BlockerCopy.promptStop(hindi), BlockerCopy.promptHide(hindi), BlockerCopy.promptContinue(hindi),
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
            // The model may take several small steps; Android's permission dialog never reaches it (see ConsentWaitAssist).
            maxSkips = if (agent.available) 3 + BlockerAgentRunner.MAX_STEPS else 3,
            userContinued = { ConsentBus.takeContinue() },
        ).await(t.waitConsentMs)
        withContext(Dispatchers.Main.immediate) { ConsentAccessibilityService.instance?.hideBlockerPrompt() }
        BlockerNotifier.clear(context)
        agent.cleanup()
        notes += BlockerCopy.notes(waited, blocksPlan = plan != AuditPlan.SILENT)
        val blockedStep = BlockerCopy.step(waited)
        val appeared = waited.consent
        sample()

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
        // Read before Home is pressed: the app's connections while it is in front.
        sample()

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
        val watcherOn = ConsentAccessibilityService.instance != null
        val crashed = ConsentBus.appCrashed
        val returns = ConsentBus.consentReturns
        ConsentReappearance.capNote(returns)?.let { notes += it }
        if (crashed) notes += "Android showed that ${config.targetLabel} stopped or was not responding during this plan."
        if (DeviceChecks.phoneLocked(context)) lockedDuring = true
        if (lockedDuring) notes += "The phone was locked or its screen went off during this plan; the app may have paused, so fewer lookups may have been seen."
        val privateDnsAtEnd = DeviceChecks.privateDnsActive(context)
        if (privateDnsAtEnd && !privateDnsAtStart) notes += "Private DNS was turned on during this plan; lookups after that may be hidden from TraceVeil."
        if (privateDnsAtStart && !privateDnsAtEnd) notes += "Private DNS was turned off during this plan."
        val conditions = PlanConditions(
            startMode = started.mode, restartProblem = started.problem, runningBeforeStart = started.runningBefore,
            privateDnsAtStart = privateDnsAtStart, privateDnsAtEnd = privateDnsAtEnd,
            otherVpnAtEnd = DeviceChecks.otherVpnActive(context),
            networkAtStart = networkAtStart, networkAtEnd = DeviceChecks.networkOnline(context),
            phoneLocked = lockedDuring,
            appCrashed = crashed.takeIf { watcherOn },
            appTraffic = AppTrafficReader.traffic(facts?.uid, trafficStart, connectionSamples.maxOrNull()),
            systemApp = facts?.system,
            blocker = blockedStep,
            consentShownAgain = returns.takeIf { it > 0 },
        )
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
            privateDnsActive = privateDnsAtStart || privateDnsAtEnd,
            notes = notes,
            captureHealth = snapshot.health,
            freshState = freshState,
            backgroundStartedNanos = backgroundStartedNanos,
            permissionDialogsWatched = ConsentAccessibilityService.instance != null,
            manifestFacts = manifest,
            conditions = conditions,
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
        /** How long a plan waits for a locked phone to be unlocked before the check ends (keeping earlier plans). */
        private const val UNLOCK_WAIT_MS = 120_000L
        private const val REVIEW_TIMEOUT_MS = 90_000L
    }
}
