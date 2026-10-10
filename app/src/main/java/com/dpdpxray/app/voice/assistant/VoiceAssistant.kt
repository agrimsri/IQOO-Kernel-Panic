package com.dpdpxray.app.voice.assistant

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.dpdpxray.app.audit.AuditConfig
import com.dpdpxray.app.audit.AuditProgress
import com.dpdpxray.app.control.DeviceChecks
import com.dpdpxray.app.graph
import com.dpdpxray.app.quarantine.QuarantineController
import com.dpdpxray.app.voice.copilotEvidence
import com.dpdpxray.app.voice.wake.WakeController
import com.dpdpxray.app.voice.wake.WakeRuntime
import com.dpdpxray.app.voice.wake.WakeStopReason
import com.dpdpxray.core.assistant.AppCandidate
import com.dpdpxray.core.assistant.AuditSpokenSummary
import com.dpdpxray.core.assistant.ChoiceReply
import com.dpdpxray.core.assistant.VoiceCommand
import com.dpdpxray.core.assistant.VoiceCommandParser
import com.dpdpxray.core.copilot.CopilotLanguage
import com.dpdpxray.core.copilot.GroundedAnswer
import com.dpdpxray.core.copilot.GroundedAnswerer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Carries out spoken, typed or chip commands for the wake word and the voice copilot: parse, plan, perform the
 * screen work through [AssistantBus], and build the reply. When a voice-started check finishes it speaks the saved
 * result and opens it. Everything runs on the phone; recognition and speech use only on-device engines.
 */
class VoiceAssistant private constructor(private val app: Application) {
    private val graph = app.graph
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val voice: AssistantVoice by lazy { AssistantVoice(app) }

    private var choices: List<AppCandidate> = emptyList()
    private var choicesAtMs = 0L
    private var followUp: Job? = null

    private val _busy = MutableStateFlow(false)
    /** True while a voice-started check is being followed up or a reply is spoken; the wake word stays paused. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * Packages a developer has allowed the agent to tap consent for: the Developer automation "test apps the runner may
     * reset" allowlist (empty by default, so only the LeakyShop demo apps are auto-tapped).
     */
    @Volatile var automationAllowlist: () -> Set<String> = {
        com.dpdpxray.app.automation.AutomationStore.get(app).settings.value.resetAllowlist
    }

    data class Turn(
        val reply: Said,
        val hindi: Boolean,
        /** The command's kind, for the calibration log; never the words. */
        val kind: String,
        val choices: List<AppCandidate> = emptyList(),
        val stopListening: Boolean = false,
        /** Set for a question, so the copilot can show its citations. */
        val answer: GroundedAnswer? = null,
        /** Screen work that waits for the user to open TraceVeil (from a notification). */
        val waiting: AssistantAction? = null,
    ) {
        val text: String get() = reply.pick(hindi)
    }

    val hasPendingChoice: Boolean
        get() = choices.isNotEmpty() && SystemClock.elapsedRealtime() - choicesAtMs < CHOICE_TTL_MS

    /** Handles one utterance and returns the reply; the caller speaks it (see [say]). */
    suspend fun handle(text: String, source: AssistantSource, preferHindi: Boolean, focusAuditId: String? = null): Turn {
        val parsed = VoiceCommandParser.parse(text)
        val hindi = preferHindi || parsed.hindi
        val offered = choices.takeIf { hasPendingChoice }.orEmpty()
        choices = emptyList()
        val needsApps = parsed.command is VoiceCommand.CheckApp || offered.isNotEmpty()
        val ctx = context(source, focusAuditId, needsApps)

        var kind = parsed.command::class.simpleName ?: "Unknown"
        val step = when (val reply = if (offered.isEmpty()) ChoiceReply.Unclear else ChoiceReply.parse(text, offered)) {
            is ChoiceReply.Picked -> AssistantPlanner.checkPicked(offered[reply.index], ctx).also { kind = "ChoicePicked" }
            ChoiceReply.Cancel -> AssistantStep(AssistantCopy.cancelled).also { kind = "ChoiceCancelled" }
            ChoiceReply.Unclear -> AssistantPlanner.plan(parsed.command, ctx)
        }

        if (step.choices.isNotEmpty()) {
            choices = step.choices
            choicesAtMs = SystemClock.elapsedRealtime()
        }
        step.question?.let { q ->
            Log.i(TAG, "command $kind outcome=answered")
            return answer(q, ctx, hindi, kind)
        }

        val action = step.action ?: run {
            Log.i(TAG, "command $kind outcome=none")
            return Turn(step.reply, hindi, kind, step.choices, step.stopListening)
        }
        val outcome = perform(action, source, hindi)
        val reply = AssistantPlanner.afterScreenWork(step.reply, action, outcome)
        Log.i(TAG, "command $kind outcome=${outcome::class.simpleName}")
        return Turn(reply, hindi, kind, step.choices, step.stopListening, waiting = action.takeIf { outcome is UiOutcome.NotReached })
    }

