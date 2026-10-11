package com.dpdpxray.cli

import com.dpdpxray.core.automation.AutomationContract
import com.dpdpxray.core.automation.AutomationMode
import com.dpdpxray.core.automation.AutomationStatus
import com.dpdpxray.core.automation.AutomationStatuses
import com.dpdpxray.core.evidence.GoogleAttestationRoots
import com.dpdpxray.core.evidence.TrustedRoot
import com.dpdpxray.core.model.AuditPlan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.io.PrintStream

/** `traceveil-ci run` options after validation. Nothing here has touched a phone yet. */
data class RunOptions(
    val packageName: String,
    val device: String?,
    val apk: File?,
    val mode: AutomationMode,
    val token: String,
    val policy: Policy,
    val policySource: String,
    val timeoutSeconds: Long,
    val outDir: File,
    val adb: String,
    val format: String,
    val sarif: File?,
    val childFacing: Boolean,
) {
    companion object {
        val VALUE_OPTIONS = listOf("--device", "--package", "--apk", "--mode", "--token", "--policy", "--timeout", "--out", "--adb", "--format", "--sarif")
        val FLAGS = listOf("--child-facing", TraceVeilCi.REQUIRE_SIGNATURE)
        const val DEFAULT_TIMEOUT_S = 300L
        const val MAX_TIMEOUT_S = 86_400L
        const val DEFAULT_OUT = "traceveil-out"
        private val SERIAL = Regex("[A-Za-z0-9._:-]{1,128}")
        private const val TOKEN_SOURCE = "TraceVeil > Settings > Developer automation on the phone"

        /** Throws [UsageException] for anything wrong on the command line; the token is never echoed. */
        fun parse(args: List<String>, env: Map<String, String>, cwd: File): RunOptions {
            val values = mutableMapOf<String, String>()
            val flags = mutableSetOf<String>()
            var i = 0
            while (i < args.size) {
                val arg = args[i]
                when {
                    arg.startsWith("--") -> {
                        val name = arg.substringBefore('=')
                        when (name) {
                            in FLAGS -> {
                                if ('=' in arg) throw UsageException("$name takes no value")
                                flags += name
                            }
                            in VALUE_OPTIONS -> {
                                if (name in values) throw UsageException("$name was given twice")
                                val value = if ('=' in arg) arg.substringAfter('=') else args.getOrNull(++i) ?: throw UsageException("$name needs a value")
                                if (value.isBlank()) throw UsageException("$name needs a value")
                                values[name] = value
                            }
                            else -> throw UsageException("unknown option \"$name\" (known: ${(VALUE_OPTIONS + FLAGS).sorted().joinToString()})")
                        }
                    }
                    arg.startsWith("-") && arg.length > 1 -> throw UsageException("unknown option \"$arg\"")
                    else -> throw UsageException("run takes no file arguments (got \"$arg\")")
                }
                i++
            }
            fun resolve(path: String) = File(path).let { if (it.isAbsolute) it else File(cwd, path) }

            val pkg = values["--package"] ?: throw UsageException("--package is required (the app under test, e.g. --package com.example.app)")
            if (!AutomationContract.isValidPackage(pkg)) throw UsageException("\"$pkg\" is not a valid Android package name")
            if (pkg == AutomationContract.APP_PACKAGE) throw UsageException("TraceVeil cannot check itself; pass the package of the app under test")

            val device = values["--device"] ?: env["ANDROID_SERIAL"]?.takeIf { it.isNotBlank() }
            if (device != null && !SERIAL.matches(device)) throw UsageException("--device must be an adb serial (letters, digits, '.', '_', ':' or '-')")

            val mode = when (values["--mode"]?.lowercase() ?: "full") {
                "full" -> AutomationMode.FULL
                "quick" -> AutomationMode.QUICK
                else -> throw UsageException("--mode must be full or quick (Fast Preview is a short first look and cannot gate a release)")
            }
            val timeout = (values["--timeout"] ?: DEFAULT_TIMEOUT_S.toString()).toLongOrNull()?.takeIf { it in 1..MAX_TIMEOUT_S }
                ?: throw UsageException("--timeout must be a whole number of seconds between 1 and $MAX_TIMEOUT_S")
            val format = values["--format"]?.lowercase() ?: "text"
            if (format !in setOf("text", "json")) throw UsageException("unknown format \"$format\" (use text|json; SARIF goes to --sarif FILE)")

            val token = values["--token"] ?: env["TRACEVEIL_TOKEN"]?.takeIf { it.isNotBlank() }
                ?: throw UsageException("no pairing token: pass --token or set TRACEVEIL_TOKEN (copy it from $TOKEN_SOURCE)")
            if (!AutomationContract.isValidToken(token.trim())) throw UsageException("the token is not a TraceVeil pairing token (copy it from $TOKEN_SOURCE)")

            val (filePolicy, fileSource) = values["--policy"]?.let { path ->
                val f = resolve(path)
                if (!f.isFile) throw UsageException("policy file not found: $path")
                Policy.parse(f.readText(Charsets.UTF_8)) to "policy file ${LoadedBundle.displayPath(f, cwd)}"
            } ?: (Policy() to DEFAULT_POLICY_SOURCE)
            val (policy, policySource) = TraceVeilCi.withRequireSignature(filePolicy, fileSource, TraceVeilCi.REQUIRE_SIGNATURE in flags)

            val apk = values["--apk"]?.let { path -> resolve(path).also { if (!it.isFile) throw UsageException("APK not found: $path") } }
            return RunOptions(
                packageName = pkg, device = device, apk = apk, mode = mode, token = token.trim(), policy = policy, policySource = policySource,
                timeoutSeconds = timeout, outDir = resolve(values["--out"] ?: DEFAULT_OUT), adb = values["--adb"] ?: env["ADB"]?.takeIf { it.isNotBlank() } ?: "adb",
                format = format, sarif = values["--sarif"]?.let(::resolve), childFacing = "--child-facing" in flags,
            )
        }

        const val DEFAULT_POLICY_SOURCE = "defaults (failOn C2, C9; not-established fails; requireFreshState; requirePlans ACCEPT, REJECT, SILENT; inconclusive fails)"
    }
}

