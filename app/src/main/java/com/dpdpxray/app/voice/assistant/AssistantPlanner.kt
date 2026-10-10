package com.dpdpxray.app.voice.assistant

import com.dpdpxray.app.audit.AuditConfig
import com.dpdpxray.core.assistant.AppCandidate
import com.dpdpxray.core.assistant.AppMatch
import com.dpdpxray.core.assistant.AppNameMatcher
import com.dpdpxray.core.assistant.AuditSpokenSummary
import com.dpdpxray.core.assistant.OpenTarget
import com.dpdpxray.core.assistant.VoiceCommand
import com.dpdpxray.core.copilot.AuditEvidence
import com.dpdpxray.core.copilot.CopilotLanguage

/** A reply in both languages; the speaker picks one when it knows which offline voice is installed. */
data class Said(val en: String, val hi: String) {
    fun pick(hindi: Boolean): String = if (hindi) hi else en

    operator fun plus(more: Said): Said = Said("$en ${more.en}".trim(), "$hi ${more.hi}".trim())
}

/** What is still missing before a real audit can start. Each opens the screen that fixes it. */
enum class SetupFix { VPN, WATCHER, PRIVATE_DNS }

/** Screen work a command asks for; only TraceVeil's own screen (the host in MainActivity) performs it. */
sealed interface AssistantAction {
    /** Starts the audit the same way the app picker does, then shows the live screen. */
    data class StartAudit(val config: AuditConfig) : AssistantAction

    /** [optional]: only if TraceVeil is already on screen; never brings it forward or posts a notification. */
    data class Navigate(val route: String, val optional: Boolean = false) : AssistantAction

    /** APK Guard's file picker. */
    data object PickApkFile : AssistantAction

    data class FixSetup(val fix: SetupFix) : AssistantAction
}

/** Where a command came from: the wake word (possibly in the background) or the copilot screen (on screen). */
enum class AssistantSource { WAKE, COPILOT }

data class VoiceReadiness(val vpnReady: Boolean, val watcherOn: Boolean, val privateDnsActive: Boolean)

/** A saved audit the assistant may read: its id and the copilot's view of its evidence. */
data class SavedAudit(val id: String, val evidence: AuditEvidence)

/** Everything the planner may look at. It reads nothing else, so every reply can be traced to these facts. */
data class AssistantContext(
    val source: AssistantSource,
    val apps: List<AppCandidate> = emptyList(),
    val readiness: VoiceReadiness = VoiceReadiness(vpnReady = true, watcherOn = true, privateDnsActive = false),
    /** The audit "what did you find" refers to: the copilot's own audit, otherwise the newest saved one. */
    val audit: SavedAudit? = null,
    val auditRunning: Boolean = false,
    /** The running check's app label, when [auditRunning]. */
    val runningLabel: String? = null,
    val quarantineActive: Boolean = false,
    /** Optional routes present in this build (for example "checkup", "trust", "apkguard"). */
    val routes: Set<String> = emptySet(),
    val wakeListening: Boolean = false,
    val automationAllowlist: Set<String> = emptySet(),
)

data class AssistantStep(
    val reply: Said,
    val action: AssistantAction? = null,
    /** Two apps offered with "Which one?"; the next utterance answers it. */
    val choices: List<AppCandidate> = emptyList(),
    /** Set for a question: the grounded copilot answers it from [AssistantContext.audit]. */
    val question: String? = null,
    val stopListening: Boolean = false,
)

/**
 * Turns a parsed command into a reply and at most one screen action. Pure: it reads only [AssistantContext], so the
 * same facts always give the same reply. It never starts a Full audit or a reset (see [VoiceAuditOptions]), checks
 * readiness before any audit and says exactly what to fix, and never claims more than the saved result shows.
 */
object AssistantPlanner {
    const val ROUTE_HOME = "home"
    const val ROUTE_REPORTS = "reports"
    const val ROUTE_SETTINGS = "settings"
    const val ROUTE_PICK = "pick"
    const val ROUTE_CHECKUP = "checkup"
    const val ROUTE_TRUST = "trust"
    const val ROUTE_APK_GUARD = "apkguard"

    fun resultRoute(auditId: String) = "results/$auditId"

    fun plan(command: VoiceCommand, ctx: AssistantContext): AssistantStep = when (command) {
        VoiceCommand.Empty -> AssistantStep(AssistantCopy.notCaught)
        VoiceCommand.Help -> AssistantStep(AssistantCopy.help)
        VoiceCommand.Stop ->
            if (ctx.wakeListening) AssistantStep(AssistantCopy.stopped, stopListening = true) else AssistantStep(AssistantCopy.notListening)
        VoiceCommand.LastResult -> lastResult(ctx)
        is VoiceCommand.Open -> open(command.target, ctx)
        VoiceCommand.CheckFile ->
            if (ROUTE_APK_GUARD in ctx.routes) AssistantStep(AssistantCopy.pickApk, AssistantAction.PickApkFile)
            else AssistantStep(AssistantCopy.notInThisVersion("Checking an APK file", "APK फ़ाइल की जाँच"))
        VoiceCommand.PhoneCheckup ->
            if (ROUTE_CHECKUP in ctx.routes) AssistantStep(AssistantCopy.openingCheckup, AssistantAction.Navigate(ROUTE_CHECKUP))
            else AssistantStep(AssistantCopy.notInThisVersion("My phone checkup", "फ़ोन चेकअप"))
        is VoiceCommand.CheckApp -> checkApp(command.name, ctx).let { step ->
            // Voice only ever runs a Quick check; say so when more was asked for, before anything else.
            if (command.askedForMore && step.action is AssistantAction.StartAudit) step.copy(reply = AssistantCopy.quickOnly + step.reply) else step
        }
        is VoiceCommand.Question ->
            if (ctx.audit == null) AssistantStep(AssistantCopy.noSavedChecks) else AssistantStep(AssistantCopy.answering, question = command.text)
    }

