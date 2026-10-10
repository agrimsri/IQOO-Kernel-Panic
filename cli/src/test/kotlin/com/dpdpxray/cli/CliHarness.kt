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

    /** Writes the synthetic sample bundles into [dir] (same text as docs/samples). */
    fun samples(dir: File): File = dir.also { SampleBundles.write(it) }

    fun write(dir: File, name: String, bundle: AuditBundleV1): File =
        File(dir, name).apply { writeText(AuditBundles.encode(bundle), Charsets.UTF_8) }

    fun writeText(dir: File, name: String, text: String): File = File(dir, name).apply { writeText(text, Charsets.UTF_8) }
}
