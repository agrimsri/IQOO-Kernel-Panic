package com.dpdpxray.app.audit

import com.dpdpxray.app.data.AuditMeta
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession

/**
 * The result's one-tap "Check again": the same check of the same app, started fresh. A restart keeps the app's data and
 * is offered for every app; a reset clears it and is offered only for an app the person develops (a debuggable build,
 * the LeakyShop demo, or one on the developer-runner reset list), never for a system app, and only after they confirm.
 */
object CheckAgain {
    enum class Mode { RESTART, RESET }

    val CONTROLLED_DEMOS = setOf("com.leakyshop.demo", "com.leakyshop.demo.fixed")

    fun resetAllowed(debuggable: Boolean, controlledDemo: Boolean, allowlisted: Boolean, system: Boolean): Boolean =
        !system && (debuggable || controlledDemo || allowlisted)

    /** Options the saved audit did not record fall back to the safe side: the person taps, no Play services lookups. */
    fun config(meta: AuditMeta, sessions: List<AuditSession>, mode: Mode): AuditConfig {
        val fromMeta = meta.plans.mapNotNull { label -> AuditPlan.entries.firstOrNull { it.label == label } }.distinct()
        val plans = fromMeta.ifEmpty { sessions.map { it.plan }.distinct() }.ifEmpty { listOf(AuditPlan.REJECT) }
        return AuditConfig(
            targetPackage = meta.targetPackage,
            targetLabel = meta.targetLabel,
            plans = plans,
            childFacing = sessions.any { it.childFacing },
            includeGms = meta.includeGms ?: false,
            autoDrive = meta.autoDrive ?: false,
            resetAppData = mode == Mode.RESET,
            fastPreview = meta.timingProfile == AuditTimingPolicy.FAST_PREVIEW && plans == listOf(AuditPlan.ACCEPT),
        )
    }
}