    /** The user picked one of the two offered apps. */
    fun checkPicked(app: AppCandidate, ctx: AssistantContext): AssistantStep = startFor(app, ctx)

    /** The reply once TraceVeil's screen tried [action]: the [planned] reply, or what stopped the work. */
    fun afterScreenWork(planned: Said, action: AssistantAction, outcome: UiOutcome): Said = when (outcome) {
        UiOutcome.Done, UiOutcome.Skipped -> planned
        UiOutcome.Unavailable -> AssistantCopy.screenMissing
        is UiOutcome.CheckRunning -> AssistantCopy.alreadyRunning(outcome.app)
        is UiOutcome.Failed -> if (action is AssistantAction.StartAudit) AssistantCopy.couldNotStart(outcome.reason) else AssistantCopy.openApp
        is UiOutcome.NotReached -> if (outcome.locked) AssistantCopy.unlockFirst else AssistantCopy.tapNotification
    }

    private fun lastResult(ctx: AssistantContext): AssistantStep {
        val audit = ctx.audit ?: return AssistantStep(AssistantCopy.noSavedChecks)
        val summary = Said(
            AuditSpokenSummary.summarize(audit.evidence, CopilotLanguage.EN),
            AuditSpokenSummary.summarize(audit.evidence, CopilotLanguage.HI),
        )
        // The copilot already shows this audit; elsewhere its result screen opens if TraceVeil is on screen.
        val show = if (ctx.source == AssistantSource.COPILOT) null else AssistantAction.Navigate(resultRoute(audit.id), optional = true)
        return AssistantStep(summary, show)
    }

    private fun open(target: OpenTarget?, ctx: AssistantContext): AssistantStep = when (target) {
        null -> AssistantStep(AssistantCopy.openWhat)
        OpenTarget.REPORTS -> AssistantStep(AssistantCopy.opening("your reports", "आपकी रिपोर्टें"), AssistantAction.Navigate(ROUTE_REPORTS))
        OpenTarget.SETTINGS -> AssistantStep(AssistantCopy.opening("Settings", "सेटिंग्स"), AssistantAction.Navigate(ROUTE_SETTINGS))
        OpenTarget.HOME -> AssistantStep(AssistantCopy.opening("Home", "होम"), AssistantAction.Navigate(ROUTE_HOME))
        OpenTarget.TRUST ->
            if (ROUTE_TRUST in ctx.routes) AssistantStep(AssistantCopy.opening("the trust map", "ट्रस्ट मैप"), AssistantAction.Navigate(ROUTE_TRUST))
            else AssistantStep(AssistantCopy.notInThisVersion("The trust map", "ट्रस्ट मैप"))
    }

    private fun checkApp(name: String?, ctx: AssistantContext): AssistantStep {
        busy(ctx)?.let { return it }
        if (name == null) return AssistantStep(AssistantCopy.whichApp, AssistantAction.Navigate(ROUTE_PICK))
        return when (val m = AppNameMatcher.match(name, ctx.apps)) {
            AppMatch.NotFound -> AssistantStep(AssistantCopy.notFound(name))
            is AppMatch.Ambiguous -> AssistantStep(AssistantCopy.whichOne(m.options[0].label, m.options[1].label), choices = m.options)
            is AppMatch.Found -> startFor(m.app, ctx)
        }
    }

    private fun busy(ctx: AssistantContext): AssistantStep? = when {
        ctx.auditRunning -> AssistantStep(AssistantCopy.alreadyRunning(ctx.runningLabel))
        ctx.quarantineActive -> AssistantStep(AssistantCopy.quarantineActive)
        else -> null
    }

    private fun startFor(app: AppCandidate, ctx: AssistantContext): AssistantStep {
        busy(ctx)?.let { return it }
        val r = ctx.readiness
        return when {
            !r.vpnReady -> AssistantStep(AssistantCopy.fixVpn(app.label), AssistantAction.FixSetup(SetupFix.VPN))
            !r.watcherOn -> AssistantStep(AssistantCopy.fixWatcher(app.label), AssistantAction.FixSetup(SetupFix.WATCHER))
            r.privateDnsActive -> AssistantStep(AssistantCopy.fixPrivateDns(app.label), AssistantAction.FixSetup(SetupFix.PRIVATE_DNS))
            else -> {
                val config = VoiceAuditOptions.config(app, ctx.automationAllowlist)
                AssistantStep(if (config.autoDrive) AssistantCopy.checkingDemo(app.label) else AssistantCopy.checking(app.label), AssistantAction.StartAudit(config))
            }
        }
    }
}
