package com.dpdpxray.core.automation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

/** status.json written by the phone and read by traceveil-ci run. */
class AutomationStatusTest {
    private val started = 1_791_590_400_000L // 10 Oct 2026 00:00:00 UTC
    private val request = RunRequest("run-1", "com.leakyshop.demo", AutomationMode.FULL, autoTap = true, childFacing = false)

    @Test fun `a running status names the run, the request and the start time`() {
        val s = AutomationStatuses.running(request, resetAppData = true, startedMs = started, appVersion = "1.1.0")
        assertEquals(RunState.RUNNING, s.state)
        assertEquals("2026-10-10T00:00:00Z", s.startedAt)
        assertEquals(s.startedAt, s.updatedAt)
        assertNull(s.finishedAt)
        assertNull(s.auditId)
        val o = Json.parseToJsonElement(AutomationStatuses.encode(s)).jsonObject
        assertEquals("traceveil.automation.status.v1", (o["schema"] as JsonPrimitive).content)
        assertEquals("running", (o["state"] as JsonPrimitive).content)
        assertEquals("com.leakyshop.demo", (o["package"] as JsonPrimitive).content)
        assertEquals("full", (o["mode"] as JsonPrimitive).content)
        for (key in listOf("state", "reason", "auditId", "score", "band", "startedAt", "finishedAt", "appVersion")) {
            assertTrue("status.json always has \"$key\"", key in o)
        }
    }

    @Test fun `done and failed statuses round-trip`() {
        val running = AutomationStatuses.running(request, resetAppData = true, startedMs = started, appVersion = "1.1.0")
        val done = AutomationStatuses.done(running, auditId = "audit-1791590401000", score = 21, band = "AT_RISK", findingCount = 5, nowMs = started + 70_500)
        assertEquals(RunState.DONE, done.state)
        assertEquals("2026-10-10T00:01:10Z", done.finishedAt)
        assertEquals(done, AutomationStatuses.decode(AutomationStatuses.encode(done)))

        val failed = AutomationStatuses.failed(running, RefusalCode.AUDIT_FAILED, "The check stopped on the phone: Capture stopped", "Look at the phone.", started + 5_000)
        assertEquals(RunState.FAILED, failed.state)
        assertEquals("AUDIT_FAILED", failed.reasonCode)
        assertEquals(failed, AutomationStatuses.decode(AutomationStatuses.encode(failed)))
    }

    @Test fun `an inconclusive result keeps the score unknown, never zero`() {
        val running = AutomationStatuses.running(request, resetAppData = true, startedMs = started, appVersion = "1.1.0")
        val done = AutomationStatuses.done(running, auditId = "audit-1", score = null, band = "INCONCLUSIVE", findingCount = 0, nowMs = started)
        val o = Json.parseToJsonElement(AutomationStatuses.encode(done)).jsonObject
        assertEquals("null", o["score"].toString())
    }

    @Test fun `a refusal records the reason, the fix and what was asked`() {
        val input = RunRequestInput("t", "com.example.shop", "full", true, null, "run-2")
        val refusal = RunDecision.Refused("run-2", RefusalCode.NOT_ALLOWLISTED, "not allowlisted", "add it")
        val s = AutomationStatuses.refused(refusal, input, nowMs = started, appVersion = "1.1.0")
        assertEquals(RunState.FAILED, s.state)
        assertEquals("not allowlisted", s.reason)
        assertEquals("add it", s.fix)
        assertEquals("NOT_ALLOWLISTED", s.reasonCode)
        assertEquals("com.example.shop", s.packageName)
        assertEquals(s.startedAt, s.finishedAt)
        assertFalse("the token is never written to status.json", AutomationStatuses.encode(s).contains("\"t\""))
    }

    @Test fun `a refusal never echoes an invalid package or mode verbatim into the status`() {
        val input = RunRequestInput("t", "com.x;reboot", "full\nmore", null, null, "run-3")
        val s = AutomationStatuses.refused(RunDecision.Refused("run-3", RefusalCode.BAD_REQUEST, "bad", "fix"), input, started, "1.1.0")
        assertNull(s.packageName)
        assertNull(s.mode)
    }

    @Test fun `readers ignore unknown fields but reject other schemas and broken text`() {
        val running = AutomationStatuses.running(request, resetAppData = false, startedMs = started, appVersion = "1.1.0")
        val extended = JsonObject(Json.parseToJsonElement(AutomationStatuses.encode(running)).jsonObject + ("future" to JsonPrimitive(1)))
        assertEquals(running, AutomationStatuses.decode(extended.toString()))
        for (bad in listOf("", "{", "[]", """{"schema":"traceveil.audit.v1"}""", """{"schema":"traceveil.automation.status.v1"}""")) {
            try {
                AutomationStatuses.decode(bad)
                fail("expected a failure for $bad")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message.orEmpty().isNotBlank())
            }
        }
    }

    @Test fun `pairing tokens are long, unambiguous and different each time`() {
        val a = PairingTokens.generate()
        val b = PairingTokens.generate()
        assertNotEquals(a, b)
        assertTrue(a, Regex("[a-km-np-z2-9]{6}(-[a-km-np-z2-9]{6}){3}").matches(a))
        assertTrue(AutomationContract.isValidToken(a))
        assertEquals(PairingTokens.generate(Random(7)), PairingTokens.generate(Random(7)))
        assertEquals(120, PairingTokens.ENTROPY_BITS)
    }

    @Test fun `the bundle signature binds bundle json to the pairing token`() {
        val token = PairingTokens.generate(Random(2))
        val bundle = "{\"schema\": \"traceveil.audit.v1\"}\n".toByteArray()
        val sig = BundleSignatures.sign(token, bundle)
        assertTrue(sig, Regex("[0-9a-f]{64}").matches(sig))
        assertTrue(BundleSignatures.verify(token, bundle, sig))
        assertFalse("another token", BundleSignatures.verify(PairingTokens.generate(Random(3)), bundle, sig))
        assertFalse("a changed byte", BundleSignatures.verify(token, bundle.copyOf().also { it[3] = 'X'.code.toByte() }, sig))
        assertFalse("no signature", BundleSignatures.verify(token, bundle, null))
        assertFalse("not hex", BundleSignatures.verify(token, bundle, "zz"))
        // RFC 4231 test case 2: HMAC-SHA256 with key "Jefe".
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            BundleSignatures.sign("Jefe", "what do ya want for nothing?".toByteArray()))

        val running = AutomationStatuses.running(request, resetAppData = true, startedMs = started, appVersion = "1.1.0")
        val done = AutomationStatuses.done(running, "audit-1", 21, "AT_RISK", 5, started, bundleSignature = sig)
        assertEquals(sig, AutomationStatuses.decode(AutomationStatuses.encode(done)).bundleSignature)
    }

    @Test fun `token comparison needs an exact match`() {
        val t = PairingTokens.generate(Random(1))
        assertTrue(PairingTokens.matches(t, t))
        assertFalse(PairingTokens.matches(t, t.uppercase()))
        assertFalse(PairingTokens.matches(t, "$t "))
        assertFalse(PairingTokens.matches(null, t))
        assertFalse(PairingTokens.matches(t, null))
        assertFalse(PairingTokens.matches("", ""))
    }
}
