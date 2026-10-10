package com.dpdpxray.cli

import com.dpdpxray.cli.samples.SampleBundles
import com.dpdpxray.core.export.AuditBundleV1
import com.dpdpxray.core.export.AuditBundles
import com.dpdpxray.core.evidence.EvidenceSignatures
import com.dpdpxray.core.evidence.GoogleAttestationRoots
import com.dpdpxray.core.evidence.SecurityLevel
import com.dpdpxray.core.evidence.TestPki
import com.dpdpxray.core.evidence.TrustedRoot
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

    /** Like [run], but `verify`, `check` and `run` trust [roots] (a test root) instead of Google's attestation roots. */
    fun runTrusting(cwd: File, roots: List<TrustedRoot>, vararg args: String): CliRun {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = TraceVeilCi(PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8"), cwd, gate, trustedRoots = { roots }).run(arrayOf(*args))
        return CliRun(code, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    /**
     * A traceveil.signature.v1 file over [bytes], made with [chain] (a self-made test chain, never a Google one), as the
     * phone writes it next to an export.
     */
    fun signature(bytes: ByteArray, chain: TestPki.Chain, signedFile: String, level: SecurityLevel = SecurityLevel.STRONGBOX): String =
        EvidenceSignatures.encode(EvidenceSignatures.create(bytes, chain.sign(bytes), chain.derChain, "2026-10-11T08:00:05Z", "1.1.0", level, signedFile))

    /** Signs [file]'s exact bytes and writes the signature next to it as <name>.sig.json; returns the signature file. */
    fun signNextTo(file: File, chain: TestPki.Chain): File =
        File(file.parentFile, EvidenceSignatures.fileNameFor(file.name)).apply { writeText(signature(file.readBytes(), chain, file.name), Charsets.UTF_8) }

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
        roots: List<TrustedRoot>? = null,
    ): CliRun {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val ci = TraceVeilCi(
            PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8"), cwd, gate,
            env = env, adbFactory = { path -> adbPaths += path; adb }, clock = clock, newRunId = { FakePhone.RUN_ID },
            trustedRoots = { roots ?: GoogleAttestationRoots.all },
        )
        val code = ci.run(arrayOf(*args))
        return CliRun(code, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    /** Writes the synthetic sample bundles into [dir] (same text as docs/samples). */
    fun samples(dir: File): File = dir.also { SampleBundles.write(it) }

    fun write(dir: File, name: String, bundle: AuditBundleV1): File =
        File(dir, name).apply { writeText(AuditBundles.encode(bundle), Charsets.UTF_8) }

    fun writeText(dir: File, name: String, text: String): File = File(dir, name).apply { writeText(text, Charsets.UTF_8) }
}
