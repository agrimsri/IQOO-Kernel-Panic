package com.dpdpxray.cli

import com.dpdpxray.cli.CliHarness.runDevice
import com.dpdpxray.cli.FakePhone.Companion.AUDIT_ID
import com.dpdpxray.cli.FakePhone.Companion.FIXED
import com.dpdpxray.cli.FakePhone.Companion.LEAKY
import com.dpdpxray.cli.FakePhone.Companion.LOST
import com.dpdpxray.cli.FakePhone.Companion.NOT_YET
import com.dpdpxray.cli.FakePhone.Companion.RUN_ID
import com.dpdpxray.cli.FakePhone.Companion.SERIAL
import com.dpdpxray.cli.FakePhone.Companion.TOKEN
import com.dpdpxray.cli.FakePhone.Companion.done
import com.dpdpxray.cli.FakePhone.Companion.refused
import com.dpdpxray.cli.FakePhone.Companion.running
import com.dpdpxray.core.automation.AutomationMode
import com.dpdpxray.core.automation.RefusalCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** `traceveil-ci run` against a scripted phone: preflight, start, polling, pull, gate and every failure path. */
class RunCommandTest {
    @get:Rule val temp = TemporaryFolder()

    private val cwd: File by lazy { temp.newFolder("work") }
    private val clock = FakeClock()
    private val phone = FakePhone(clock)

    private val policyFile: String by lazy {
        val docs = File(System.getProperty("traceveil.ciDocsDir") ?: "../docs/ci", "traceveil-policy.json")
        docs.absolutePath
    }

    private fun leakyRun() {
        phone.statuses = listOf(
            NOT_YET,
            running("Starting"),
            running("Plan C · No interaction (1 of 3): Resetting authorised test app LeakyShop", 4),
            running("Plan B · Reject (2 of 3): Agent is choosing: reject", 30),
            done(21, "AT_RISK", 5),
        )
        phone.bundleText = FakePhone.leakyBundle()
    }

    private fun run(vararg args: String, env: Map<String, String> = mapOf("TRACEVEIL_TOKEN" to TOKEN), adbPaths: MutableList<String> = mutableListOf()) =
        runDevice(cwd, phone, *args, clock = clock, env = env, adbPaths = adbPaths)

    @Test fun `a leaky full run fails the default policy with exit 1 and saves the evidence`() {
        leakyRun()
        val r = run("run", "--package", LEAKY)
        assertEquals(r.toString(), Exit.VIOLATION, r.exit)
        assertTrue(r.out, r.out.contains("Device:   $SERIAL"))
        assertTrue(r.out, r.out.contains("Run id:   $RUN_ID"))
        assertTrue(r.out, r.out.contains("--es token '<hidden>'"))
        assertTrue(r.out, r.out.contains("[   2s] running · Starting"))
        assertTrue(r.out, r.out.contains("running · Plan B · Reject (2 of 3): Agent is choosing: reject"))
        assertTrue(r.out, r.out.contains("done · $AUDIT_ID · 21/100 · below 60 · 5 findings"))
        assertTrue(r.out, r.out.contains("FAIL") && r.out.contains("failOn C2"))
        assertTrue(r.out, r.out.contains("Overall: FAIL"))
        assertTrue(r.out, r.out.contains("TraceVeil Runner: FAIL (policy violation; exit 1)"))
        assertFalse("the token never appears in output", r.out.contains(TOKEN) || r.err.contains(TOKEN))

        val saved = File(cwd, "traceveil-out/$RUN_ID")
        assertTrue(File(saved, "bundle.json").isFile)
        assertTrue(File(saved, "status.json").readText().contains("\"state\": \"done\""))

        // Exact order of adb calls: preflight, start, polls every 2 s, pull.
        val expectedStart = AdbCommands.startRun(SERIAL, StartRequest(TOKEN, LEAKY, "full", autoTap = true, childFacing = false, runId = RUN_ID))
        val readStatus = AdbCommands.readStatus(SERIAL, RUN_ID)
        assertEquals(
            listOf(
                listOf("version"), listOf("devices"),
                AdbCommands.packagePath(SERIAL, "com.dpdpxray.app"), AdbCommands.packagePath(SERIAL, LEAKY),
                expectedStart, readStatus, readStatus, readStatus, readStatus, readStatus,
                AdbCommands.pullBundle(SERIAL, RUN_ID, File(saved, "bundle.json").absolutePath),
            ),
            phone.calls,
        )
        assertEquals(listOf(2000L, 2000L, 2000L, 2000L), clock.sleeps)
    }

