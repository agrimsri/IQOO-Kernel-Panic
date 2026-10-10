package com.dpdpxray.cli

import com.dpdpxray.cli.samples.SampleBundles
import com.dpdpxray.core.export.AuditBundleV1
import com.dpdpxray.core.export.AuditBundles
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

class CliRun(val exit: Int, val out: String, val err: String) {
    override fun toString() = "exit=$exit\n--- stdout\n$out\n--- stderr\n$err"
}

/** Runs the CLI in-process with [cwd] as its working directory, sharing one rules engine across tests. */
object CliHarness {
    private val gate = lazy { Gate() }

    fun run(cwd: File, vararg args: String): CliRun {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = TraceVeilCi(PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8"), cwd, gate).run(arrayOf(*args))
        return CliRun(code, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    /**
     * Runs `traceveil-ci run` in-process against [adb] (a fake phone), with a test clock, a fixed run id and [env] as
     * the environment. [adbPaths] records which adb executable the command chose.
     */
    fun runDevice(
        cwd: File,
        adb: Adb,
        vararg args: String,
        clock: RunClock = FakeClock(),
        env: Map<String, String> = mapOf("TRACEVEIL_TOKEN" to FakePhone.TOKEN),
        adbPaths: MutableList<String> = mutableListOf(),
    ): CliRun {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = TraceVeilCi(
            PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8"), cwd, gate,
            env = env, adbFactory = { path -> adbPaths += path; adb }, clock = clock, newRunId = { FakePhone.RUN_ID },
        ).run(arrayOf(*args))
        return CliRun(code, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    /** Writes the synthetic sample bundles into [dir] (same text as docs/samples). */
    fun samples(dir: File): File = dir.also { SampleBundles.write(it) }

    fun write(dir: File, name: String, bundle: AuditBundleV1): File =
        File(dir, name).apply { writeText(AuditBundles.encode(bundle), Charsets.UTF_8) }

    fun writeText(dir: File, name: String, text: String): File = File(dir, name).apply { writeText(text, Charsets.UTF_8) }
}
