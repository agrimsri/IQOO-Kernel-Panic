package com.dpdpxray.app.ui.modes

import com.dpdpxray.app.audit.AuditConfig
import com.dpdpxray.app.audit.FoundSideEffect
import com.dpdpxray.app.audit.RestartPolicy
import com.dpdpxray.app.data.Audience
import com.dpdpxray.core.model.AuditPlan

/** The ways the check sheet can run an app. */
enum class CheckChoice { FULL, QUICK, PREVIEW }

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
        Audience.USER -> listOf(CheckChoice.QUICK, CheckChoice.FULL)
        Audience.DEVELOPER -> listOf(CheckChoice.FULL, CheckChoice.QUICK, CheckChoice.PREVIEW)
    }

    /** Full needs an approved fresh start; without one it falls back to the mode's single run. */
    fun plans(audience: Audience, choice: CheckChoice, freshReset: Boolean): List<AuditPlan> = when {
        choice == CheckChoice.FULL && freshReset -> listOf(AuditPlan.SILENT, AuditPlan.REJECT, AuditPlan.ACCEPT)
        choice == CheckChoice.PREVIEW -> listOf(AuditPlan.ACCEPT)
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

    /** App data is cleared only when the person approved it and Shizuku can do it. */
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
    ): AuditConfig {
        val freshReset = allowReset && shizukuReady
        return AuditConfig(
            targetPackage = targetPackage, targetLabel = targetLabel,
            plans = plans(audience, choice, freshReset),
            childFacing = childFacing, includeGms = includeGms, autoDrive = agentTaps, resetAppData = freshReset,
            fastPreview = choice == CheckChoice.PREVIEW,
            restart = restart,
        )
    }
}