    @Test fun `a fixed full run passes the example policy with exit 0`() {
        phone.statuses = listOf(running("Starting", pkg = FIXED), done(100, "READY", 0, pkg = FIXED))
        phone.bundleText = FakePhone.fixedBundle()
        val r = run("run", "--package", FIXED, "--policy", policyFile, "--device", SERIAL)
        assertEquals(r.toString(), Exit.PASS, r.exit)
        assertTrue(r.out, r.out.contains("done · $AUDIT_ID · 100/100 · 85–100 band · 0 findings"))
        assertTrue(r.out, r.out.contains("Overall: PASS"))
        assertTrue(r.out, r.out.contains("TraceVeil Runner: PASS (exit 0)"))
        assertFalse("no readiness or compliance label", r.out.contains("DPDP-ready"))
    }

    @Test fun `a refusal on the phone is exit 2 with the reason and the fix`() {
        phone.statuses = listOf(refused(RefusalCode.NOT_ALLOWLISTED, "A full run clears com.leakyshop.demo's local data", "Add it to Test apps the runner may reset."))
        val r = run("run", "--package", LEAKY)
        assertEquals(r.toString(), Exit.USAGE, r.exit)
        assertTrue(r.err, r.err.contains("The phone refused the run: A full run clears com.leakyshop.demo's local data"))
        assertTrue(r.err, r.err.contains("Fix: Add it to Test apps the runner may reset."))
        assertTrue(r.err, r.err.contains("NOT_ALLOWLISTED"))
        assertTrue(phone.callsStartingWith("pull").isEmpty())
        assertTrue(File(cwd, "traceveil-out/$RUN_ID/status.json").readText().contains("\"state\": \"failed\""))
    }

    @Test fun `an audit that stops on the phone after starting is exit 2`() {
        phone.statuses = listOf(
            running("Starting"),
            FakePhone.ok(com.dpdpxray.core.automation.AutomationStatuses.encode(
                com.dpdpxray.core.automation.AutomationStatuses.failed(
                    FakePhone.runningStatus("Launching"), RefusalCode.CANCELLED, "The check was stopped on the phone before it finished.", "Run again.", FakeClock.T0 + 9000,
                ),
            )),
        )
        val r = run("run", "--package", LEAKY)
        assertEquals(r.toString(), Exit.USAGE, r.exit)
        assertTrue(r.err, r.err.contains("The check did not finish on the phone: The check was stopped on the phone before it finished."))
    }

    @Test fun `timeout is exit 2 and says the check may still finish on the phone`() {
        phone.statuses = listOf(running("Plan B · Reject (2 of 3): Waiting for a consent screen"))
        val r = run("run", "--package", LEAKY, "--timeout", "10")
        assertEquals(r.toString(), Exit.USAGE, r.exit)
        assertTrue(r.err, r.err.contains("Timed out after 10 s"))
        assertTrue(r.err, r.err.contains("last status: running · Plan B · Reject (2 of 3): Waiting for a consent screen"))
        assertTrue(r.err, r.err.contains("--timeout"))
        assertEquals("polls at 0, 2, 4, 6, 8 and 10 s", 6, phone.polls)
    }

    @Test fun `no status file within the grace period means the request never reached the app`() {
        phone.statuses = listOf(NOT_YET)
        val r = run("run", "--package", LEAKY)
        assertEquals(r.toString(), Exit.USAGE, r.exit)
        assertTrue(r.err, r.err.contains("TraceVeil wrote no status within 30 s"))
        assertEquals(16, phone.polls)
    }

