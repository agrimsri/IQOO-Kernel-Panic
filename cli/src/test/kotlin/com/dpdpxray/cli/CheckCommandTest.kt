package com.dpdpxray.cli

import com.dpdpxray.cli.CliHarness.run
import com.dpdpxray.cli.CliHarness.samples
import com.dpdpxray.cli.CliHarness.write
import com.dpdpxray.cli.CliHarness.writeText
import com.dpdpxray.cli.samples.SampleBundles
import com.dpdpxray.core.export.AuditBundles
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.ConsentKind
import kotlinx.serialization.json.Json
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

/** `traceveil-ci check` on the synthetic sample bundles. */
class CheckCommandTest {
    @get:Rule val temp = TemporaryFolder()

    private val dir: File by lazy { samples(temp.newFolder("bundles")) }
    private val leaky get() = SampleBundles.LEAKY
    private val fixed get() = SampleBundles.FIXED

    private fun has(text: String, regex: String) = Regex(regex).containsMatchIn(text)

    @Test
    fun `leaky sample fails the default policy on C2`() {
        val r = run(dir, "check", leaky)
        assertEquals(r.toString(), Exit.VIOLATION, r.exit)
        assertTrue(r.out, has(r.out, "FAIL\\s+failOn C2\\s+C2 Trackers contacted after the user refused \\(Critical, OBSERVED evidence\\)"))
        assertTrue(r.out, r.out.contains("C2     Critical  OBSERVED  Trackers contacted after the user refused"))
        assertTrue(r.out, r.out.contains("first evidence: graph.facebook.com"))
        assertTrue(r.out, r.out.contains("Score:    21/100 · At risk (re-evaluated; same as stored in the bundle)"))
        assertTrue(r.out, r.out.contains("Result: FAIL (1 policy violation)"))
        assertTrue(r.out, r.out.contains("Warning:  SYNTHETIC SAMPLE"))
        assertTrue(r.out, r.out.contains("not proof that personal data was sent"))
        assertTrue(r.err, r.err.isEmpty())
    }

    @Test
    fun `fixed sample passes the default policy`() {
        val r = run(dir, "check", fixed)
        assertEquals(r.toString(), Exit.PASS, r.exit)
        assertTrue(r.out, r.out.contains("Score:    100/100 · DPDP-ready"))
        assertTrue(r.out, r.out.contains("No findings in the captured evidence"))
        assertTrue(r.out, has(r.out, "PASS\\s+failOn C2\\s+no C2 finding in the captured evidence"))
        assertTrue(r.out, has(r.out, "PASS\\s+requireFreshState\\s+all 3 plans started from an approved fresh reset"))
        assertTrue(r.out, r.out.contains("Overall: PASS (1 bundle checked, no policy violations; exit 0)"))
        assertFalse("never a compliance claim", r.out.contains("compliant", ignoreCase = true) && !r.out.contains("not legal advice or a compliance certificate"))
    }

    @Test
    fun `the example policy also gates on score and high findings`() {
        writeText(dir, "policy.json", """{"failOn": ["C2","C9"], "minScore": 85, "maxHigh": 0, "requireFreshState": true, "allowInconclusive": false}""")
        val l = run(dir, "check", leaky, "--policy", "policy.json")
        assertEquals(l.toString(), Exit.VIOLATION, l.exit)
        assertTrue(l.out, has(l.out, "FAIL\\s+minScore\\s+score 21 is below the minimum 85"))
        assertTrue(l.out, has(l.out, "FAIL\\s+maxHigh\\s+4 findings at High or Critical severity \\(C2, C1, C3, C4\\); the policy allows 0"))
        assertTrue(l.out, l.out.contains("Result: FAIL (3 policy violations)"))
        assertTrue(l.out, l.out.contains("Policy: policy file policy.json"))
        assertEquals(Exit.PASS, run(dir, "check", fixed, "--policy=policy.json").exit)
    }

    @Test
    fun `the shipped example policy in docs ci gates the samples`() {
        val policy = File(System.getProperty("traceveil.ciDocsDir") ?: "../docs/ci", "traceveil-policy.json")
        assertTrue("missing ${policy.path}", policy.isFile)
        assertEquals(Policy(minScore = 85, maxHigh = 0), Policy.parse(policy.readText()))
        assertEquals(Exit.VIOLATION, run(dir, "check", leaky, "--policy", policy.absolutePath).exit)
        assertEquals(Exit.PASS, run(dir, "check", fixed, "--policy", policy.absolutePath).exit)
    }

