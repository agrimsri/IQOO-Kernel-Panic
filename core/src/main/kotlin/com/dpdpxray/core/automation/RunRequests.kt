package com.dpdpxray.core.automation

/** The intent extras exactly as received. Nothing here is trusted until [RunRequests.decide] accepts it. */
data class RunRequestInput(
    val token: String?,
    val packageName: String?,
    val mode: String?,
    /** A boolean extra, or the string "true"/"false"; anything else is refused. Absent means false. */
    val autoTap: Any?,
    val childFacing: Any?,
    val runId: String?,
)

/** A validated run request. */
data class RunRequest(
    val runId: String,
    val packageName: String,
    val mode: AutomationMode,
    val autoTap: Boolean,
    val childFacing: Boolean,
)

/** The phone's state when the request arrives; every item is something the person set up themselves. */
data class PhoneReadiness(
    val automationEnabled: Boolean,
    val pairingToken: String?,
    val ownPackage: String,
    /** Test apps the person allowed the runner to reset. Empty by default. */
    val resetAllowlist: Set<String>,
    val vpnPrepared: Boolean,
    val consentWatcherOn: Boolean,
    /** Shizuku is running and has allowed TraceVeil (needed to clear a test app's data). */
    val shizukuReady: Boolean,
    /** What is already running ("an audit of X", "a quarantine run"), or null when the phone is idle. */
    val busy: String?,
)

enum class RefusalCode {
    BAD_RUN_ID, RUN_ID_USED, AUTOMATION_OFF, BAD_TOKEN, BAD_REQUEST, SELF, NOT_INSTALLED, NOT_LAUNCHABLE,
    NOT_ALLOWLISTED, NO_SHIZUKU, VPN_NOT_PREPARED, WATCHER_OFF, BUSY, NO_STORAGE,

    /** Set after acceptance: the audit stopped with an error, was stopped on the phone, or its result was unreadable. */
    AUDIT_FAILED, CANCELLED, RESULT_UNREADABLE,
}

sealed interface RunDecision {
    /** [resetAppData]: the app is allowlisted and Shizuku is ready, so each plan starts from cleared app data. */
    data class Accepted(val request: RunRequest, val resetAppData: Boolean) : RunDecision

    /**
     * [runId] is where the failed status may be written, or null when there is no safe place: the id is invalid, or it
     * belongs to an earlier run whose files must not be overwritten.
     */
    data class Refused(val runId: String?, val code: RefusalCode, val reason: String, val fix: String) : RunDecision
}

/**
 * Decides whether a runner request may start an audit. Order matters: the run id first (it says where to report),
 * then automation on/off and the token, and only then anything about the package, so a caller without the token
 * learns nothing about installed apps.
 */
object RunRequests {
    private const val SETTINGS = "TraceVeil > Settings > Developer automation"