    @Test fun `a half-written or unreadable status is retried, a persistent one is an error`() {
        leakyRun()
        phone.statuses = listOf(FakePhone.ok("{\"schema\": \"traceveil.automation"), running("Starting"), done(21, "AT_RISK", 5))
        assertEquals(Exit.VIOLATION, run("run", "--package", LEAKY).exit)

        val broken = FakePhone(clock).apply { statuses = listOf(FakePhone.ok("{not json")) }
        val r = runDevice(cwd, broken, "run", "--package", LEAKY, clock = clock)
        assertEquals(r.toString(), Exit.USAGE, r.exit)
        assertTrue(r.err, r.err.contains("status.json on the phone could not be read"))
    }

    @Test fun `losing the device while polling is exit 2`() {
        phone.statuses = listOf(running("Starting"), LOST, LOST, LOST)
        val r = run("run", "--package", LEAKY)
        assertEquals(r.toString(), Exit.USAGE, r.exit)
        assertTrue(r.err, r.err.contains("Lost contact with $SERIAL"))
        assertTrue(r.err, r.err.contains("device '$SERIAL' not found"))
    }

    @Test fun `one transport glitch is tolerated`() {
        leakyRun()
        phone.statuses = listOf(running("Starting"), LOST, running("Plan A", 6), done(21, "AT_RISK", 5))
        assertEquals(Exit.VIOLATION, run("run", "--package", LEAKY).exit)
    }

    @Test fun `adb that cannot read Android data folders gets a precise message`() {
        phone.statuses = listOf(ExecResult(1, "", "cat: /sdcard/Android/data/com.dpdpxray.app/files/automation/$RUN_ID/status.json: Permission denied"))
        val r = run("run", "--package", LEAKY)
        assertEquals(Exit.USAGE, r.exit)
        assertTrue(r.err, r.err.contains("adb cannot read TraceVeil's run folder"))
    }

    @Test fun `device selection`() {
        phone.devices = "List of devices attached\n\n"
        run("run", "--package", LEAKY).let {
            assertEquals(it.toString(), Exit.USAGE, it.exit)
            assertTrue(it.err, it.err.contains("No phone is connected"))
        }
        phone.devices = "List of devices attached\n$SERIAL\tunauthorized\n"
        run("run", "--package", LEAKY).let {
            assertEquals(Exit.USAGE, it.exit)
            assertTrue(it.err, it.err.contains("$SERIAL is unauthorized"))
            assertTrue(it.err, it.err.contains("Allow USB debugging"))
        }
        phone.devices = "List of devices attached\n$SERIAL\toffline\n"
        run("run", "--package", LEAKY).let { assertTrue(it.err, it.err.contains("$SERIAL is offline")) }
        phone.devices = "List of devices attached\n$SERIAL\tdevice\nemulator-5554\tdevice\n"
        run("run", "--package", LEAKY).let {
            assertEquals(Exit.USAGE, it.exit)
            assertTrue(it.err, it.err.contains("2 phones are connected ($SERIAL, emulator-5554)"))
            assertTrue(it.err, it.err.contains("--device"))
        }
        run("run", "--package", LEAKY, "--device", "OTHER123").let {
            assertEquals(Exit.USAGE, it.exit)
            assertTrue(it.err, it.err.contains("OTHER123 is not connected"))
        }
        // ANDROID_SERIAL picks the phone like adb itself does.
        leakyRun()
        val r = run("run", "--package", LEAKY, env = mapOf("TRACEVEIL_TOKEN" to TOKEN, "ANDROID_SERIAL" to "emulator-5554"))
        assertEquals(r.toString(), Exit.VIOLATION, r.exit)
        assertTrue(r.out, r.out.contains("Device:   emulator-5554"))
    }

