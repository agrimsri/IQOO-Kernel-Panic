package com.dpdpxray.cli

import com.dpdpxray.core.automation.AutomationContract
import com.dpdpxray.core.automation.AutomationMode
import com.dpdpxray.core.automation.AutomationStatus
import com.dpdpxray.core.automation.AutomationStatuses
import com.dpdpxray.core.automation.BundleSignatures
import com.dpdpxray.core.automation.RefusalCode
import com.dpdpxray.core.automation.RunState
import com.dpdpxray.core.model.ScoreBand
import java.io.File

/** Time source for polling; tests move it forward instead of sleeping. */
interface RunClock {
    fun nowMs(): Long
    fun sleep(ms: Long)
}

object SystemRunClock : RunClock {
    override fun nowMs(): Long = System.currentTimeMillis()
    override fun sleep(ms: Long) = Thread.sleep(ms)
}

/** A validated run: what to start on which phone, and where to save the evidence. */
data class RunPlan(
    val packageName: String,
    val device: String?,
    val apk: File?,
    val mode: AutomationMode,
    val token: String,
    val childFacing: Boolean,
    val timeoutMs: Long,
    val runId: String,
    val runDir: File,
)

/** Anything that stops a run before a policy result: exit code 2. [fix] says what to change. */
class RunFailure(message: String, val fix: String?, val status: AutomationStatus? = null) : Exception(message)

class DeviceRunResult(val serial: String, val status: AutomationStatus, val bundleFile: File, val statusFile: File)

/**
 * Drives one run over adb: preflight (adb, phone, TraceVeil, optional install, target), the start intent, polling
 * status.json every [pollMs], and pulling bundle.json. It never changes a phone setting, grants a permission or
 * deletes anything on the phone; the phone itself decides whether to accept the request.
 */
