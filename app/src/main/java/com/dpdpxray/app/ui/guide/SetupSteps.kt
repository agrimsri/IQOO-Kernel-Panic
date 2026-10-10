package com.dpdpxray.app.ui.guide

import com.dpdpxray.app.control.Preflight
import com.dpdpxray.app.data.AuditMeta
import com.dpdpxray.app.audit.AuditTimingPolicy
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.ScoreBand

enum class SetupKind { VPN, WATCHER, PRIVATE_DNS, SHIZUKU }

/** REQUIRED items are exactly [Preflight.canAudit]; RECOMMENDED affects coverage; OPTIONAL adds Full checks. */
enum class SetupNeed { REQUIRED, RECOMMENDED, OPTIONAL }

data class SetupItem(val kind: SetupKind, val need: SetupNeed, val done: Boolean, val title: String, val detail: String, val fixable: Boolean)

/** Turns the existing readiness checks into one plain-language step each. Same checks, same meaning as before. */
object SetupSteps {
    fun from(pf: Preflight): List<SetupItem> = listOf(
        if (pf.otherVpnActive) SetupItem(SetupKind.VPN, SetupNeed.REQUIRED, false, "Turn off your other VPN", "Android runs one VPN at a time. TraceVeil never switches yours off; turn it off for the check, then back on.", true)
        else if (pf.vpnReady) SetupItem(SetupKind.VPN, SetupNeed.REQUIRED, true, "VPN allowed", "TraceVeil can watch the lookups of the app you check.", false)
        else SetupItem(SetupKind.VPN, SetupNeed.REQUIRED, false, "Allow the VPN", "Needed to watch the lookups of the app you check. Only that app.", true),
        if (pf.accessibilityOn) SetupItem(SetupKind.WATCHER, SetupNeed.REQUIRED, true, "Consent watcher on", "TraceVeil can see the consent popup during a check.", false)
        else SetupItem(SetupKind.WATCHER, SetupNeed.REQUIRED, false, "Turn on the consent watcher", "In Accessibility, switch on \"TraceVeil consent watcher\".", true),
        if (pf.privateDnsActive) SetupItem(SetupKind.PRIVATE_DNS, SetupNeed.RECOMMENDED, false, "Turn off Private DNS", "While it's on, lookups may be hidden from the check.", true)
        else SetupItem(SetupKind.PRIVATE_DNS, SetupNeed.RECOMMENDED, true, "Private DNS off", "Lookups are visible to the check.", false),
        when {
            pf.shizukuGranted -> SetupItem(SetupKind.SHIZUKU, SetupNeed.OPTIONAL, true, "Fresh start ready",
                "Shizuku is ready. A reset happens only when you approve it in check setup.", false)
            pf.shizukuRunning -> SetupItem(SetupKind.SHIZUKU, SetupNeed.OPTIONAL, false, "Allow Shizuku",
                "Shizuku is running. Allow TraceVeil to use it for Full checks of your own test apps.", true)
            else -> SetupItem(SetupKind.SHIZUKU, SetupNeed.OPTIONAL, false, "Start Shizuku (optional)",
                "Only needed for Full checks (Reject and Accept) on your own test apps.", false)
        },
    )

    /** What still needs doing before a good check, in order: required first, then recommended. */
    fun todo(items: List<SetupItem>): List<SetupItem> =
        items.filter { !it.done && it.need != SetupNeed.OPTIONAL }.sortedBy { it.need.ordinal }
}

/** Plain labels for the Recent checks cards, from saved metadata only (no audit files are opened). */
object RecentChecks {
    fun scoreText(meta: AuditMeta): String = if (meta.band == ScoreBand.INCONCLUSIVE) "?" else meta.score?.toString() ?: "?"

    fun coverageWarning(meta: AuditMeta): String? = when {
        meta.sample -> null
        meta.band == ScoreBand.INCONCLUSIVE -> "Inconclusive: too little was captured"
        meta.timingProfile == AuditTimingPolicy.FAST_PREVIEW -> "Fast Preview: limited evidence"
        AuditPlan.REJECT.label !in meta.plans -> "Single run: Reject not tested"
        meta.timingProfile == null -> "Timing not recorded"
        else -> null
    }
}
