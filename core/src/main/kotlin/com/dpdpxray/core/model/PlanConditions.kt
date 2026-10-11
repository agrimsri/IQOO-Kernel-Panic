package com.dpdpxray.core.model

import kotlinx.serialization.Serializable

/** How the audited app was started for one plan. Saved by name: add new values at the end and never rename one. */
@Serializable
enum class StartMode {
    /** Stopped, its data cleared with the person's approval, then opened: the state of a fresh install. */
    RESET,

    /** Stopped, then opened again with its data kept: a new process, so new lookups and new connections. */
    RESTARTED,

    /** Opened without stopping it first (no Shizuku, or stopping failed): it may have been running already. */
    NOT_RESTARTED,
}

/**
 * Conditions on the phone around one plan, read so a result with too few lookups can say why instead of a bare
 * "Inconclusive" (see `VisibilityDiagnosis`). Nothing here changes a finding or the score on its own.
 *
 * Every field is nullable: null means "not read" (an older audit, or not readable on this phone), never "no".
 */
@Serializable
data class PlanConditions(
    val startMode: StartMode? = null,
    /** Why the app could not be restarted (or reset) before the plan, in plain words. Null when it was, or not recorded. */
    val restartProblem: String? = null,
    /** The app's process was running just before the plan started (read through Shizuku). Null = not readable. */
    val runningBeforeStart: Boolean? = null,
    /** Android's Private DNS was in use at the plan's start and end. */
    val privateDnsAtStart: Boolean? = null,
    val privateDnsAtEnd: Boolean? = null,
    /** Another app's VPN was up when the plan ended (it can take the VPN slot from TraceVeil). */
    val otherVpnAtEnd: Boolean? = null,
    /** The phone had a working (validated) internet connection at the plan's start and end. */
    val networkAtStart: Boolean? = null,
    val networkAtEnd: Boolean? = null,
    /** The phone was locked, or its screen off, at one of the moments the plan checked. */
    val phoneLocked: Boolean? = null,
    /** Android showed its "keeps stopping" or "isn't responding" dialog for the app during the plan. */
    val appCrashed: Boolean? = null,
    /** The audited app's own network use during the plan, when the phone lets TraceVeil read it. */
    val appTraffic: AppTraffic? = null,
    /** The audited app is a system (preinstalled) app. */
    val systemApp: Boolean? = null,
    /** The first screen that stood between launch and the consent screen, and what was done about it. */
    val blocker: BlockedStep? = null,
    /** How many times the consent screen came back after the plan's choice (all of them, not only the ones kept). */
    val consentShownAgain: Int? = null,
)

/**
 * The audited app's own network use during one plan. [rxBytes]/[txBytes] come from Android's per-app counters, which
 * Android reports only for the calling app on current versions, so they are usually null (unknown). [openConnections] is
 * a count of the app's open TCP/UDP sockets to non-local addresses, read from the kernel's socket table through Shizuku;
 * only the count is kept, never an address. Null = not readable.
 */
@Serializable
data class AppTraffic(
    val rxBytes: Long? = null,
    val txBytes: Long? = null,
    val openConnections: Int? = null,
) {
    /** True when a reading shows network use, false when every reading taken shows none, null when nothing was read. */
    val active: Boolean?
        get() {
            val readings = listOfNotNull(rxBytes, txBytes, openConnections?.toLong())
            if (readings.isEmpty()) return null
            return readings.any { it > 0 }
        }
}

/**
 * A sign-in, verification-code, CAPTCHA, biometric, details-form or Android permission screen that stood between the
 * app's launch and its consent screen. [kind] is a `BlockerKind` name.
 */
@Serializable
data class BlockedStep(
    val kind: String,
    /** Skip or continue buttons the agent tapped (as the app labels them), including a form it filled with test values. */
    val agentTapped: List<String> = emptyList(),
    /** The person was asked to complete the step themselves. */
    val handedOff: Boolean = false,
    /** Time spent behind the step, which did not count against the plan's consent-screen wait. */
    val waitedMs: Long = 0,
    /** The app's consent screen was reached afterwards. */
    val consentReached: Boolean = false,
)