    @Test fun `TraceVeil and the target must be installed`() {
        phone.installed -= "com.dpdpxray.app"
        run("run", "--package", LEAKY).let {
            assertEquals(Exit.USAGE, it.exit)
            assertTrue(it.err, it.err.contains("TraceVeil (com.dpdpxray.app) is not installed on $SERIAL"))
        }
        phone.installed += "com.dpdpxray.app"
        run("run", "--package", "com.example.absent").let {
            assertEquals(Exit.USAGE, it.exit)
            assertTrue(it.err, it.err.contains("com.example.absent is not installed on $SERIAL"))
            assertTrue(it.err, it.err.contains("--apk"))
        }
        assertTrue("nothing was started", phone.callsStartingWith("shell", "am").isEmpty())
    }

    @Test fun `--apk installs the build first and then checks the package`() {
        val apk = File(cwd, "builds/app debug.apk").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        phone.installAdds = "com.example.newbuild"
        phone.statuses = listOf(running("Starting", pkg = "com.example.newbuild", mode = AutomationMode.QUICK))
        run("run", "--package", "com.example.newbuild", "--apk", "builds/app debug.apk", "--mode", "quick", "--timeout", "2")
        val install = phone.callsStartingWith("install")
        assertEquals(listOf(listOf("-s", SERIAL, "install", "-r", apk.absolutePath)), install)
        val installAt = phone.calls.indexOfFirst { it.getOrNull(2) == "install" }
        val targetCheckAt = phone.calls.indexOf(AdbCommands.packagePath(SERIAL, "com.example.newbuild"))
        assertTrue(phone.calls.toString(), installAt in 0 until targetCheckAt)

        phone.installResult = ExecResult(1, "Performing Streamed Install\n", "adb: failed to install builds/app debug.apk: Failure [INSTALL_FAILED_VERSION_DOWNGRADE]\n")
        run("run", "--package", "com.example.newbuild", "--apk", "builds/app debug.apk").let {
            assertEquals(Exit.USAGE, it.exit)
            assertTrue(it.err, it.err.contains("INSTALL_FAILED_VERSION_DOWNGRADE"))
        }
        run("run", "--package", LEAKY, "--apk", "missing.apk").let {
            assertEquals(Exit.USAGE, it.exit)
            assertTrue(it.err, it.err.contains("APK not found: missing.apk"))
        }
    }

    @Test fun `am start errors are explained`() {
        phone.startResult = ExecResult(0, "Starting: Intent { cmp=com.dpdpxray.app/.automation.AutomationActivity }\nError type 3\nError: Activity class {com.dpdpxray.app/com.dpdpxray.app.automation.AutomationActivity} does not exist.\n")
        run("run", "--package", LEAKY).let {
            assertEquals(Exit.USAGE, it.exit)
            assertTrue(it.err, it.err.contains("This TraceVeil build has no Developer automation"))
        }
        phone.startResult = ExecResult(255, "", "Exception occurred while executing 'start':\njava.lang.SecurityException: Permission Denial: starting Intent { ... } requires android.permission.DUMP\n")
        run("run", "--package", LEAKY).let {
            assertEquals(Exit.USAGE, it.exit)
            assertTrue(it.err, it.err.contains("Android refused to open TraceVeil's runner screen"))
            assertTrue(it.err, it.err.contains("requires android.permission.DUMP"))
        }
        assertEquals("no polling after a failed start", 0, phone.polls)
    }

    @Test fun `adb that cannot be started is exit 2 with where to get it`() {
        val missing = object : Adb {
            override fun exec(args: List<String>, timeoutMs: Long): ExecResult = throw AdbUnavailableException("Could not run adb (\"adb\"): Cannot run program \"adb\"")
        }
        val r = runDevice(cwd, missing, "run", "--package", LEAKY, clock = clock)
        assertEquals(Exit.USAGE, r.exit)
        assertTrue(r.err, r.err.contains("Could not run adb"))
        assertTrue(r.err, r.err.contains("--adb"))
    }