class DeviceRunner(
    private val adb: Adb,
    private val adbName: String,
    private val clock: RunClock,
    private val log: (String) -> Unit,
    private val pollMs: Long = POLL_MS,
    private val graceMs: Long = STATUS_GRACE_MS,
) {
    /** The phone in use once chosen, for reports. */
    var serial: String? = null
        private set

    fun run(p: RunPlan): DeviceRunResult {
        val version = call(AdbCommands.version(), SHORT_MS)
        if (!version.ok) throw RunFailure("adb did not run: ${AdbOutput.firstLine(version)}", FIX_ADB)
        log("adb:      ${AdbOutput.firstLine(version)}")

        val s = pickDevice(p.device)
        serial = s
        log("Device:   $s")
        if (!installed(s, AutomationContract.APP_PACKAGE)) {
            throw RunFailure("TraceVeil (${AutomationContract.APP_PACKAGE}) is not installed on $s.", "Install TraceVeil on the test phone and do the one-time setup in docs/ci/RUNNER.md.")
        }
        log("TraceVeil: installed")
        val before = p.apk?.let { apkPaths(s, p.packageName) }
        p.apk?.let { install(s, it) }
        val after = apkPaths(s, p.packageName)
        if (after.isEmpty()) {
            val fix = if (p.apk != null) "The APK passed with --apk installed, but it is not ${p.packageName}; check --package."
            else "Install the test build first, or pass --apk <file.apk> to install it with adb install -r."
            throw RunFailure("${p.packageName} is not installed on $s.", fix)
        }
        // Android gives every install a new folder, so an unchanged one means the APK did not update this package: the
        // run would check the older build, and a gate must not pass a build it never installed.
        if (!before.isNullOrEmpty() && before == after) {
            throw RunFailure(
                "${p.packageName} did not change on the phone after adb install -r, so the APK is probably a different app and the run would check the older build.",
                "Check that --package is the package name inside the APK.",
            )
        }
        log("Target:   ${p.packageName} (installed)")

        val start = AdbCommands.startRun(s, StartRequest(p.token, p.packageName, p.mode.wire, autoTap = true, childFacing = p.childFacing, runId = p.runId))
        log("Start:    ${AdbCommands.display(adbName, start)}")
        val startedMs = clock.nowMs()
        checkStarted(s, call(start, SHORT_MS))

        val (status, statusText) = poll(s, p, startedMs)
        p.runDir.mkdirs()
        val statusFile = File(p.runDir, AutomationContract.STATUS_FILE).apply { writeText(statusText.trimEnd() + "\n", Charsets.UTF_8) }
        if (status.packageName != null && status.packageName != p.packageName) {
            throw RunFailure("The phone ran ${status.packageName}, not ${p.packageName}.", null, status)
        }
        if (status.state == RunState.FAILED) {
            val code = status.reasonCode
            val what = if (code in AFTER_START) "The check did not finish on the phone" else "The phone refused the run"
            throw RunFailure("$what: ${status.reason ?: "no reason given"}" + (code?.let { " [$it]" } ?: ""), status.fix, status)
        }

        val bundleFile = File(p.runDir, AutomationContract.BUNDLE_FILE)
        bundleFile.delete()
        val pulled = call(AdbCommands.pullBundle(s, p.runId, bundleFile.absolutePath), PULL_MS)
        if (!pulled.ok || !bundleFile.isFile) {
            throw RunFailure(
                "Could not copy bundle.json from the phone: ${AdbOutput.firstLine(pulled)}",
                "The audit is saved on the phone${status.auditId?.let { " ($it)" } ?: ""}; open it there and use Export audit data (JSON) if needed.",
                status,
            )
        }
        // Only TraceVeil, holding the token that started this run, can sign the bundle; anything else is not gated.
        if (!BundleSignatures.verify(p.token, bundleFile.readBytes(), status.bundleSignature)) {
            throw RunFailure(
                "bundle.json is not signed with this run's pairing token, so it was not written by TraceVeil for this run (or it was changed). It was not gated.",
                "Run again. If it repeats, check which apps on the test phone have shell rights through Shizuku, and use matching TraceVeil and traceveil-ci builds.",
                status,
            )
        }
        log("Verified: bundle.json is signed with this run's pairing token")
        return DeviceRunResult(s, status, bundleFile, statusFile)
    }

    private fun call(args: List<String>, timeoutMs: Long): ExecResult = adb.exec(args, timeoutMs)

    private fun pickDevice(requested: String?): String {
        val r = call(AdbCommands.devices(), SHORT_MS)
        if (!r.ok) throw RunFailure("adb devices failed: ${AdbOutput.firstLine(r)}", FIX_ADB)
        val all = AdbOutput.devices(r.out)
        val listed = all.joinToString { it.serial }.ifEmpty { "none" }
        val target = if (requested != null) {
            all.firstOrNull { it.serial == requested }
                ?: throw RunFailure("$requested is not connected (adb devices lists: $listed).", "Connect the phone over USB, unlock it and check its serial with adb devices.")
        } else when (all.size) {
            0 -> throw RunFailure("No phone is connected (adb devices lists none).", "Connect the test phone over USB, unlock it and allow USB debugging.")
            1 -> all.single()
            else -> throw RunFailure("${all.size} phones are connected ($listed); choose one.", "Pass --device SERIAL (or set ANDROID_SERIAL).")
        }
        when (target.state) {
            "device" -> Unit
            "unauthorized" -> throw RunFailure(
                "${target.serial} is unauthorized: the phone has not allowed USB debugging from this computer.",
                "Unlock the phone and tap Allow USB debugging (tick Always allow from this computer for a CI runner).",
            )
            "offline" -> throw RunFailure("${target.serial} is offline.", "Reconnect the USB cable (or run adb reconnect) and try again.")
            else -> throw RunFailure("${target.serial} is ${target.state}, not ready.", "Wait until adb devices lists it as \"device\".")
        }
        return target.serial
    }

    private fun installed(serial: String, pkg: String): Boolean = apkPaths(serial, pkg).isNotEmpty()

    private fun apkPaths(serial: String, pkg: String): List<String> {
        val r = call(AdbCommands.packagePath(serial, pkg), SHORT_MS)
        AdbOutput.transportProblem(r)?.let { throw RunFailure("Lost contact with $serial: $it.", FIX_USB) }
        return AdbOutput.installedPaths(r)
    }

    private fun install(serial: String, apk: File) {
        log("Install:  adb install -r ${apk.path}")
        val r = call(AdbCommands.install(serial, apk.absolutePath), INSTALL_MS)
        if (!r.ok || !r.text.contains("Success")) {
            val line = r.text.lineSequence().map { it.trim() }.firstOrNull { "Failure" in it || "failed" in it || "error" in it } ?: AdbOutput.firstLine(r)
            throw RunFailure("adb install -r failed: $line", "Fix the APK, or uninstall the older build on the phone if Android refuses the update, then run again.")
        }
    }

    private fun checkStarted(serial: String, r: ExecResult) {
        AdbOutput.transportProblem(r)?.let { throw RunFailure("Lost contact with $serial: $it.", FIX_USB) }
        val t = r.text
        fun line(vararg keys: String) = t.lineSequence().map { it.trim() }.firstOrNull { l -> keys.any { it in l } } ?: AdbOutput.firstLine(r)
        when {
            "does not exist" in t -> throw RunFailure(
                "This TraceVeil build has no Developer automation (${line("does not exist")}).",
                "Install a TraceVeil build that includes Developer automation, then do the one-time setup in docs/ci/RUNNER.md.",
            )
            "Permission Denial" in t || "SecurityException" in t -> throw RunFailure(
                "Android refused to open TraceVeil's runner screen: ${line("Permission Denial", "SecurityException")}",
                "The runner screen accepts requests from adb (the shell user) only: run traceveil-ci on a computer connected over adb.",
            )
            "Activity not started" in t -> throw RunFailure(
                "Android brought an earlier TraceVeil screen to the front instead of starting the run (${line("Activity not started")}).",
                "Close TraceVeil's runner screen on the phone (or force-stop TraceVeil) and run again; if it repeats, update TraceVeil and traceveil-ci together.",
            )
            !r.ok || "Error" in t || "Exception" in t -> throw RunFailure("adb could not start the run: ${line("Error", "Exception")}", "Unlock the phone and run again.")
        }
    }

    /** Reads status.json every [pollMs] until the run is done or failed. Returns the status and its exact text. */
    private fun poll(s: String, p: RunPlan, startedMs: Long): Pair<AutomationStatus, String> {
        val deadline = startedMs + p.timeoutMs
        var seen = false
        var failures = 0
        var unreadable = 0
        var shown: String? = null
        while (true) {
            val r = call(AdbCommands.readStatus(s, p.runId), SHORT_MS)
            val elapsedMs = clock.nowMs() - startedMs
            when {
                r.ok -> {
                    failures = 0
                    val status = try {
                        AutomationStatuses.decode(r.out)
                    } catch (e: IllegalArgumentException) {
                        // A torn read is retried; the phone replaces the file atomically, so this should not last.
                        if (++unreadable >= MAX_UNREADABLE) {
                            throw RunFailure("status.json on the phone could not be read: ${e.message}", "Use TraceVeil and traceveil-ci builds from the same release.")
                        }
                        null
                    }
                    if (status != null) {
                        unreadable = 0
                        if (status.runId != p.runId) throw RunFailure("The phone's status.json belongs to run ${status.runId}, not ${p.runId}.", null, status)
                        seen = true
                        val line = describe(status)
                        if (line != shown) {
                            log("[%4ds] %s".format(elapsedMs / 1000, line))
                            shown = line
                        }
                        if (status.state != RunState.RUNNING) return status to r.out
                    }
                }
                AdbOutput.isMissingFile(r) -> failures = 0
                AdbOutput.isPermissionDenied(r) -> throw RunFailure(
                    "adb cannot read TraceVeil's run folder (${AutomationContract.deviceRunDir(p.runId)}): Permission denied.",
                    "This Android build does not let adb read app folders under Android/data; see Limits in docs/ci/RUNNER.md.",
                )
                else -> if (++failures >= MAX_FAILURES) {
                    throw RunFailure(
                        "Lost contact with $s while waiting for the check: ${AdbOutput.transportProblem(r) ?: AdbOutput.firstLine(r)}.",
                        "Check the USB cable and keep the phone connected; the check may still finish on the phone, and its result stays there.",
                    )
                }
            }
            if (!seen && elapsedMs >= graceMs) {
                throw RunFailure(
                    "TraceVeil wrote no status within ${graceMs / 1000} s of the run request, so the request did not reach the app.",
                    "Look at the phone: unlock it and check that TraceVeil opened, then run again.",
                )
            }
            if (clock.nowMs() >= deadline) {
                throw RunFailure(
                    "Timed out after ${p.timeoutMs / 1000} s waiting for the phone (last status: ${shown ?: "none"}).",
                    "The check may still be running on the phone and saves its result there. Allow longer with --timeout, and watch the phone for a step that does not move.",
                )
            }
            clock.sleep(pollMs)
        }
    }

    companion object {
        const val POLL_MS = 2_000L
        const val STATUS_GRACE_MS = 30_000L
        const val SHORT_MS = 30_000L
        const val INSTALL_MS = 300_000L
        const val PULL_MS = 120_000L
        private const val MAX_FAILURES = 3
        private const val MAX_UNREADABLE = 5
        private val AFTER_START = setOf(RefusalCode.AUDIT_FAILED.name, RefusalCode.CANCELLED.name, RefusalCode.RESULT_UNREADABLE.name)

        const val FIX_ADB = "Install Android platform-tools and put adb on PATH, or pass --adb <path to adb> (or set ADB)."
        const val FIX_USB = "Check the USB cable, unlock the phone and run adb devices."

        /** One progress line: "running · <step>", "done · <audit> · <score> · <n> findings" or "failed · <reason>". */
        fun describe(s: AutomationStatus): String = when (s.state) {
            RunState.RUNNING -> "running · ${s.step ?: "started"}"
            RunState.DONE -> "done · ${s.auditId ?: "audit"} · ${score(s)} · ${s.findingCount ?: "?"} finding" + if (s.findingCount == 1) "" else "s"
            RunState.FAILED -> "failed · ${s.reason ?: "no reason given"}"
        }

        private fun score(s: AutomationStatus): String {
            val band = runCatching { ScoreBand.valueOf(s.band.orEmpty()) }.getOrNull()
            return when {
                band == ScoreBand.INCONCLUSIVE || s.score == null -> "score not established (inconclusive)"
                band != null -> "${s.score}/100 · ${Wording.band(band)}"
                else -> "${s.score}/100"
            }
        }
    }
}
