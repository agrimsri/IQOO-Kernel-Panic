package com.dpdpxray.app.webcheck

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.dpdpxray.app.audit.AuditProgress
import com.dpdpxray.app.audit.AuditTimingPolicy
import com.dpdpxray.app.data.AuditMeta
import com.dpdpxray.app.graph
import com.dpdpxray.app.report.EvidenceReport
import com.dpdpxray.app.ui.guide.Bi
import com.dpdpxray.core.consent.ConsentLexicon
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.report.MarkdownReport
import com.dpdpxray.core.rules.Inconclusive
import com.dpdpxray.core.rules.RulesEngine
import com.dpdpxray.core.web.CookieSnapshot
import com.dpdpxray.core.web.StorageSnapshot
import com.dpdpxray.core.web.WebAudit
import com.dpdpxray.core.web.WebBanner
import com.dpdpxray.core.web.WebBannerDetection
import com.dpdpxray.core.web.WebBanners
import com.dpdpxray.core.web.WebClick
import com.dpdpxray.core.web.WebCookieMeta
import com.dpdpxray.core.web.WebFixes
import com.dpdpxray.core.web.WebPlanCapture
import com.dpdpxray.core.web.WebProbe
import com.dpdpxray.core.web.WebProbeResult
import com.dpdpxray.core.web.WebResourceType
import com.dpdpxray.core.web.WebSessions
import com.dpdpxray.core.web.WebSnapshot
import com.dpdpxray.core.web.WebStep
import com.dpdpxray.core.web.WebUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the person chose on the start screen. */
data class WebCheckConfig(
    val url: String,
    val host: String,
    /** TraceVeil taps the banner's Reject and Accept itself; off = the person taps them while the check waits. */
    val agentTaps: Boolean = true,
    val plans: List<AuditPlan> = WebCheckTiming.PLANS,
)

/** Timings of a website check, the same windows as a normal app audit (AuditTimingPolicy.NORMAL). */
object WebCheckTiming {
    val PLANS = listOf(AuditPlan.SILENT, AuditPlan.REJECT, AuditPlan.ACCEPT)
    const val WAIT_BANNER_MS = 20_000L
    const val WAIT_PERSON_MS = 45_000L
    const val SETTLE_MS = 2_500L
    const val OBSERVE_MS = 15_000L
    const val SILENT_MS = 25_000L
    const val POLL_MS = 1_000L
    const val SETTINGS_PAUSE_MS = 1_500L
}

sealed interface WebCheckState {
    data object Idle : WebCheckState

    /** [planStartNanos] is the current plan's start (elapsedRealtimeNanos, the requests' clock). */
    data class Running(
        val config: WebCheckConfig,
        val planIndex: Int,
        val step: Bi,
        val planStartNanos: Long,
        val bannerFound: Boolean = false,
        val choiceNanos: Long? = null,
        val choiceKind: ConsentKind? = null,
        val waitingForPerson: Boolean = false,
    ) : WebCheckState

    data class Done(val auditId: String) : WebCheckState
    data class Failed(val message: Bi) : WebCheckState
}

/**
 * Runs one website check the person started: for each plan (no interaction, Reject, Accept) it clears TraceVeil's
 * browser, opens the site, reads the banner, taps the plan's choice (or waits for the person's tap), keeps watching, and
 * reads cookies and storage at fixed moments. Then it clears the browser again, looks up the contacted servers'
 * addresses for the map, applies the rules and saves the check like an app audit. It never runs by itself and stops
 * when the person leaves the live screen.
 */
object WebCheckRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow<WebCheckState>(WebCheckState.Idle)
    val state: StateFlow<WebCheckState> = _state.asStateFlow()

    private val _browser = MutableStateFlow<WebCheckBrowser?>(null)
    /** The browser of the running check, for the live screen to show. */
    val browser: StateFlow<WebCheckBrowser?> = _browser.asStateFlow()

    private var job: Job? = null

    val isRunning: Boolean get() = _state.value is WebCheckState.Running

    /** Starts a check; returns why it cannot start, or null. */
    fun start(context: Context, config: WebCheckConfig): Bi? {
        val app = context.applicationContext
        if (isRunning) return WebCheckCopy.alreadyRunning
        if (app.graph.orchestrator.progress.value is AuditProgress.Running) return WebCheckCopy.appCheckRunning
        if (com.dpdpxray.app.quarantine.QuarantineController.isActive) return WebCheckCopy.appCheckRunning
        _state.value = WebCheckState.Running(config, 0, WebCheckCopy.preparing, SystemClock.elapsedRealtimeNanos())
        job = scope.launch { run(app, config) }
        return null
    }

    /** Stops the check; nothing is saved. */
    fun cancel() {
        job?.cancel()
        job = null
    }

    /** The screen has shown the outcome; back to idle. */
    fun acknowledge() {
        if (_state.value !is WebCheckState.Running) _state.value = WebCheckState.Idle
    }

    private suspend fun run(app: Context, config: WebCheckConfig) {
        val browser = WebCheckBrowser(app)
        _browser.value = browser
        try {
            val auditId = "web-${System.currentTimeMillis()}"
            val captures = config.plans.mapIndexed { i, plan -> plan(browser, config, auditId, i, plan) }
            step(WebCheckCopy.saving)
            withContext(NonCancellable) { browser.clearProfile() }
            val hosts = captures.flatMap { c -> c.requests.mapNotNull { WebUrl.hostOf(it.url) } }.distinct()
            val addresses = WebHostResolver.resolve(hosts)
            val id = save(app, auditId, config, captures.map { it.copy(addresses = addresses) })
            _state.value = WebCheckState.Done(id)
        } catch (e: CancellationException) {
            _state.value = WebCheckState.Idle
            throw e
        } catch (e: PlanFailure) {
            _state.value = WebCheckState.Failed(e.reason)
        } catch (e: Exception) {
            _state.value = WebCheckState.Failed(WebCheckCopy.failed)
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                runCatching { browser.clearProfile() }
                _browser.value = null
                browser.destroy()
            }
        }
    }

    private class PlanFailure(val reason: Bi) : Exception(reason.en)

    private fun step(text: Bi) = _state.update { s -> if (s is WebCheckState.Running) s.copy(step = text) else s }

    /** One plan, start to end. */
    private suspend fun plan(browser: WebCheckBrowser, config: WebCheckConfig, auditId: String, index: Int, plan: AuditPlan): WebPlanCapture {
        _state.value = WebCheckState.Running(config, index, WebCheckCopy.clearing, SystemClock.elapsedRealtimeNanos())
        if (!browser.clearProfile()) throw PlanFailure(WebCheckCopy.notCleared)
        val epoch0 = System.currentTimeMillis()
        val t0 = SystemClock.elapsedRealtimeNanos()
        fun now() = SystemClock.elapsedRealtimeNanos()
        fun elapsedMs() = (now() - t0) / 1_000_000
        _state.value = WebCheckState.Running(config, index, WebCheckCopy.opening, t0)
        browser.startRecording()
        browser.load(config.url)

        val events = mutableListOf<ConsentEvent>()
        val notes = mutableListOf<String>()
        val cookieSnaps = mutableListOf<CookieSnapshot>()
        val storageSnaps = mutableListOf<StorageSnapshot>()
        val types = linkedMapOf<String, WebResourceType>()
        val meta = linkedMapOf<String, WebCookieMeta>()
        var banner: WebBanner? = null
        var latest: WebProbeResult? = null
        var settingsOpened = false
        var choiceAt: Long? = null
        var choiceKind: ConsentKind? = null
        var repromptSeen = false
        var goneAfterChoice = false
        var stillShownNoted = false
        var unavailable = false
        var unavailableAtMs = 0L
        var bannerSeenAt = 0L
        var nextDuringSnapshotMs = 0L
        val personTaps = !config.agentTaps

        suspend fun scan(): WebProbeResult? {
            val r = WebProbe.parseScan(browser.evaluate(WebProbe.scanCall())) ?: return null
            r.resourceTypes.forEach { (u, t) -> types.putIfAbsent(u, t) }
            r.cookieMeta.forEach { meta[it.name] = it }
            latest = r
            return r
        }

        suspend fun snapshot(at: WebSnapshot) {
            val start = now()
            val hosts = (browser.seen.value.mapNotNull { WebUrl.hostOf(it.url) } + config.host).distinct()
            val readings = browser.readCookies(hosts)
            cookieSnaps += CookieSnapshot(at, start, now(), readings)
            latest?.let { storageSnaps += StorageSnapshot(at, start, it.localStorage, it.sessionStorage) }
        }

        fun clickTime(c: WebClick): Long = t0 + (c.epochMs - epoch0) * 1_000_000

        // When a Reject or Accept run without a choice ends: no banner by 20 s (then 15 s more of watching); no way to make
        // the choice (15 s of watching from then); the person not tapping (45 s, and at least 20 s after the banner);
        // TraceVeil's tap not taking (10 s of retries after the banner settled).
        fun deadlineMs(): Long {
            val bannerMs = (bannerSeenAt - t0) / 1_000_000
            return when {
                banner == null -> WebCheckTiming.WAIT_BANNER_MS + WebCheckTiming.OBSERVE_MS
                unavailable -> unavailableAtMs + WebCheckTiming.OBSERVE_MS
                personTaps -> maxOf(WebCheckTiming.WAIT_PERSON_MS, bannerMs + 20_000)
                else -> maxOf(WebCheckTiming.WAIT_BANNER_MS, bannerMs + WebCheckTiming.SETTLE_MS + 10_000)
            }
        }
        while (true) {
            delay(WebCheckTiming.POLL_MS)
            if (browser.rendererGone) throw PlanFailure(WebCheckCopy.pageStopped)
            val r = scan()
            val b = r?.banner
            if (b != null && banner == null) {
                banner = b
                bannerSeenAt = now()
                events += ConsentEvent(bannerSeenAt, ConsentKind.SCREEN_APPEARED, nodeText = b.cmp,
                    tree = if (b.detection == WebBannerDetection.FRAME_ONLY) null else WebBanners.tree(b))
                snapshot(WebSnapshot.BANNER)
                if (personTaps && b.detection == WebBannerDetection.FRAME_ONLY) notes += WebCheckCopy.frameTapsNote(b.frameHost).en
                _state.update { s -> if (s is WebCheckState.Running) s.copy(bannerFound = true) else s }
            }
            // The person's own taps (trusted clicks), in agent mode too: what the person did is what happened.
            r?.clicks?.filter { it.trusted }?.forEach { c ->
                val kind = classifyTap(c.text, banner)
                if (kind != null && choiceAt == null) {
                    if (kind == ConsentKind.SETTINGS) events += ConsentEvent(clickTime(c), ConsentKind.SETTINGS, c.text)
                    else { choiceAt = clickTime(c); choiceKind = kind; events += ConsentEvent(choiceAt!!, kind, c.text) }
                }
            }
            val choice = choiceAt
            if (choice != null) {
                _state.update { s -> if (s is WebCheckState.Running) s.copy(choiceNanos = choice, choiceKind = choiceKind, waitingForPerson = false, step = WebCheckCopy.watching) else s }
                // The banner closing after the choice, and coming back once it had closed (the nagging signal reads it).
                if (r != null && b == null) goneAfterChoice = true
                if (b != null && goneAfterChoice && !repromptSeen && b.detection != WebBannerDetection.FRAME_ONLY &&
                    WebBanners.shown(b).any { WebBanners.kindOf(it) == ConsentKind.ACCEPT }) {
                    repromptSeen = true
                    events += ConsentEvent(now(), ConsentKind.REPROMPT, tree = WebBanners.tree(b))
                }
                if (b != null && !goneAfterChoice && !stillShownNoted && now() - choice >= 3_000_000_000L) {
                    stillShownNoted = true
                    notes += WebCheckCopy.stillShownNote.en
                }
                if (now() - choice >= 2_000_000_000L && cookieSnaps.none { it.at == WebSnapshot.DURING && it.startNanos > choice }) snapshot(WebSnapshot.DURING)
                if ((now() - choice) / 1_000_000 >= WebCheckTiming.OBSERVE_MS) break
                continue
            }
            if (plan == AuditPlan.SILENT) {
                step(if (banner != null) WebCheckCopy.silentBanner else WebCheckCopy.silent)
                if (elapsedMs() >= WebCheckTiming.SILENT_MS) break
                continue
            }
            // Reject or Accept, no choice yet.
            if (personTaps) {
                // Readings every second while the person decides, so cookies are placed before or after the tap.
                if (elapsedMs() >= nextDuringSnapshotMs) { snapshot(WebSnapshot.DURING); nextDuringSnapshotMs = elapsedMs() + WebCheckTiming.POLL_MS }
                _state.update { s -> if (s is WebCheckState.Running) s.copy(waitingForPerson = banner != null, step = if (plan == AuditPlan.REJECT) WebCheckCopy.tapReject else WebCheckCopy.tapAccept) else s }
            } else if (banner != null && !unavailable && now() - bannerSeenAt >= WebCheckTiming.SETTLE_MS * 1_000_000) {
                val current = b ?: banner!!
                when (val next = WebBanners.stepFor(plan, current, settingsOpened)) {
                    is WebStep.Choose -> {
                        step(if (next.kind == ConsentKind.REJECT) WebCheckCopy.tappingReject else WebCheckCopy.tappingAccept)
                        snapshot(WebSnapshot.BEFORE_CHOICE)
                        val at = now()
                        val clicked = WebProbe.parseClick(browser.evaluate(WebProbe.clickCall(next.control.index)))
                        if (clicked?.ok == true) {
                            choiceAt = at; choiceKind = next.kind
                            events += ConsentEvent(at, next.kind, clicked.text ?: next.control.text)
                        } else notes += "TraceVeil could not tap \"${next.control.text}\"."
                    }
                    is WebStep.OpenSettings -> {
                        val clicked = WebProbe.parseClick(browser.evaluate(WebProbe.clickCall(next.control.index)))
                        if (clicked?.ok == true) {
                            events += ConsentEvent(now(), ConsentKind.SETTINGS, clicked.text ?: next.control.text)
                            settingsOpened = true
                            delay(WebCheckTiming.SETTINGS_PAUSE_MS)
                        } else { notes += "TraceVeil could not open \"${next.control.text}\"."; unavailable = true; unavailableAtMs = elapsedMs() }
                    }
                    is WebStep.Unavailable -> { notes += next.reason; unavailable = true; unavailableAtMs = elapsedMs(); step(Bi(next.reason, next.reason)) }
                }
                continue
            } else if (banner == null) step(WebCheckCopy.lookingForBanner)
            if (elapsedMs() >= deadlineMs()) {
                when {
                    banner == null -> notes += WebCheckCopy.noBannerNote(WebCheckTiming.WAIT_BANNER_MS / 1000).en
                    personTaps -> notes += WebCheckCopy.noTapNote.en
                    !unavailable -> notes += WebCheckCopy.noChoiceNote.en
                }
                break
            }
        }
        snapshot(WebSnapshot.END)
        val (requests, dropped, errors) = browser.stopRecording()
        if (!browser.pageLoaded && requests.isEmpty()) notes += WebCheckCopy.notLoadedNote.en
        return WebPlanCapture(
            id = "$auditId-${plan.name}", plan = plan, url = config.url,
            startedAtNanos = t0, endedAtNanos = now(), startedAtEpochMs = epoch0,
            requests = requests, requestsDropped = dropped, resourceTypes = types,
            cookieSnapshots = cookieSnaps, storageSnapshots = storageSnaps, cookieMeta = meta.values.toList(),
            banner = banner, consentEvents = events, profileCleared = true, agentTaps = config.agentTaps,
            pageLoaded = browser.pageLoaded, errors = errors, notes = notes.distinct(),
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(), androidVersion = Build.VERSION.RELEASE.orEmpty(),
        )
    }

    /** A tap's role: the banner control with the same text decides, then the shared consent wording. */
    internal fun classifyTap(text: String, banner: WebBanner?): ConsentKind? {
        val t = text.trim()
        if (t.isEmpty()) return null
        banner?.controls?.firstOrNull { it.text.equals(t, ignoreCase = true) }?.let { c -> WebBanners.kindOf(c)?.let { return it } }
        return ConsentLexicon.classifyButton(t)
    }

    private suspend fun save(app: Context, auditId: String, config: WebCheckConfig, captures: List<WebPlanCapture>): String = withContext(Dispatchers.Default) {
        val graph = app.graph
        val catalog = graph.catalog()
        val sessions: List<AuditSession> = captures.map { WebSessions(catalog).build(it) }
        val result = RulesEngine(catalog).evaluate(sessions)
        val fixes = WebFixes.forResult(result)
        val md = EvidenceReport.annotate(MarkdownReport.build(result, sessions, fixes, graph.citations()), sessions, AuditTimingPolicy.NORMAL)
        graph.repository.save(
            AuditMeta(
                id = auditId, targetPackage = WebAudit.packageFor(config.host), targetLabel = WebUrl.label(config.url),
                createdAtMs = System.currentTimeMillis(), score = result.score, band = result.band, findingCount = result.findings.size,
                plans = sessions.map { it.plan.label }, timingProfile = AuditTimingPolicy.NORMAL,
                inconclusiveReason = Inconclusive.reason(result.findings)?.name, targetKind = WebAudit.KIND,
            ),
            sessions, result, md,
        )
        auditId
    }
}