    @Test fun `the adb executable comes from --adb, then ADB, then PATH`() {
        leakyRun()
        val paths = mutableListOf<String>()
        run("run", "--package", LEAKY, "--adb", "D:/sdk/platform-tools/adb.exe", adbPaths = paths)
        run("run", "--package", LEAKY, env = mapOf("TRACEVEIL_TOKEN" to TOKEN, "ADB" to "/opt/sdk/adb"), adbPaths = paths)
        run("run", "--package", LEAKY, adbPaths = paths)
        assertEquals(listOf("D:/sdk/platform-tools/adb.exe", "/opt/sdk/adb", "adb"), paths)
    }

    @Test fun `the bundle must belong to this run`() {
        phone.statuses = listOf(done(21, "AT_RISK", 5, auditId = "audit-other"))
        phone.bundleText = FakePhone.leakyBundle()
        run("run", "--package", LEAKY).let {
            assertEquals(it.toString(), Exit.USAGE, it.exit)
            assertTrue(it.err, it.err.contains("bundle.json is audit $AUDIT_ID, but the phone reported audit-other"))
        }
        phone.statuses = listOf(done(100, "READY", 0))
        phone.bundleText = FakePhone.fixedBundle()
        run("run", "--package", LEAKY).let {
            assertEquals(Exit.USAGE, it.exit)
            assertTrue(it.err, it.err.contains("bundle.json is for $FIXED, not $LEAKY"))
        }
        phone.statuses = listOf(done(21, "AT_RISK", 5))
        phone.pullResult = ExecResult(1, "", "adb: error: failed to stat remote object: No such file or directory")
        run("run", "--package", LEAKY).let {
            assertEquals(Exit.USAGE, it.exit)
            assertTrue(it.err, it.err.contains("Could not copy bundle.json from the phone"))
        }
    }