    @Test
    fun `kept-data sample cannot pass by default because consent history is unknown`() {
        val r = run(dir, "check", SampleBundles.KEPT_DATA)
        assertEquals(r.toString(), Exit.VIOLATION, r.exit)
        assertTrue(r.out, r.out.contains("Score:    100/100"))
        assertTrue(r.out, has(r.out, "FAIL\\s+requireFreshState\\s+Plan C · No interaction ran on the app's kept data"))
        assertTrue(r.out, has(r.out, "FAIL\\s+requirePlans\\s+Plan A · Accept was not captured; Plan B · Reject was not captured"))
        assertTrue(r.out, has(r.out, "NOT ESTABLISHED\\s+failOn C2\\s+C2 could not be tested: no Reject choice was recorded"))
        assertTrue(r.out, r.out.contains("U1     Info      OBSERVED  Consent history unknown — app kept its data"))
        assertTrue(r.out, r.out.contains("S1     Info      INFERRED  Same-company servers contacted"))

        writeText(dir, "relaxed.json", """{"requireFreshState": false, "requirePlans": ["SILENT"]}""")
        val relaxed = run(dir, "check", SampleBundles.KEPT_DATA, "--policy", "relaxed.json")
        assertEquals(relaxed.toString(), Exit.PASS, relaxed.exit)
        assertTrue(relaxed.out, has(relaxed.out, "OFF\\s+requireFreshState\\s+not required"))
    }

    @Test
    fun `an accept-only capture cannot pass and C2 stays not established`() {
        val acceptOnly = SampleBundles.leaky().let { b -> b.copy(sessions = b.sessions.filter { it.plan == AuditPlan.ACCEPT }) }
        write(dir, "accept-only.json", acceptOnly)
        val r = run(dir, "check", "accept-only.json")
        assertEquals(r.toString(), Exit.VIOLATION, r.exit)
        assertTrue(r.out, has(r.out, "NOT ESTABLISHED\\s+failOn C2\\s+C2 could not be tested"))
        assertTrue(r.out, r.out.contains("Plan B · Reject was not captured; Plan C · No interaction was not captured"))
    }

    @Test
    fun `a reject plan whose choice was not recorded fails requirePlans`() {
        val missed = SampleBundles.leaky().let { b ->
            b.copy(sessions = b.sessions.map { s ->
                if (s.plan == AuditPlan.REJECT) s.copy(consentEvents = s.consentEvents.filter { it.kind != ConsentKind.REJECT }) else s
            })
        }
        write(dir, "missed.json", missed)
        val r = run(dir, "check", "missed.json")
        assertEquals(r.toString(), Exit.VIOLATION, r.exit)
        assertTrue(r.out, r.out.contains("Plan B · Reject: a consent screen appeared but no Reject choice was recorded"))
        assertTrue(r.out, has(r.out, "NOT ESTABLISHED\\s+failOn C2"))
        assertTrue(r.out, r.out.contains("Stored:   21/100 · At risk (score saved on the phone; differs from today's rules)"))
    }

    @Test
    fun `an inconclusive result fails unless the policy allows it`() {
        val quiet = SampleBundles.fixed().let { b -> b.copy(sessions = b.sessions.map { it.copy(netEvents = emptyList()) }) }
        write(dir, "quiet.json", quiet)
        val r = run(dir, "check", "quiet.json")
        assertEquals(r.toString(), Exit.VIOLATION, r.exit)
        assertTrue(r.out, r.out.contains("Score:    Inconclusive (score not established) (re-evaluated with current rules)"))
        assertTrue(r.out, has(r.out, "FAIL\\s+allowInconclusive\\s+result is inconclusive"))
        writeText(dir, "allow.json", """{"allowInconclusive": true, "minScore": 85}""")
        val allowed = run(dir, "check", "quiet.json", "--policy", "allow.json")
        assertEquals(allowed.toString(), Exit.PASS, allowed.exit)
        assertTrue(allowed.out, has(allowed.out, "NOT ESTABLISHED\\s+minScore\\s+score not established"))
    }

    @Test
    fun `stored and re-evaluated results are both reported when they differ`() {
        val stale = SampleBundles.leaky().copy(result = SampleBundles.fixed().result)
        write(dir, "stale.json", stale)
        val r = run(dir, "check", "stale.json", "--format", "json")
        assertEquals(Exit.VIOLATION, r.exit)
        val b = Json.parseToJsonElement(r.out).jsonObject["bundles"]!!.jsonArray[0].jsonObject
        assertEquals("true", b["resultChanged"]!!.jsonPrimitive.content)
        assertEquals("100", b["storedResult"]!!.jsonObject["score"]!!.jsonPrimitive.content)
        assertEquals("21", b["currentResult"]!!.jsonObject["score"]!!.jsonPrimitive.content)
        val text = run(dir, "check", "stale.json").out
        assertTrue(text, text.contains("Score:    21/100 · At risk (re-evaluated with current rules)"))
        assertTrue(text, text.contains("Stored:   100/100 · DPDP-ready"))
        assertTrue(text, text.contains("only after re-evaluation: C2, C1, C3, C4, C10"))
    }

