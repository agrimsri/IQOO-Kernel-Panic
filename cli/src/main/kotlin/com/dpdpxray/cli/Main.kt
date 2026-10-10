package com.dpdpxray.cli

import com.dpdpxray.core.datasafety.DataSafetyLabel
import com.dpdpxray.core.datasafety.DataSafetyParser
import com.dpdpxray.core.datasafety.PlayLabelCheck
import com.dpdpxray.core.datasafety.PlayLabelComparator
import com.dpdpxray.core.datasafety.PlayLabelReport
import com.dpdpxray.core.evidence.EvidenceSignatures
import com.dpdpxray.core.evidence.EvidenceVerifier
import com.dpdpxray.core.evidence.GoogleAttestationRoots
import com.dpdpxray.core.evidence.TrustedRoot
import com.dpdpxray.core.automation.AutomationContract
import com.dpdpxray.core.law.LegalProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

object CliInfo {
    const val NAME = "TraceVeil CI"
    val version: String = CliInfo::class.java.`package`?.implementationVersion ?: "dev"
}

object Exit {
    const val PASS = 0
    const val VIOLATION = 1
    const val USAGE = 2
}

fun main(args: Array<String>) {
    val code = try {
        // UTF-8 everywhere: CI logs and report files are UTF-8 even when the platform default is not.
        val out = PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8")
        val err = PrintStream(FileOutputStream(FileDescriptor.err), true, "UTF-8")
        TraceVeilCi(out, err).run(args)
    } catch (t: Throwable) {
        // Anything that escapes (even a JVM Error) is an internal error, exit 2: an uncaught throwable would exit 1,
        // the code for a policy violation.
        runCatching { System.err.println("traceveil-ci: internal error: ${t.message ?: t.javaClass.name}") }
        Exit.USAGE
    }
    exitProcess(code)
}

/**
 * `traceveil-ci check <bundle.json>... [--policy policy.json] [--format text|json|sarif] [--out file] [--play-label label.txt]`
 * `traceveil-ci diff <old.json> <new.json> [--format text|json] [--out file]`
 *
 * `traceveil-ci run --package <pkg> [options]` (see [RunCommand]): runs the audit on a USB-connected phone first.
 * `traceveil-ci verify <bundle.json> [--sig file] [--format text|json] [--package pkg] [--signer-sha256 hex]`: checks the
 * phone's hardware-backed signature (traceveil.signature.v1) offline; exit 0 verified, 1 not verified, 2 usage.
 *
 * Exit codes: 0 pass (diff: no regression), 1 policy violation (diff: regression), 2 usage or unreadable input
 * (run: also device, phone-side refusal or timeout).
 * With --out, the chosen format is written to the file and the text report still goes to stdout for the CI log.
 */