    @Test fun `json output is one document on stdout, progress goes to stderr`() {
        leakyRun()
        val r = run("run", "--package", LEAKY, "--format", "json")
        assertEquals(r.toString(), Exit.VIOLATION, r.exit)
        val o = Json.parseToJsonElement(r.out).jsonObject
        assertEquals("traceveil.ci.run.v1", o["schema"]!!.jsonPrimitive.content)
        assertEquals("1", o["exitCode"]!!.jsonPrimitive.content)
        assertEquals("false", o["passed"]!!.jsonPrimitive.content)
        assertEquals(SERIAL, o["device"]!!.jsonPrimitive.content)
        assertEquals(RUN_ID, o["runId"]!!.jsonPrimitive.content)
        assertEquals("done", o["status"]!!.jsonObject["state"]!!.jsonPrimitive.content)
        assertEquals("traceveil-out/$RUN_ID/bundle.json", o["bundle"]!!.jsonPrimitive.content)
        assertEquals("traceveil.ci.check.v1", o["check"]!!.jsonObject["schema"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, o["error"])
        assertTrue(r.err, r.err.contains("running · Starting"))

        phone.statuses = listOf(refused(RefusalCode.WATCHER_OFF, "The consent watcher is off.", "Switch it on."))
        val e = run("run", "--package", LEAKY, "--format", "json")
        assertEquals(Exit.USAGE, e.exit)
        val eo = Json.parseToJsonElement(e.out).jsonObject
        assertEquals("2", eo["exitCode"]!!.jsonPrimitive.content)
        assertTrue(eo["error"]!!.jsonPrimitive.content.contains("The consent watcher is off."))
        assertEquals("Switch it on.", eo["fix"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, eo["check"])
    }

    @Test fun `--sarif writes the findings for code scanning`() {
        leakyRun()
        val r = run("run", "--package", LEAKY, "--sarif", "reports/traceveil.sarif", "--out", "evidence")
        assertEquals(r.toString(), Exit.VIOLATION, r.exit)
        val sarif = Json.parseToJsonElement(File(cwd, "reports/traceveil.sarif").readText()).jsonObject
        assertEquals("2.1.0", sarif["version"]!!.jsonPrimitive.content)
        val results = sarif["runs"]!!.jsonArray[0].jsonObject["results"]!!.jsonArray
        assertTrue(results.any { it.jsonObject["ruleId"]!!.jsonPrimitive.content == "C2" })
        assertTrue(File(cwd, "evidence/$RUN_ID/bundle.json").isFile)
        assertTrue(r.out, r.out.contains("Wrote SARIF to reports/traceveil.sarif"))
    }

    @Test fun `quick mode warns that the default policy needs all three plans`() {
        phone.statuses = listOf(done(21, "AT_RISK", 5, mode = AutomationMode.QUICK))
        phone.bundleText = FakePhone.leakyBundle()
        val r = run("run", "--package", LEAKY, "--mode", "quick")
        assertTrue(r.out, r.out.contains("Note: quick mode captures only the Accept plan"))
        assertTrue(phone.callsStartingWith("shell", "am").single().contains("'quick'"))
        val child = run("run", "--package", LEAKY, "--child-facing")
        assertTrue(phone.callsStartingWith("shell", "am").last().joinToString(" "), phone.callsStartingWith("shell", "am").last().joinToString(" ").contains("--ez childFacing true"))
        assertEquals(child.toString(), Exit.VIOLATION, child.exit)
    }

    @Test fun `token handling`() {
        run("run", "--package", LEAKY, env = emptyMap()).let {
            assertEquals(Exit.USAGE, it.exit)
            assertTrue(it.err, it.err.contains("no pairing token"))
            assertTrue(it.err, it.err.contains("TRACEVEIL_TOKEN"))
        }
        run("run", "--package", LEAKY, "--token", "bad token!", env = emptyMap()).let {
            assertEquals(Exit.USAGE, it.exit)
            assertTrue(it.err, it.err.contains("not a TraceVeil pairing token"))
            assertFalse("a rejected token is not echoed", it.err.contains("bad token!"))
        }
        leakyRun()
        val other = "zzzzzz-zzzzzz-zzzzzz-zzzzzz"
        run("run", "--package", LEAKY, "--token", other)
        assertTrue(phone.callsStartingWith("shell", "am").single().contains("'$other'"))
        assertTrue("nothing reached the phone without a token", phone.calls.none { it.contains("'$TOKEN'") })
    }

    @Test fun `argument errors are exit 2 before any adb call`() {
        fun usage(vararg args: String, expect: String) {
            val r = run(*args)
            assertEquals(r.toString(), Exit.USAGE, r.exit)
            assertTrue(r.err, r.err.contains(expect))
        }
        usage("run", expect = "--package is required")
        usage("run", "--package", "not a package", expect = "not a valid Android package name")
        usage("run", "--package", "com.dpdpxray.app", expect = "TraceVeil cannot check itself")
        usage("run", "--package", LEAKY, "--mode", "preview", expect = "--mode must be full or quick")
        usage("run", "--package", LEAKY, "--timeout", "0", expect = "--timeout must be a whole number of seconds")
        usage("run", "--package", LEAKY, "--timeout", "ten", expect = "--timeout must be a whole number of seconds")
        usage("run", "--package", LEAKY, "--format", "sarif", expect = "unknown format")
        usage("run", "--package", LEAKY, "--policy", "nope.json", expect = "policy file not found: nope.json")
        usage("run", "--package", LEAKY, "--bogus", "x", expect = "unknown option \"--bogus\"")
        usage("run", "--package", LEAKY, "extra.json", expect = "run takes no file arguments")
        usage("run", "--package", LEAKY, "--package", FIXED, expect = "--package was given twice")
        usage("run", "--package", LEAKY, "--device", "bad serial", expect = "--device")
        usage("run", "--package", LEAKY, "--child-facing=yes", expect = "--child-facing takes no value")
        assertTrue(phone.calls.toString(), phone.calls.isEmpty())
    }

    @Test fun `help lists the run command`() {
        val r = CliHarness.run(cwd, "--help")
        assertTrue(r.out, r.out.contains("traceveil-ci run --package <pkg>"))
        assertTrue(r.out, r.out.contains("TRACEVEIL_TOKEN"))
    }
}