    /**
     * For the copilot screen: handles [text] in the assistant's own scope (a command may leave the screen, which
     * clears the screen's view model), hands the turn to [onTurn], then speaks it when [speak] is set.
     */
    fun submit(text: String, preferHindi: Boolean, focusAuditId: String?, speak: Boolean, onTurn: (Turn) -> Unit): Job = scope.launch {
        voice.stop()
        val turn = handle(text, AssistantSource.COPILOT, preferHindi, focusAuditId)
        // The wake-word service stops itself after its own reply; from the copilot it is stopped here.
        if (turn.stopListening) WakeController.stop(WakeStopReason.USER)
        onTurn(turn)
        if (speak) say(turn.reply, turn.hindi)
    }

    /** Speaks a reply with the offline voice and waits until it is finished. */
    suspend fun say(reply: Said, hindi: Boolean): Boolean {
        _busy.value = true
        try {
            return voice.say(reply, hindi)
        } finally {
            _busy.value = followUp?.isActive == true
        }
    }

    /** Binds the offline text-to-speech engine ahead of the first reply, so "Yes?" is not delayed. */
    fun warmUp() {
        voice
    }

    /** "Yes?" (or a beep) after the wake word. */
    suspend fun cue(hindi: Boolean) = voice.cue(hindi)

    private suspend fun answer(question: String, ctx: AssistantContext, hindi: Boolean, kind: String): Turn {
        val audit = ctx.audit ?: return Turn(AssistantCopy.noSavedChecks, hindi, kind)
        val (en, hi) = withContext(Dispatchers.Default) {
            val answerer = GroundedAnswerer(graph.corpus, graph.retriever)
            answerer.answer(question, audit.evidence, CopilotLanguage.EN) to answerer.answer(question, audit.evidence, CopilotLanguage.HI)
        }
        val hint = if (en.refused) AssistantCopy.noEvidenceHint else Said("", "")
        val reply = Said(en.spokenText + hint.en, hi.spokenText + hint.hi)
        return Turn(reply, hindi, kind, answer = if (hindi) hi else en)
    }

    private suspend fun context(source: AssistantSource, focusAuditId: String?, needsApps: Boolean): AssistantContext = withContext(Dispatchers.IO) {
        val repository = graph.repository
        repository.refresh()
        val id = focusAuditId ?: repository.audits.value.firstOrNull()?.id
        val audit = id?.let { repository.load(it) }?.let { b -> SavedAudit(b.meta.id, copilotEvidence(b.meta, b.sessions, b.result)) }
        val running = graph.orchestrator.progress.value as? AuditProgress.Running
        AssistantContext(
            source = source,
            apps = if (needsApps) runCatching { InstalledApps.load(app) }.getOrDefault(emptyList()) else emptyList(),
            readiness = VoiceReadiness(
                vpnReady = DeviceChecks.vpnReady(app),
                watcherOn = DeviceChecks.accessibilityOn(app),
                privateDnsActive = DeviceChecks.privateDnsActive(app),
            ),
            audit = audit,
            auditRunning = running != null,
            runningLabel = running?.config?.targetLabel,
            quarantineActive = QuarantineController.isActive,
            routes = AssistantBus.routes.value,
            wakeListening = WakeRuntime.state.value.active,
            automationAllowlist = runCatching { automationAllowlist() }.getOrDefault(emptySet()),
        )
    }