    fun decide(
        input: RunRequestInput,
        phone: PhoneReadiness,
        isInstalled: (String) -> Boolean,
        isLaunchable: (String) -> Boolean,
        runIdUsed: (String) -> Boolean,
    ): RunDecision {
        val runId = input.runId
        if (!AutomationContract.isValidRunId(runId)) {
            return RunDecision.Refused(
                null, RefusalCode.BAD_RUN_ID,
                "The run request has no valid runId (1 to ${AutomationContract.MAX_RUN_ID} letters, digits, _ or -), so TraceVeil has nowhere to report.",
                "Start runs with traceveil-ci run; it creates a new run id each time.",
            )
        }
        runId!!
        if (runIdUsed(runId)) {
            return RunDecision.Refused(
                null, RefusalCode.RUN_ID_USED,
                "Run id $runId was already used on this phone; TraceVeil does not overwrite an earlier run's files.",
                "Start a new run; traceveil-ci run creates a new run id each time.",
            )
        }
        fun refuse(code: RefusalCode, reason: String, fix: String) = RunDecision.Refused(runId, code, reason, fix)

        if (!phone.automationEnabled) {
            return refuse(RefusalCode.AUTOMATION_OFF, "Developer automation is off on this phone.",
                "On the phone: $SETTINGS, switch it on, then copy the pairing token to the laptop.")
        }
        if (!PairingTokens.matches(phone.pairingToken, input.token)) {
            val reason = if (input.token.isNullOrEmpty()) "The run request has no pairing token." else "The pairing token does not match this phone's token."
            return refuse(RefusalCode.BAD_TOKEN, reason,
                "Copy the token from $SETTINGS and pass it with --token or the TRACEVEIL_TOKEN variable. After Regenerate, the old token stops working.")
        }

        val pkg = input.packageName
        if (!AutomationContract.isValidPackage(pkg)) {
            return refuse(RefusalCode.BAD_REQUEST, "The package name is missing or is not a valid Android package name.",
                "Pass the test app's package name, for example --package com.example.app.")
        }
        pkg!!
        val mode = AutomationMode.parse(input.mode) ?: return refuse(
            RefusalCode.BAD_REQUEST, "The mode is missing or unknown (expected full, quick or preview).", "Pass --mode full or --mode quick.",
        )
        val autoTap = flag(input.autoTap) ?: return refuse(RefusalCode.BAD_REQUEST, "autoTap must be true or false.", "Send autoTap as a boolean extra (--ez autoTap true).")
        val childFacing = flag(input.childFacing) ?: return refuse(RefusalCode.BAD_REQUEST, "childFacing must be true or false.", "Send childFacing as a boolean extra (--ez childFacing true).")

        if (pkg == phone.ownPackage) {
            return refuse(RefusalCode.SELF, "TraceVeil cannot check itself.", "Pass the package of the app under test.")
        }
        if (!isInstalled(pkg)) {
            return refuse(RefusalCode.NOT_INSTALLED, "$pkg is not installed on this phone.",
                "Install the test build first; traceveil-ci run --apk <file.apk> installs it with adb install -r.")
        }
        if (!isLaunchable(pkg)) {
            return refuse(RefusalCode.NOT_LAUNCHABLE, "$pkg has no launcher screen, so TraceVeil cannot open it for a check.",
                "Check an app that opens from the launcher.")
        }
        val allowlisted = pkg in phone.resetAllowlist
        if (mode == AutomationMode.FULL && !allowlisted) {
            return refuse(RefusalCode.NOT_ALLOWLISTED,
                "A full run clears $pkg's local data before each of its three plans, and $pkg is not on this phone's list of test apps the runner may reset.",
                "On the phone: $SETTINGS > Test apps the runner may reset > Add, and pick this app (only your own test builds). Or run with --mode quick, which keeps the app's data.")
        }
        if (mode == AutomationMode.FULL && !phone.shizukuReady) {
            return refuse(RefusalCode.NO_SHIZUKU,
                "A full run needs Shizuku to clear the test app's data, and Shizuku is not running or has not allowed TraceVeil.",
                "On the phone: start Shizuku and allow TraceVeil in it (TraceVeil Home shows \"Fresh start ready\"), then run again. Or run with --mode quick.")
        }
        if (!phone.vpnPrepared) {
            return refuse(RefusalCode.VPN_NOT_PREPARED, "Android has not allowed TraceVeil's VPN yet.",
                "On the phone: open TraceVeil, tap \"Allow the VPN\" on Home and accept Android's request once. The runner never accepts it for you.")
        }
        if (!phone.consentWatcherOn) {
            return refuse(RefusalCode.WATCHER_OFF, "The TraceVeil consent watcher (accessibility service) is off.",
                "On the phone: Settings > Accessibility > TraceVeil consent watcher > On. TraceVeil never switches it on for you.")
        }
        phone.busy?.let { what ->
            return refuse(RefusalCode.BUSY, "Another check is already running on this phone ($what).",
                "Let it finish or stop it on the phone, then run again.")
        }
        val request = RunRequest(runId, pkg, mode, autoTap, childFacing)
        return RunDecision.Accepted(request, resetAppData = allowlisted && phone.shizukuReady)
    }

    /** true/false from a boolean extra or the exact strings "true"/"false" (any case); null when invalid. Absent is false. */
    private fun flag(value: Any?): Boolean? = when (value) {
        null -> false
        is Boolean -> value
        is String -> when (value.lowercase()) { "true" -> true; "false" -> false; else -> null }
        else -> null
    }
}