/**
 * `traceveil-ci run`: starts a TraceVeil audit on a USB-connected test phone, waits for it, copies the evidence and
 * applies the same check and policy as `traceveil-ci check`. Exit 0 pass, 1 policy violation, 2 usage, device,
 * phone-side refusal or timeout.
 */
class RunCommand(
    private val out: PrintStream,
    private val err: PrintStream,
    private val cwd: File,
    private val gate: () -> Gate,
    private val env: Map<String, String>,
    private val adbFactory: (String) -> Adb,
    private val clock: RunClock,
    private val newRunId: () -> String,
    /** Roots the hardware signature must chain to: Google's attestation roots (tests pass a test root). */
    private val trustedRoots: () -> List<TrustedRoot> = { GoogleAttestationRoots.all },
) {
    private val pretty = Json { prettyPrint = true }

    fun run(args: List<String>): Int {
        val o = RunOptions.parse(args, env, cwd)
        val json = o.format == "json"
        // With --format json, stdout carries only the final JSON document; the progress log goes to stderr.
        val log: (String) -> Unit = if (json) err::println else out::println
        val runId = newRunId()
        check(AutomationContract.isValidRunId(runId)) { "invalid run id" }
        val plan = RunPlan(o.packageName, o.device, o.apk, o.mode, o.token, o.childFacing, o.timeoutSeconds * 1000, runId, File(o.outDir, runId))

        log("TraceVeil Runner · ${CliInfo.NAME} ${CliInfo.version}")
        log("Policy:   ${o.policySource}")
        log("Mode:     ${modeText(o.mode)}")
        if (o.childFacing) log("Audience: declared child-facing (adds the C9 check)")
        if (o.mode == AutomationMode.QUICK && o.policy.requirePlans.any { it != AuditPlan.ACCEPT }) {
            log("Note: quick mode captures only the Accept plan; this policy requires ${o.policy.requirePlans.joinToString { it.name }}, so a quick run cannot pass it. Use --mode full, or a policy with \"requirePlans\": [\"ACCEPT\"].")
        }
        log("Run id:   $runId")

        val runner = DeviceRunner(adbFactory(o.adb), o.adb, clock, log)
        val result = try {
            runner.run(plan)
        } catch (e: RunFailure) {
            return failure(o, runner, runId, e.message ?: "the run failed", e.fix, e.status)
        } catch (e: AdbUnavailableException) {
            return failure(o, runner, runId, e.message ?: "could not run adb", DeviceRunner.FIX_ADB, null)
        } catch (e: IOException) {
            return failure(o, runner, runId, "Could not save the run's files: ${e.message}", "Make --out a folder this user can write to.", null)
        }
        log("Saved:    ${listOfNotNull(result.bundleFile, result.statusFile, result.signatureFile).joinToString { shown(it) }}")

        val loaded = try {
            LoadedBundle.read(result.bundleFile, cwd)
        } catch (e: UsageException) {
            return failure(o, runner, runId, "bundle.json from the phone could not be read: ${e.message}", "Use TraceVeil and traceveil-ci builds from the same release.", result.status)
        }
        val bundlePackage = loaded.bundle.app.packageName
        if (bundlePackage != o.packageName) {
            return failure(o, runner, runId, "bundle.json is for $bundlePackage, not ${o.packageName}.", null, result.status)
        }
        val bundleAudit = loaded.meta.id
        val statusAudit = result.status.auditId
        if (bundleAudit != null && statusAudit != null && bundleAudit != statusAudit) {
            return failure(o, runner, runId, "bundle.json is audit $bundleAudit, but the phone reported $statusAudit.", null, result.status)
        }

        // The hardware signature is verified before gating; with requireSignature anything but verified fails.
        val signature = SignatureStatus.of(result.bundleFile, null, cwd, trustedRoots())
        val check = gate().check(loaded, o.policy, signature = signature)
        val code = if (check.passed) Exit.PASS else Exit.VIOLATION
        o.sarif?.let { f ->
            runCatching {
                f.absoluteFile.parentFile?.mkdirs()
                f.writeText(pretty.encodeToString(JsonObject.serializer(), CheckSarif.render(listOf(check))) + "\n", Charsets.UTF_8)
            }.getOrElse { return failure(o, runner, runId, "could not write ${f.path}: ${it.message}", null, result.status) }
        }
        if (json) {
            out.println(
                pretty.encodeToString(
                    JsonObject.serializer(),
                    report(
                        o, runner, runId, code, null, null, result.status, shown(result.bundleFile),
                        CheckJson.render(listOf(check), o.policy, o.policySource), result.signatureFile?.let(::shown),
                    ),
                ),
            )
        } else {
            out.println()
            out.print(CheckText.render(listOf(check), o.policy, o.policySource))
            o.sarif?.let { out.println("Wrote SARIF to ${shown(it)}") }
            out.println("TraceVeil Runner: " + if (check.passed) "PASS (exit 0)" else "FAIL (policy violation; exit 1)")
        }
        return code
    }

    private fun failure(o: RunOptions, runner: DeviceRunner, runId: String, message: String, fix: String?, status: AutomationStatus?): Int {
        if (o.format == "json") {
            out.println(pretty.encodeToString(JsonObject.serializer(), report(o, runner, runId, Exit.USAGE, message, fix, status, null, null)))
        } else {
            out.println("TraceVeil Runner: ERROR (exit 2)")
        }
        err.println("traceveil-ci run: $message")
        fix?.let { err.println("Fix: $it") }
        return Exit.USAGE
    }

    /** Machine-readable result: schema "traceveil.ci.run.v1". */
    private fun report(
        o: RunOptions, runner: DeviceRunner, runId: String, code: Int, error: String?, fix: String?,
        status: AutomationStatus?, bundle: String?, check: JsonObject?, signatureFile: String? = null,
    ): JsonObject = buildJsonObject {
        put("schema", SCHEMA)
        put("tool", buildJsonObject { put("name", CliInfo.NAME); put("version", CliInfo.version) })
        put("exitCode", code)
        put("passed", code == Exit.PASS)
        put("error", error)
        put("fix", fix)
        put("device", runner.serial)
        put("package", o.packageName)
        put("mode", o.mode.wire)
        put("runId", runId)
        put("status", status?.let { Json.parseToJsonElement(AutomationStatuses.encode(it)) } ?: JsonNull as JsonElement)
        put("bundle", bundle)
        put("signatureFile", signatureFile)
        put("check", check ?: JsonNull)
    }

    private fun shown(f: File) = LoadedBundle.displayPath(f, cwd)

    private fun modeText(mode: AutomationMode) = when (mode) {
        AutomationMode.FULL -> "full · Silent, Reject and Accept plans; the phone clears the app's data before each plan (allowlisted test apps only); the agent taps Accept and Reject"
        AutomationMode.QUICK -> "quick · one Accept plan; data is cleared only for an allowlisted test app with Shizuku ready; the agent taps Accept"
        AutomationMode.PREVIEW -> "preview"
    }

    companion object {
        const val SCHEMA = "traceveil.ci.run.v1"
    }
}
