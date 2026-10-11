package com.dpdpxray.cli

import com.dpdpxray.core.automation.AutomationContract
import com.dpdpxray.core.evidence.EvidenceSignatures
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Random
import java.util.concurrent.TimeUnit

/** One adb invocation's result. [exitCode] is null when adb was stopped after its time limit. */
data class ExecResult(val exitCode: Int?, val out: String, val err: String = "") {
    val ok: Boolean get() = exitCode == 0
    val text: String get() = listOf(out, err).filter { it.isNotBlank() }.joinToString("\n").trim()
}

/** Every adb call of the runner goes through this, so tests use a scripted phone instead of a device. */
interface Adb {
    /** Runs adb with [args] (without the adb executable itself). Throws [AdbUnavailableException] when adb cannot start. */
    fun exec(args: List<String>, timeoutMs: Long): ExecResult
}

class AdbUnavailableException(message: String) : Exception(message)

/** The real adb, started as a child process with an argument list (no local shell is involved). */
class ProcessAdb(private val executable: String) : Adb {
    override fun exec(args: List<String>, timeoutMs: Long): ExecResult {
        val process = try {
            ProcessBuilder(listOf(executable) + args).start()
        } catch (e: IOException) {
            throw AdbUnavailableException("Could not run adb (\"$executable\"): ${e.message}")
        }
        process.outputStream.close()
        val out = Drain(process.inputStream)
        val err = Drain(process.errorStream)
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        // A freshly started adb server can keep the pipes open after adb itself exits: wait briefly, keep what was read.
        out.join(); err.join()
        return ExecResult(if (finished) process.exitValue() else null, out.text(), err.text())
    }

    private class Drain(private val stream: InputStream) {
        private val buffer = ByteArrayOutputStream()
        private val thread = Thread {
            runCatching {
                val chunk = ByteArray(8192)
                while (true) {
                    val n = stream.read(chunk)
                    if (n < 0) break
                    synchronized(buffer) { buffer.write(chunk, 0, n) }
                }
            }
        }.apply { isDaemon = true; start() }

        fun join() = thread.join(2_000)
        fun text(): String = synchronized(buffer) { buffer.toString(Charsets.UTF_8) }
    }
}

/** What the runner asks the phone to do; [mode] is the wire name ("full", "quick"). */
data class StartRequest(
    val token: String,
    val packageName: String,
    val mode: String,
    val autoTap: Boolean,
    val childFacing: Boolean,
    val runId: String,
)

/**
 * The runner's exact adb argument lists. `adb shell` joins its arguments into one command line for the phone's sh, so
 * every value that goes through the device shell is single-quoted ([DeviceShell.quote]); values are also validated
 * before they get here. `adb install` and `adb pull` take literal paths and are not quoted.
 */
object AdbCommands {
    private const val HIDDEN = "'<hidden>'"

    fun version() = listOf("version")
    fun devices() = listOf("devices")

    fun packagePath(serial: String, pkg: String) = listOf("-s", serial, "shell", "pm", "path", DeviceShell.quote(pkg))

    fun install(serial: String, apkPath: String) = listOf("-s", serial, "install", "-r", apkPath)

    fun startRun(serial: String, r: StartRequest): List<String> = listOf(
        "-s", serial, "shell", "am", "start", "-n", AutomationContract.COMPONENT,
        // NEW_TASK (always set by am) plus MULTIPLE_TASK: every request starts a new instance. Without it Android may
        // only bring an earlier runner screen's task to the front, because it compares intents without their extras.
        "--activity-multiple-task",
        "--es", AutomationContract.EXTRA_TOKEN, DeviceShell.quote(r.token),
        "--es", AutomationContract.EXTRA_PACKAGE, DeviceShell.quote(r.packageName),
        "--es", AutomationContract.EXTRA_MODE, DeviceShell.quote(r.mode),
        "--ez", AutomationContract.EXTRA_AUTO_TAP, r.autoTap.toString(),
        "--ez", AutomationContract.EXTRA_CHILD_FACING, r.childFacing.toString(),
        "--es", AutomationContract.EXTRA_RUN_ID, DeviceShell.quote(r.runId),
    )

    fun readStatus(serial: String, runId: String) = listOf("-s", serial, "shell", "cat", DeviceShell.quote(AutomationContract.deviceStatusPath(runId)))

    fun pullBundle(serial: String, runId: String, localPath: String) = listOf("-s", serial, "pull", AutomationContract.deviceBundlePath(runId), localPath)

    /** bundle.json.sig.json: the phone's hardware signature over bundle.json, written only when the phone can sign. */
    fun pullSignature(serial: String, runId: String, localPath: String) =
        listOf("-s", serial, "pull", EvidenceSignatures.fileNameFor(AutomationContract.deviceBundlePath(runId)), localPath)

    /** The command as text for logs, with the pairing token replaced. */
    fun display(adb: String, args: List<String>): String {
        val shown = args.toMutableList()
        for (i in 1 until shown.size - 1) {
            if (shown[i] == AutomationContract.EXTRA_TOKEN && shown[i - 1] == "--es") shown[i + 1] = HIDDEN
        }
        return (listOf(adb) + shown).joinToString(" ")
    }
}

object DeviceShell {
    /** One literal word for the phone's sh: wrapped in single quotes, an embedded quote written as '\''. */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

data class AdbDevice(val serial: String, val state: String)

/** Reads adb's answers. */
object AdbOutput {
    /** `adb devices`: "serial<TAB>state" lines; banners such as "* daemon started successfully" are skipped. */
    fun devices(text: String): List<AdbDevice> = text.lineSequence()
        .map { it.trim() }
        .filter { '\t' in it }
        .map { line -> AdbDevice(line.substringBefore('\t').trim(), line.substringAfter('\t').trim()) }
        .filter { it.serial.isNotEmpty() }
        .toList()

    /** `pm path`: the APK paths of an installed package; empty when it is not installed or the call failed. */
    fun installedPaths(r: ExecResult): List<String> =
        if (!r.ok) emptyList() else r.out.lineSequence().map { it.trim() }.filter { it.startsWith("package:") }.map { it.removePrefix("package:") }.toList()

    fun isMissingFile(r: ExecResult): Boolean = !r.ok && r.text.contains("No such file or directory")

    fun isPermissionDenied(r: ExecResult): Boolean = !r.ok && r.text.contains("Permission denied")

    private val notFound = Regex("device '[^']*' not found")

    /** A problem reaching the phone (not an answer from it), in adb's words; null when there is none. */
    fun transportProblem(r: ExecResult): String? {
        val t = r.text
        notFound.find(t)?.let { return it.value }
        return listOf("no devices/emulators found", "device offline", "device unauthorized", "device still authorizing", "more than one device/emulator")
            .firstOrNull { it in t }
    }

    fun firstLine(r: ExecResult): String = r.text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "exit code ${r.exitCode ?: "none (timed out)"}"
}

object RunIds {
    private const val CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

    /** "run-20261010-120000-ab12": UTC time plus four random characters; a valid run id (at most 40 characters). */
    fun generate(nowMs: Long, random: Random = Random()): String =
        "run-" + STAMP.format(Instant.ofEpochMilli(nowMs)) + "-" + (1..4).map { CHARS[random.nextInt(CHARS.length)] }.joinToString("")
}