    /**
     * Screen work reaches TraceVeil's screen directly when it is showing. From the wake word in the background,
     * TraceVeil is asked to come forward (Android allows it only in some cases, for example while the consent watcher
     * is bound); if it does not appear in time, the request waits for the user to tap the notification.
     */
    private suspend fun perform(action: AssistantAction, source: AssistantSource, hindi: Boolean): UiOutcome {
        if (action is AssistantAction.Navigate && action.optional) {
            return if (AssistantBus.hostActive) AssistantBus.request(action, hindi, OPTIONAL_WAIT_MS) else UiOutcome.Skipped
        }
        if (AssistantBus.hostActive || source == AssistantSource.COPILOT) return AssistantBus.request(action, hindi, HOST_WAIT_MS)
        if (locked()) {
            AssistantBus.enqueue(action, hindi)
            return UiOutcome.NotReached(locked = true)
        }
        DeviceChecks.bringXrayToFront(app)
        return AssistantBus.request(action, hindi, BRING_FORWARD_WAIT_MS)
    }

    private fun locked(): Boolean {
        val keyguard = app.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return keyguard?.isKeyguardLocked == true || power?.isInteractive == false
    }

    /**
     * Called by the host after it started a check for a voice command: when the check ends, the saved result is
     * spoken and its screen opened. A cancelled or failed check is reported as such; nothing is invented.
     */
    fun followUp(config: AuditConfig, hindi: Boolean) {
        followUp?.cancel()
        val startedAt = System.currentTimeMillis()
        _busy.value = true
        followUp = scope.launch {
            try {
                val progress = graph.orchestrator.progress
                val end = progress.first { it !is AuditProgress.Running }
                val id = (end as? AuditProgress.Done)?.auditId ?: withContext(Dispatchers.IO) {
                    graph.repository.refresh()
                    graph.repository.audits.value.firstOrNull { it.targetPackage == config.targetPackage && it.createdAtMs >= startedAt && !it.sample }?.id
                }
                if (id == null) {
                    val reason = (end as? AuditProgress.Failed)?.message
                    voice.say(if (reason != null) AssistantCopy.checkStopped(reason) else AssistantCopy.checkCancelled, hindi)
                    return@launch
                }
                val bundle = withContext(Dispatchers.IO) { graph.repository.load(id) } ?: return@launch
                val evidence = copilotEvidence(bundle.meta, bundle.sessions, bundle.result)
                // The live screen usually opens the result itself; this covers a user who moved elsewhere meanwhile.
                delay(RESULT_SETTLE_MS)
                if (AssistantBus.hostActive) AssistantBus.request(AssistantAction.Navigate(AssistantPlanner.resultRoute(id), optional = true), hindi, OPTIONAL_WAIT_MS)
                val summary = Said(
                    AuditSpokenSummary.summarize(evidence, CopilotLanguage.EN),
                    AuditSpokenSummary.summarize(evidence, CopilotLanguage.HI),
                )
                Log.i(TAG, "voice check finished; speaking its saved result")
                voice.say(summary, hindi)
            } finally {
                _busy.value = false
            }
        }
    }

    companion object {
        private const val TAG = "HeyTraceVeil"
        private const val CHOICE_TTL_MS = 60_000L
        private const val HOST_WAIT_MS = 6_000L
        private const val OPTIONAL_WAIT_MS = 3_000L
        private const val BRING_FORWARD_WAIT_MS = 4_000L
        private const val RESULT_SETTLE_MS = 1_500L

        @Volatile private var instance: VoiceAssistant? = null

        fun get(context: Context): VoiceAssistant =
            instance ?: synchronized(this) {
                instance ?: VoiceAssistant(context.applicationContext as Application).also { instance = it }
            }
    }
}
