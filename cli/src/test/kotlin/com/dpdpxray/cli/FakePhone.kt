package com.dpdpxray.cli

import com.dpdpxray.cli.samples.SampleBundles
import com.dpdpxray.core.automation.AutomationContract
import com.dpdpxray.core.automation.AutomationMode
import com.dpdpxray.core.automation.AutomationStatus
import com.dpdpxray.core.automation.AutomationStatuses
import com.dpdpxray.core.automation.BundleSignatures
import com.dpdpxray.core.automation.RefusalCode
import com.dpdpxray.core.automation.RunDecision
import com.dpdpxray.core.automation.RunRequest
import com.dpdpxray.core.automation.RunRequestInput
import com.dpdpxray.core.export.AuditBundleV1
import com.dpdpxray.core.export.AuditBundles
import com.dpdpxray.core.export.BundleMeta
import kotlinx.serialization.json.jsonObject
import java.io.File

/** Test clock: sleeping only moves the time forward. */
class FakeClock(var now: Long = T0) : RunClock {
    val sleeps = mutableListOf<Long>()
    override fun nowMs(): Long = now
    override fun sleep(ms: Long) { sleeps += ms; now += ms }

    companion object { const val T0 = 1_791_590_400_000L }
}

/**
 * A scripted phone behind the [Adb] interface. It answers the runner's adb calls the way adb and Android answer them,
 * and records every argument list. No real device or adb is involved.
 */
class FakePhone(private val clock: FakeClock = FakeClock()) : Adb {
    val calls = mutableListOf<List<String>>()

    var version = ExecResult(0, "Android Debug Bridge version 1.0.41\nVersion 36.0.0-13206524\n")
    var devices = "List of devices attached\n$SERIAL\tdevice\n\n"
    val installed = mutableSetOf(AutomationContract.APP_PACKAGE, LEAKY, FIXED)
    var installResult = ExecResult(0, "Performing Streamed Install\nSuccess\n")
    /** The package an `adb install` adds, as if the APK contained it. */
    var installAdds: String? = null
    var startResult = ExecResult(0, "Starting: Intent { cmp=com.dpdpxray.app/.automation.AutomationActivity (has extras) }\n")
    /** Successive answers to `cat status.json`; the last one repeats. Setting a new script restarts it. */
    var statuses: List<ExecResult> = listOf(NOT_YET)
        set(value) { field = value; polls = 0 }
    var bundleText: String? = null
    var pullResult: ExecResult? = null
    var polls = 0
        private set

    override fun exec(args: List<String>, timeoutMs: Long): ExecResult {
        calls += args
        if (args == listOf("version")) return version
        if (args == listOf("devices")) return ExecResult(0, devices)
        check(args.size >= 3 && args[0] == "-s") { "unexpected adb call $args" }
        val rest = args.drop(2)
        return when {
            rest.take(3) == listOf("shell", "pm", "path") -> {
                val pkg = rest[3].removeSurrounding("'")
                if (pkg in installed) ExecResult(0, "package:/data/app/~~abc==/$pkg-1/base.apk\n") else ExecResult(1, "")
            }
            rest.take(2) == listOf("install", "-r") -> { if (installResult.ok) installAdds?.let { installed += it }; installResult }
            rest.take(3) == listOf("shell", "am", "start") -> startResult
            rest.take(2) == listOf("shell", "cat") -> statuses[minOf(polls++, statuses.lastIndex)]
            rest.first() == "pull" -> pullResult ?: run {
                val text = checkNotNull(bundleText) { "no bundle on the fake phone" }
                File(rest[2]).apply { parentFile?.mkdirs() }.writeText(text, Charsets.UTF_8)
                ExecResult(0, "${rest[1]}: 1 file pulled, 0 skipped.\n")
            }
            else -> error("unexpected adb call $args")
        }
    }

    fun callsStartingWith(vararg prefix: String) = calls.filter { it.drop(2).take(prefix.size) == prefix.toList() }

    companion object {
        const val SERIAL = "10BFBL24YS001GM"
        const val RUN_ID = "run-test-1"
        const val TOKEN = "abcdef-ghijkm-npqrst-uvwxyz"
        const val LEAKY = "com.leakyshop.demo"
        const val FIXED = "com.leakyshop.demo.fixed"
        const val AUDIT_ID = "audit-1791590401000"
        val NOT_YET = ExecResult(1, "", "cat: /sdcard/Android/data/com.dpdpxray.app/files/automation/$RUN_ID/status.json: No such file or directory\n")
        val LOST = ExecResult(1, "", "adb: device '$SERIAL' not found\n")

        fun ok(text: String) = ExecResult(0, text)

        fun request(pkg: String = LEAKY, mode: AutomationMode = AutomationMode.FULL) = RunRequest(RUN_ID, pkg, mode, autoTap = true, childFacing = false)

        fun running(step: String, atSec: Int = 0, pkg: String = LEAKY, mode: AutomationMode = AutomationMode.FULL): ExecResult =
            ok(AutomationStatuses.encode(runningStatus(step, atSec, pkg, mode)))

        fun runningStatus(step: String, atSec: Int = 0, pkg: String = LEAKY, mode: AutomationMode = AutomationMode.FULL): AutomationStatus =
            AutomationStatuses.step(AutomationStatuses.running(request(pkg, mode), resetAppData = mode == AutomationMode.FULL, startedMs = FakeClock.T0, appVersion = "1.1.0"), step, FakeClock.T0 + atSec * 1000L)

        /** A "done" status signed for [bundle] with [TOKEN], as the phone signs the bundle.json it serves. */
        fun done(
            score: Int?, band: String, findings: Int, atSec: Int = 70, pkg: String = LEAKY, mode: AutomationMode = AutomationMode.FULL,
            auditId: String = AUDIT_ID, bundle: String? = leakyBundle(),
        ): ExecResult = ok(AutomationStatuses.encode(AutomationStatuses.done(
            runningStatus("Evaluating", atSec, pkg, mode), auditId, score, band, findings, FakeClock.T0 + atSec * 1000L,
            bundleSignature = bundle?.let { BundleSignatures.sign(TOKEN, it.toByteArray(Charsets.UTF_8)) },
        )))

        fun refused(code: RefusalCode, reason: String, fix: String, pkg: String = LEAKY): ExecResult {
            val input = RunRequestInput(TOKEN, pkg, "full", true, false, RUN_ID)
            return ok(AutomationStatuses.encode(AutomationStatuses.refused(RunDecision.Refused(RUN_ID, code, reason, fix), input, FakeClock.T0, "1.1.0")))
        }

        /** A sample bundle made to look like a fresh device export of [auditId] (not marked as a sample). */
        fun liveLike(bundle: AuditBundleV1, auditId: String = AUDIT_ID): String {
            val meta = AuditBundles.metaOf(bundle).copy(id = auditId, sample = false)
            return AuditBundles.encode(bundle.copy(note = null, meta = AuditBundles.json.encodeToJsonElement(BundleMeta.serializer(), meta).jsonObject))
        }

        fun leakyBundle() = liveLike(SampleBundles.leaky())
        fun fixedBundle() = liveLike(SampleBundles.fixed())
    }
}