    @Test
    fun `json output carries the gate, the findings and the caveat`() {
        val r = run(dir, "check", leaky, fixed, "--format", "json")
        assertEquals(Exit.VIOLATION, r.exit)
        val root = Json.parseToJsonElement(r.out).jsonObject
        assertEquals("traceveil.ci.check.v1", root["schema"]!!.jsonPrimitive.content)
        assertEquals("false", root["passed"]!!.jsonPrimitive.content)
        assertEquals("1", root["exitCode"]!!.jsonPrimitive.content)
        val (l, f) = root["bundles"]!!.jsonArray.map { it.jsonObject }
        assertEquals(leaky, l["path"]!!.jsonPrimitive.content)
        val c2 = l["findings"]!!.jsonArray.first().jsonObject
        assertEquals("C2", c2["checkId"]!!.jsonPrimitive.content)
        assertEquals("CRITICAL", c2["severity"]!!.jsonPrimitive.content)
        assertEquals("OBSERVED", c2["evidenceLabel"]!!.jsonPrimitive.content)
        assertTrue(l["rules"]!!.jsonArray.any { it.jsonObject["rule"]!!.jsonPrimitive.content == "failOn C2" && it.jsonObject["status"]!!.jsonPrimitive.content == "FAIL" })
        assertEquals("true", f["passed"]!!.jsonPrimitive.content)
        assertTrue(root["caveat"]!!.jsonPrimitive.content.contains("not proof"))
        assertFalse("no penalty figures in CI output", r.out.contains("₹") || r.out.contains("crore"))
    }

    @Test
    fun `several bundles fail together when any one fails`() {
        val r = run(dir, "check", fixed, leaky)
        assertEquals(Exit.VIOLATION, r.exit)
        assertTrue(r.out, r.out.contains("Overall: FAIL (1 of 2 bundles violated the policy, 1 violation; exit 1)"))
    }

    @Test
    fun `out writes the chosen format to a file and keeps the text report on stdout`() {
        val r = run(dir, "check", leaky, "--format", "sarif", "--out", "reports/traceveil.sarif")
        assertEquals(Exit.VIOLATION, r.exit)
        val sarif = File(dir, "reports/traceveil.sarif").readText()
        assertEquals("2.1.0", Json.parseToJsonElement(sarif).jsonObject["version"]!!.jsonPrimitive.content)
        assertTrue(r.out, r.out.contains("Result: FAIL"))
        assertTrue(r.out, r.out.contains("Wrote the sarif report to reports/traceveil.sarif"))
    }

    @Test
    fun `usage and input problems exit 2 with a reason`() {
        writeText(dir, "broken.json", "{broken")
        writeText(dir, "quarantine.json", AuditBundles.encodeQuarantine(SampleBundles.quarantine()))
        writeText(dir, "typo.json", """{"minscore": 85}""")
        val cases = listOf(
            arrayOf<String>() to "no command given",
            arrayOf("gate", leaky) to "unknown command",
            arrayOf("check") to "at least one bundle",
            arrayOf("check", "nope.json") to "nope.json: no such file",
            arrayOf("check", "broken.json") to "Not valid JSON",
            arrayOf("check", "quarantine.json") to "quarantine report",
            arrayOf("check", leaky, "--strict") to "unknown option \"--strict\"",
            arrayOf("check", leaky, "--format", "xml") to "unknown format \"xml\"",
            arrayOf("check", leaky, "--policy") to "--policy needs a value",
            arrayOf("check", leaky, "--policy", "missing.json") to "policy file not found",
            arrayOf("check", leaky, "--policy", "typo.json") to "unknown policy key \"minscore\"",
            arrayOf("diff", leaky) to "exactly two bundle files",
            arrayOf("diff", leaky, fixed, "--format", "sarif") to "unknown format \"sarif\"",
        )
        for ((args, fragment) in cases) {
            val r = run(dir, *args)
            assertEquals("${args.toList()}: $r", Exit.USAGE, r.exit)
            assertTrue("${args.toList()} should say '$fragment': ${r.err}", r.err.contains(fragment))
        }
    }

    @Test
    fun `an internal error exits 2, never 1 like a policy violation`() {
        val out = java.io.ByteArrayOutputStream()
        val err = java.io.ByteArrayOutputStream()
        val broken = lazy<Gate> { throw IllegalStateException("catalogue missing") }
        val code = TraceVeilCi(java.io.PrintStream(out, true, "UTF-8"), java.io.PrintStream(err, true, "UTF-8"), dir, broken).run(arrayOf("check", leaky))
        assertEquals(Exit.USAGE, code)
        assertTrue(err.toString("UTF-8"), err.toString("UTF-8").contains("internal error: catalogue missing"))
    }

    @Test
    fun `help and version exit 0`() {
        val help = run(dir, "--help")
        assertEquals(Exit.PASS, help.exit)
        assertTrue(help.out.contains("traceveil-ci check <bundle.json>..."))
        assertTrue(help.out.contains("requireFreshState [true]"))
        assertEquals(Exit.PASS, run(dir, "--version").exit)
    }
}
