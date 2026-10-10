package com.dpdpxray.app.voice.assistant

import com.dpdpxray.app.audit.AuditConfig
import com.dpdpxray.core.assistant.AppCandidate
import com.dpdpxray.core.model.AuditPlan

/**
 * The only audit a voice command may start: the same normal-timing Quick audit the app picker offers (one Accept
 * plan, 15 seconds of observation), keeping the app's data. A voice command never starts a Full audit, Fast Preview
 * or a data reset. The agent taps Accept itself only for the developer-owned LeakyShop demo apps, or for packages a
 * developer has explicitly put on an automation allowlist; for any other app the user makes the consent choice.
 */
object VoiceAuditOptions {
    val DEMO_PACKAGES = setOf("com.leakyshop.demo", "com.leakyshop.demo.fixed")

    fun autoTapAllowed(packageName: String, automationAllowlist: Set<String>): Boolean =
        packageName in DEMO_PACKAGES || packageName in automationAllowlist

    fun config(app: AppCandidate, automationAllowlist: Set<String> = emptySet()): AuditConfig = AuditConfig(
        targetPackage = app.packageName,
        targetLabel = app.label,
        plans = listOf(AuditPlan.ACCEPT),
        childFacing = false,
        includeGms = false,
        autoDrive = autoTapAllowed(app.packageName, automationAllowlist),
        resetAppData = false,
        fastPreview = false,
    )
}
