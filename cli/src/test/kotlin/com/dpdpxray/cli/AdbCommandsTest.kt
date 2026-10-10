package com.dpdpxray.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The exact adb argument lists the runner sends, and how it reads adb's answers. No device is involved. */
class AdbCommandsTest {
    private val serial = "10BFBL24YS001GM"
    private val token = "abcdef-ghijkm-npqrst-uvwxyz"

    @Test fun `the start command is one explicit component with string and boolean extras only`() {
        val args = AdbCommands.startRun(serial, StartRequest(token, "com.leakyshop.demo", "full", autoTap = true, childFacing = false, runId = "run-test-1"))
        assertEquals(
            listOf(
                "-s", serial, "shell", "am", "start", "-n", "com.dpdpxray.app/com.dpdpxray.app.automation.AutomationActivity",
                // Always a new task: an earlier runner screen's task is never just brought to the front.
                "--activity-multiple-task",
                "--es", "token", "'$token'",
                "--es", "package", "'com.leakyshop.demo'",
                "--es", "mode", "'full'",
                "--ez", "autoTap", "true",
                "--ez", "childFacing", "false",
                "--es", "runId", "'run-test-1'",
            ),
            args,
        )
    }

    @Test fun `the token is hidden whenever a command is shown`() {
        val args = AdbCommands.startRun(serial, StartRequest(token, "com.leakyshop.demo", "quick", autoTap = true, childFacing = true, runId = "r1"))
        val shown = AdbCommands.display("adb", args)
        assertTrue(shown, shown.contains("--es token '<hidden>'"))
        assertTrue(shown, !shown.contains(token))
        assertTrue(shown, shown.startsWith("adb -s $serial shell am start -n com.dpdpxray.app/"))
        assertTrue(shown, shown.contains("--ez childFacing true"))
    }

    @Test fun `other commands`() {
        assertEquals(listOf("version"), AdbCommands.version())
        assertEquals(listOf("devices"), AdbCommands.devices())
        assertEquals(listOf("-s", serial, "shell", "pm", "path", "'com.dpdpxray.app'"), AdbCommands.packagePath(serial, "com.dpdpxray.app"))
        assertEquals(listOf("-s", serial, "install", "-r", "C:\\builds\\my app.apk"), AdbCommands.install(serial, "C:\\builds\\my app.apk"))
        assertEquals(
            listOf("-s", serial, "shell", "cat", "'/sdcard/Android/data/com.dpdpxray.app/files/automation/run-test-1/status.json'"),
            AdbCommands.readStatus(serial, "run-test-1"),
        )
        // adb pull takes literal paths (no device shell), so they are not quoted.
        assertEquals(
            listOf("-s", serial, "pull", "/sdcard/Android/data/com.dpdpxray.app/files/automation/run-test-1/bundle.json", "out/run-test-1/bundle.json"),
            AdbCommands.pullBundle(serial, "run-test-1", "out/run-test-1/bundle.json"),
        )
    }

    @Test fun `device shell quoting keeps every value one literal word`() {
        assertEquals("'abc'", DeviceShell.quote("abc"))
        assertEquals("''", DeviceShell.quote(""))
        assertEquals("'x y;z && reboot'", DeviceShell.quote("x y;z && reboot"))
        assertEquals("'it'\\''s'", DeviceShell.quote("it's"))
        assertEquals("'\$HOME `id`'", DeviceShell.quote("\$HOME `id`"))
    }

    @Test fun `adb devices output is parsed line by line`() {
        val text = """
            * daemon not running; starting now at tcp:5037
            * daemon started successfully
            List of devices attached
            10BFBL24YS001GM	device
            emulator-5554	offline
            192.168.1.20:5555	unauthorized

        """.trimIndent()
        assertEquals(
            listOf(AdbDevice("10BFBL24YS001GM", "device"), AdbDevice("emulator-5554", "offline"), AdbDevice("192.168.1.20:5555", "unauthorized")),
            AdbOutput.devices(text),
        )
        assertEquals(emptyList<AdbDevice>(), AdbOutput.devices("List of devices attached\n\n"))
    }

    @Test fun `adb answers are told apart`() {
        val missing = ExecResult(1, "", "cat: /sdcard/Android/data/com.dpdpxray.app/files/automation/r/status.json: No such file or directory")
        assertTrue(AdbOutput.isMissingFile(missing))
        assertNull(AdbOutput.transportProblem(missing))
        assertEquals("device '10BFBL24YS001GM' not found", AdbOutput.transportProblem(ExecResult(1, "", "adb: device '10BFBL24YS001GM' not found")))
        assertEquals("device offline", AdbOutput.transportProblem(ExecResult(1, "", "adb: device offline")))
        assertEquals("no devices/emulators found", AdbOutput.transportProblem(ExecResult(1, "", "adb: no devices/emulators found")))
        assertTrue(AdbOutput.isPermissionDenied(ExecResult(1, "", "cat: /sdcard/Android/data/x: Permission denied")))
        assertEquals(listOf("/data/app/~~x==/com.dpdpxray.app-1/base.apk"), AdbOutput.installedPaths(ExecResult(0, "package:/data/app/~~x==/com.dpdpxray.app-1/base.apk\n")))
        assertTrue(AdbOutput.installedPaths(ExecResult(1, "")).isEmpty())
        assertTrue("a failed call lists nothing", AdbOutput.installedPaths(ExecResult(1, "package:/x/base.apk")).isEmpty())
    }

    @Test fun `run ids are short, unique and safe as a folder name`() {
        val id = RunIds.generate(1_791_590_400_000L, java.util.Random(3))
        assertTrue(id, Regex("run-20261010-000000-[a-z0-9]{4}").matches(id))
        assertTrue(id.length <= 40)
        assertTrue(com.dpdpxray.core.automation.AutomationContract.isValidRunId(id))
    }
}
