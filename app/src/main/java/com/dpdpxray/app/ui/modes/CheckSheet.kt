package com.dpdpxray.app.ui.modes

import com.dpdpxray.app.audit.AuditConfig
import com.dpdpxray.app.audit.FoundSideEffect
import com.dpdpxray.app.audit.RestartPolicy
import com.dpdpxray.app.data.Audience
import com.dpdpxray.app.ui.guide.Bi
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.BackgroundWatches

/**
 * The ways the check sheet can run an app. WATCH: a background watch, one long capture of the app running in the
 * background with no launch, restart, tap or clearing.
 */
enum class CheckChoice { FULL, QUICK, PREVIEW, WATCH }

/** What the check sheet shows selected when it opens. */
data class CheckSheetSetup(val choice: CheckChoice, val allowReset: Boolean, val agentTaps: Boolean)

/**
 * The check sheet's defaults and the runs each choice means, per mode. Pure, so both front doors are tested.
 *
 * Developer mode keeps the sheet exactly as it was. Everyday mode always opens on Quick with clearing off (Full and
 * clearing sit under Advanced), and its Quick check says No: the Everyday card asks "Does this app respect my 'No'?",
 * and only a run that taps Reject can answer that. A Quick check that taps Accept would leave refusal untested.
 */
object CheckSheet {
    fun defaults(audience: Audience, controlledDemo: Boolean, shizukuReady: Boolean, preferPreview: Boolean): CheckSheetSetup =
        when (audience) {
            Audience.DEVELOPER -> CheckSheetSetup(
                choice = when {
                    shizukuReady && controlledDemo -> CheckChoice.FULL
                    preferPreview -> CheckChoice.PREVIEW
                    else -> CheckChoice.QUICK
                },
                allowReset = controlledDemo,
                agentTaps = controlledDemo,
            )
            Audience.USER -> CheckSheetSetup(CheckChoice.QUICK, allowReset = false, agentTaps = controlledDemo)
        }

    /** Choices the sheet lists, in order. Fast Preview (an Accept-only run) stays a developer tool. */
    fun offered(audience: Audience): List<CheckChoice> = when (audience) {
        Audience.USER -> listOf(CheckChoice.QUICK, CheckChoice.FULL, CheckChoice.WATCH)
        Audience.DEVELOPER -> listOf(CheckChoice.FULL, CheckChoice.QUICK, CheckChoice.PREVIEW, CheckChoice.WATCH)
    }

    /** Full needs an approved fresh start; without one it falls back to the mode's single run. */
    fun plans(audience: Audience, choice: CheckChoice, freshReset: Boolean): List<AuditPlan> = when {
        choice == CheckChoice.FULL && freshReset -> listOf(AuditPlan.SILENT, AuditPlan.REJECT, AuditPlan.ACCEPT)
        choice == CheckChoice.PREVIEW -> listOf(AuditPlan.ACCEPT)
        choice == CheckChoice.WATCH -> listOf(AuditPlan.SILENT)
        audience == Audience.USER -> listOf(AuditPlan.REJECT)
        else -> listOf(AuditPlan.ACCEPT)
    }

    /**
     * The sheet's restart choice. Until the phone has been read ([effects] null), or when nothing would be switched off,
     * the orchestrator decides (AUTO: it reads again and leaves such an app running). Otherwise the person's choice:
     * "Check without restarting" (the default) or "Restart anyway".
     */
    fun restartPolicy(effects: List<FoundSideEffect>?, restartAnyway: Boolean): RestartPolicy = when {
        effects.isNullOrEmpty() -> RestartPolicy.AUTO
        restartAnyway -> RestartPolicy.ALWAYS
        else -> RestartPolicy.SKIP
    }

    /**
     * Choosing Full may turn clearing on by itself only for TraceVeil's own demo apps. For any other app (including a
     * debuggable build, which may well be someone's personal app) clearing waits for [resetConfirmText] to be confirmed.
     */
    fun resetNeedsConfirmation(pkg: String): Boolean = pkg !in com.dpdpxray.app.audit.CheckAgain.CONTROLLED_DEMOS

    data class ResetConfirm(val title: Bi, val body: Bi, val confirm: Bi, val cancel: Bi)

    /** The confirmation before TraceVeil may clear an app's data: it names the app and what is lost. */
    fun resetConfirmText(app: String) = ResetConfirm(
        title = Bi("Clear all of $app's data?", "$app का पूरा डेटा मिटाएँ?"),
        body = Bi(
            "A Full check clears $app's data before each of its three runs: accounts, logins, settings and anything not yet " +
                "uploaded. This cannot be undone. Use it only for a test app you can set up again. For an app you use, choose " +
                "Quick: it keeps the app's data.",
            "Full जाँच अपने तीनों रन से पहले $app का डेटा मिटाती है: अकाउंट, लॉगिन, सेटिंग और जो कुछ अभी अपलोड नहीं हुआ। " +
                "इसे वापस नहीं लाया जा सकता। इसे सिर्फ़ ऐसे टेस्ट ऐप के लिए चुनें जिसे आप फिर से सेट कर सकें। जिस ऐप को आप " +
                "इस्तेमाल करते हैं, उसके लिए Quick चुनें: वह ऐप का डेटा रखता है।",
        ),
        confirm = Bi("Clear $app's data", "$app का डेटा मिटाएँ"),
        cancel = Bi("Keep its data", "डेटा रखें"),
    )

    /**
     * A background watch of [minutes]: one no-interaction plan, the app's data kept, no tap. It never restarts the app (the
     * orchestrator does not stop it for a watch), so it needs neither Shizuku nor the restart safeguard.
     */
    fun watchConfig(
        targetPackage: String, targetLabel: String, minutes: Int = BackgroundWatches.DEFAULT_MINUTES, includeGms: Boolean = false,
        childFacing: Boolean = false,
    ): AuditConfig {
        require(minutes > 0)
        return AuditConfig(
            targetPackage = targetPackage, targetLabel = targetLabel, plans = listOf(AuditPlan.SILENT),
            childFacing = childFacing, includeGms = includeGms, autoDrive = false, resetAppData = false,
            watchMs = minutes * 60_000L,
        )
    }

    /** App data is cleared only for a Full check, when the person approved it and Shizuku can do it; Quick, Preview and a watch never clear. */
    fun config(
        audience: Audience,
        targetPackage: String,
        targetLabel: String,
        choice: CheckChoice,
        allowReset: Boolean,
        shizukuReady: Boolean,
        agentTaps: Boolean,
        childFacing: Boolean,
        includeGms: Boolean,
        restart: RestartPolicy = RestartPolicy.AUTO,
        watchMinutes: Int = BackgroundWatches.DEFAULT_MINUTES,
    ): AuditConfig {
        if (choice == CheckChoice.WATCH) return watchConfig(targetPackage, targetLabel, watchMinutes, includeGms, childFacing)
        val freshReset = allowReset && shizukuReady
        return AuditConfig(
            targetPackage = targetPackage, targetLabel = targetLabel,
            plans = plans(audience, choice, freshReset),
            childFacing = childFacing, includeGms = includeGms, autoDrive = agentTaps, resetAppData = freshReset && choice == CheckChoice.FULL,
            fastPreview = choice == CheckChoice.PREVIEW,
            restart = restart,
        )
    }
}