class TraceVeilCi(
    private val out: PrintStream,
    private val err: PrintStream,
    private val cwd: File = File("").absoluteFile,
    gateFactory: Lazy<Gate> = lazy { Gate() },
    private val env: Map<String, String> = System.getenv(),
    private val adbFactory: (String) -> Adb = ::ProcessAdb,
    private val clock: RunClock = SystemRunClock,
    private val newRunId: () -> String = { RunIds.generate(clock.nowMs()) },
    /** Roots `verify` trusts: Google's hardware attestation roots. Tests pass a test root here; there is no CLI option. */
    private val trustedRoots: () -> List<TrustedRoot> = { GoogleAttestationRoots.all },
) {
    private val gate: Gate by gateFactory
    private val pretty = Json { prettyPrint = true }

    fun run(args: Array<String>): Int = try {
        when (args.firstOrNull()) {
            null -> throw UsageException("no command given")
            "-h", "--help", "help" -> { out.print(USAGE); Exit.PASS }
            "-V", "--version", "version" -> { out.println("${CliInfo.NAME} ${CliInfo.version}"); Exit.PASS }
            "check" -> check(Args.parse(args.drop(1), setOf("--policy", "--format", "--out", "--profile", "--play-label")))
            "diff" -> diff(Args.parse(args.drop(1), setOf("--format", "--out")))
            "verify" -> verify(Args.parse(args.drop(1), setOf("--sig", "--format", "--package", "--signer-sha256")))
            "run" -> RunCommand(out, err, cwd, { gate }, env, adbFactory, clock, newRunId).run(args.drop(1))
            else -> throw UsageException("unknown command \"${args[0]}\"")
        }
    } catch (e: UsageException) {
        err.println("traceveil-ci: ${e.message}")
        err.println("Run \"traceveil-ci --help\" for usage.")
        Exit.USAGE
    } catch (e: Throwable) {
        // Never let a crash (an exception or a JVM Error) look like a policy result: the JVM would exit 1, the code for
        // a violation.
        runCatching { err.println("traceveil-ci: internal error: ${e.message ?: e.javaClass.name}") }
        Exit.USAGE
    }

    private fun check(a: Args): Int {
        if (a.positional.isEmpty()) throw UsageException("check needs at least one bundle file (traceveil.audit.v1 JSON)")
        val format = a.format(setOf("text", "json", "sarif"))
        val (policy, policySource) = a.options["--policy"]?.let { path ->
            val f = resolve(path)
            if (!f.isFile) throw UsageException("policy file not found: $path")
            Policy.parse(f.readText(Charsets.UTF_8)) to "policy file ${LoadedBundle.displayPath(f, cwd)}"
        } ?: (Policy() to "defaults (failOn C2, C9; not-established fails; requireFreshState; requirePlans ACCEPT, REJECT, SILENT; inconclusive fails)")
        val profile = a.options["--profile"]?.let { id ->
            LegalProfile.parse(id) ?: throw UsageException("unknown legal profile \"$id\" (use ${LegalProfile.entries.joinToString("|") { it.name }})")
        }
        val label = a.options["--play-label"]?.let { readPlayLabel(it) }
        val bundles = load(a.positional)
        val checks = bundles.map { gate.check(it, policy, profile) }
        // "Play label vs what this check saw": its own section, separate from the policy result and the exit code.
        val labelChecks = label?.let { (_, parsed) -> bundles.map { it to PlayLabelComparator(catalog).compare(parsed, it.bundle.sessions) } }
        val text = CheckText.render(checks, policy, policySource) + (label?.let { (file, _) -> playLabelText(file, labelChecks!!) } ?: "")
        emit(a, format, text) {
            when (format) {
                "json" -> pretty.encodeToString(
                    JsonObject.serializer(),
                    CheckJson.render(checks, policy, policySource).let { root ->
                        if (label == null) root else JsonObject(root + ("playLabel" to playLabelJson(label.first, labelChecks!!)))
                    },
                )
                "sarif" -> pretty.encodeToString(JsonObject.serializer(), CheckSarif.render(checks))
                else -> text
            }
        }
        return if (checks.all { it.passed }) Exit.PASS else Exit.VIOLATION
    }

    private fun diff(a: Args): Int {
        if (a.positional.size != 2) throw UsageException("diff needs exactly two bundle files: <old.json> <new.json>")
        val format = a.format(setOf("text", "json"))
        val (old, new) = load(a.positional)
        val d = BundleDiff(old, new, gate.reevaluate(old.bundle), gate.reevaluate(new.bundle))
        val text = DiffText.render(d)
        emit(a, format, text) { if (format == "json") pretty.encodeToString(JsonObject.serializer(), DiffJson.render(d)) else text }
        return if (d.regression) Exit.VIOLATION else Exit.PASS
    }

    private fun verify(a: Args): Int {
        if (a.positional.size != 1) throw UsageException("verify needs exactly one file: <bundle.json> (the exported file the signature covers)")
        val format = a.format(setOf("text", "json"))
        val file = resolve(a.positional.single())
        val shown = LoadedBundle.displayPath(file, cwd)
        if (!file.isFile) throw UsageException("$shown: no such file")
        val explicit = a.options["--sig"]?.let { path ->
            resolve(path).also { if (!it.isFile) throw UsageException("signature file not found: $path") }
        }
        val candidates = explicit?.let { listOf(it) } ?: EvidenceSignatures.candidatesFor(file.name).map { File(file.absoluteFile.parentFile, it) }
        val sigFile = candidates.firstOrNull { it.isFile }
        val bytes = runCatching { file.readBytes() }.getOrElse { throw UsageException("$shown: could not read the file (${it.message})") }
        val report = sigFile?.let { s ->
            val text = runCatching { s.readText(Charsets.UTF_8).removePrefix("﻿") }
                .getOrElse { throw UsageException("${LoadedBundle.displayPath(s, cwd)}: could not read the file (${it.message})") }
            EvidenceVerifier.verify(
                bytes, text, roots = trustedRoots(),
                expectedPackage = a.options["--package"] ?: AutomationContract.APP_PACKAGE,
                expectedSignerSha256 = a.options["--signer-sha256"],
            )
        }
        val outcome = VerifyOutcome(shown, sigFile?.let { LoadedBundle.displayPath(it, cwd) }, candidates.map { it.name }, report)
        if (format == "json") out.println(pretty.encodeToString(JsonObject.serializer(), VerifyJson.render(outcome)))
        else out.print(VerifyText.render(outcome))
        return if (outcome.verified) Exit.PASS else Exit.VIOLATION
    }

    private val catalog by lazy { com.dpdpxray.core.trackers.TrackerCatalog.loadDefault() }

    /** The label text file named by --play-label, read and parsed; its display path first. */
    private fun readPlayLabel(path: String): Pair<String, DataSafetyLabel> {
        val f = resolve(path)
        if (!f.isFile) throw UsageException("Play label file not found: $path")
        val text = runCatching { f.readText(Charsets.UTF_8).removePrefix("﻿") }
            .getOrElse { throw UsageException("$path: could not read the file (${it.message})") }
        return LoadedBundle.displayPath(f, cwd) to DataSafetyParser.parse(text)
    }

    private fun playLabelText(file: String, checks: List<Pair<LoadedBundle, PlayLabelCheck>>): String = buildString {
        checks.forEach { (b, c) ->
            appendLine()
            if (checks.size > 1) appendLine("Bundle: ${b.displayPath}")
            append(PlayLabelReport.text(c, file))
        }
    }

    private fun playLabelJson(file: String, checks: List<Pair<LoadedBundle, PlayLabelCheck>>): JsonObject = JsonObject(
        mapOf(
            "file" to JsonPrimitive(file),
            "basis" to JsonPrimitive(PlayLabelComparator.ABOUT),
            "bundles" to JsonArray(
                checks.map { (b, c) ->
                    JsonObject(mapOf("path" to JsonPrimitive(b.displayPath), "check" to pretty.encodeToJsonElement(PlayLabelCheck.serializer(), c)))
                },
            ),
        ),
    )

    /** Reads every file first so all unreadable inputs are reported together. */
    private fun load(paths: List<String>): List<LoadedBundle> {
        val results = paths.map { p -> runCatching { LoadedBundle.read(resolve(p), cwd) } }
        val problems = results.mapNotNull { it.exceptionOrNull() }
        if (problems.isNotEmpty()) {
            problems.dropLast(1).forEach { err.println("traceveil-ci: ${it.message}") }
            throw problems.last() as? UsageException ?: UsageException(problems.last().message ?: "could not read a bundle")
        }
        return results.map { it.getOrThrow() }
    }

    private fun emit(a: Args, format: String, text: String, rendered: () -> String) {
        val target = a.options["--out"]
        if (target == null) {
            out.print(rendered())
            if (format != "text") out.println()
            return
        }
        val f = resolve(target)
        runCatching {
            f.absoluteFile.parentFile?.mkdirs()
            f.writeText(rendered().let { if (it.endsWith("\n")) it else it + "\n" }, Charsets.UTF_8)
        }.getOrElse { throw UsageException("could not write $target: ${it.message}") }
        out.print(text)
        out.println("Wrote the $format report to ${LoadedBundle.displayPath(f, cwd)}")
    }

    private fun resolve(path: String): File = File(path).let { if (it.isAbsolute) it else File(cwd, path) }

    private class Args(val positional: List<String>, val options: Map<String, String>) {
        fun format(allowed: Set<String>): String {
            val f = options["--format"]?.lowercase() ?: "text"
            if (f !in allowed) throw UsageException("unknown format \"$f\" (use ${allowed.joinToString("|")})")
            return f
        }

        companion object {
            fun parse(args: List<String>, known: Set<String>): Args {
                val positional = mutableListOf<String>()
                val options = mutableMapOf<String, String>()
                var i = 0
                var onlyFiles = false
                while (i < args.size) {
                    val arg = args[i]
                    when {
                        onlyFiles -> positional += arg
                        arg == "--" -> onlyFiles = true
                        arg.startsWith("--") -> {
                            val name = arg.substringBefore('=')
                            if (name !in known) throw UsageException("unknown option \"$name\" (known: ${known.sorted().joinToString()})")
                            val value = if ('=' in arg) arg.substringAfter('=') else args.getOrNull(++i) ?: throw UsageException("$name needs a value")
                            if (value.isBlank()) throw UsageException("$name needs a value")
                            options[name] = value
                        }
                        arg.startsWith("-") && arg.length > 1 -> throw UsageException("unknown option \"$arg\"")
                        else -> positional += arg
                    }
                    i++
                }
                return Args(positional, options)
            }
        }
    }

    companion object {
        val USAGE = """
            |TraceVeil CI: gate a release on consent evidence captured on a phone with TraceVeil.
            |
            |Usage:
            |  traceveil-ci check <bundle.json>... [--policy policy.json] [--profile id] [--format text|json|sarif] [--out file]
            |                     [--play-label label.txt]
            |  traceveil-ci diff <old.json> <new.json> [--format text|json] [--out file]
            |  traceveil-ci run --package <pkg> [--device SERIAL] [--apk file.apk] [--mode full|quick] [--token T]
            |                   [--policy policy.json] [--timeout 300] [--out traceveil-out] [--adb path]
            |                   [--format text|json] [--sarif file] [--child-facing]
            |  traceveil-ci verify <bundle.json> [--sig file] [--format text|json] [--package pkg] [--signer-sha256 hex]
            |  traceveil-ci --help | --version
            |
            |check  Re-evaluates each traceveil.audit.v1 bundle (from TraceVeil's Report screen: "Export audit data
            |       (JSON)") with this build's rules, shows stored and re-evaluated scores, and applies the policy.
            |diff   Compares two bundles (e.g. last release and this one): resolved, new and unchanged findings and
            |       the score change. A new finding at Low severity or above, a lower score, an inconclusive new
            |       result, or an old finding the new capture could not test (NOT ESTABLISHED) is a regression.
            |
            |run    TraceVeil Runner: starts the audit on a USB-connected test phone over adb (Developer automation
            |       must be on in TraceVeil's Settings), waits for it, copies bundle.json to --out/<run id>/ and gates
            |       it like check. The pairing token comes from --token or TRACEVEIL_TOKEN; the phone comes from
            |       --device, ANDROID_SERIAL or the only connected phone; adb from --adb, ADB or PATH. --apk installs
            |       the build first with adb install -r. Exit 2 also covers device problems, a refusal on the phone
            |       and --timeout. Setup and security model: docs/ci/RUNNER.md.
            |
            |verify Checks, offline, the signature TraceVeil writes next to an export (<file>.sig.json, made by a key in
            |       the phone's Android Keystore): the file is unchanged, the key was generated in secure hardware
            |       (StrongBox or TEE), which app created it (default expected: com.dpdpxray.app; --package to change,
            |       --signer-sha256 to also require a signing-certificate digest), and the chain ends at a Google hardware
            |       attestation root. Exit 0 verified, 1 not verified (also: no signature file), 2 usage. It cannot check
            |       revocation offline. Format and threat model: docs/SIGNED_EVIDENCE.md.
            |
            |Policy keys (all optional; defaults in brackets):
            |  failOn            ["C2", "C9"]   fail when the re-evaluated result has any of these checks
            |  allowNotEstablished [false]      a failOn check the plans could not test fails unless true
            |  minScore          [none]         fail when the score is below this (0-100)
            |  maxHigh           [none]         fail when more findings than this are High or Critical
            |  requireFreshState [true]         every plan must have started from an approved fresh reset
            |  allowInconclusive [false]        an inconclusive result (low network visibility) fails unless true
            |  requirePlans      ["ACCEPT", "REJECT", "SILENT"]  plans that must be captured with their choice recorded
            |
            |--profile reads the findings under a legal lens: INDIA, EU, UK, US_CALIFORNIA, US_CHILDREN, BRAZIL,
            |       SINGAPORE or GLOBAL_BASELINE (case and - or _ do not matter). Default: the bundle's legalProfile, else
            |       GLOBAL_BASELINE. The lens shows which findings each law requires, treats as context or does not
            |       cover, and a score without the not-applicable ones. It never changes the policy result.
            |--out writes the chosen format to a file; the text report still goes to stdout.
            |--play-label label.txt sets the app's Google Play Data safety text (copied from its listing) against each
            |       bundle's lookups and prints points to review (text and json formats). Separate from the policy: it
            |       never changes the result or the exit code, and nothing is fetched. Method: docs/PLAY_LABEL_CHECK.md.
            |Exit codes: 0 pass (diff: no regression), 1 policy violation (diff: regression), 2 usage or unreadable input
            |(run: also no device, a refusal or failure on the phone, or a timeout).
            |A DNS lookup is an attempt to contact a host, not proof that personal data was sent.
            |
        """.trimMargin()
    }
}
