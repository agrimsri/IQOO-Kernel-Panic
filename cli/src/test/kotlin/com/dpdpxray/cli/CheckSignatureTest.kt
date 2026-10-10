package com.dpdpxray.cli

import com.dpdpxray.cli.CliHarness.run
import com.dpdpxray.cli.CliHarness.runTrusting
import com.dpdpxray.cli.CliHarness.samples
import com.dpdpxray.cli.CliHarness.signNextTo
import com.dpdpxray.cli.CliHarness.writeText
import com.dpdpxray.cli.samples.SampleBundles
import com.dpdpxray.core.evidence.TestPki
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
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

/**
 * `traceveil-ci check` reports the hardware signature next to each bundle (absent, verified, NOT verified) and, with
 * requireSignature, gates on it. Signatures are made with a self-made test chain; the test root is passed in.
 */
class CheckSignatureTest {
    @get:Rule val temp = TemporaryFolder()

    private val dir: File by lazy { samples(temp.newFolder("bundles")) }
    private val fixed get() = SampleBundles.FIXED
    private val chain = TestPki.chain()
    private val roots get() = listOf(chain.trustedRoot)

    private fun has(text: String, regex: String) = Regex(regex).containsMatchIn(text)
    private fun examplePolicy() = writeText(dir, "policy.json", """{"failOn": ["C2"], "minScore": 85, "maxHigh": 0}""").name
    private fun strictPolicy() = writeText(dir, "strict.json", """{"failOn": ["C2"], "minScore": 85, "maxHigh": 0, "requireSignature": true}""").name
    private fun signFixed(): File = signNextTo(File(dir, fixed), chain)

    @Test
    fun `an unsigned bundle shows the signature as absent and passes when the policy does not require one`() {
        val r = runTrusting(dir, roots, "check", fixed, "--policy", examplePolicy())
        assertEquals(r.toString(), Exit.PASS, r.exit)
        assertTrue(
            r.out,
            r.out.contains("Signature: absent (no leakyshop-fixed_sample.json.sig.json or leakyshop-fixed_sample.sig.json next to the bundle)"),
        )
        assertTrue(r.out, has(r.out, "OFF\\s+requireSignature\\s+not required"))
    }

    @Test
    fun `a valid signature is verified and named in text, json and sarif`() {
        signFixed()
        val r = runTrusting(dir, roots, "check", fixed, "--policy", examplePolicy())
        assertEquals(r.toString(), Exit.PASS, r.exit)
        assertTrue(r.out, r.out.contains("Signature: verified (StrongBox, chain ends at TraceVeil test root (not Google)) · leakyshop-fixed_sample.json.sig.json"))

        val j = runTrusting(dir, roots, "check", fixed, "--policy", "policy.json", "--format", "json")
        val sig = Json.parseToJsonElement(j.out).jsonObject["bundles"]!!.jsonArray.single().jsonObject["signature"]!!.jsonObject
        assertEquals("verified", sig["status"]!!.jsonPrimitive.content)
        assertTrue(sig["verified"]!!.jsonPrimitive.boolean)
        assertEquals("leakyshop-fixed_sample.json.sig.json", sig["file"]!!.jsonPrimitive.content)
        assertEquals("StrongBox", sig["securityLevel"]!!.jsonPrimitive.content)
        assertEquals("TraceVeil test root (not Google)", sig["root"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(listOf("com.dpdpxray.app"), sig["appPackages"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(sig["failedChecks"]!!.jsonArray.isEmpty())

        val s = runTrusting(dir, roots, "check", fixed, "--format", "sarif")
        val props = Json.parseToJsonElement(s.out).jsonObject["runs"]!!.jsonArray.single().jsonObject["invocations"]!!.jsonArray.single()
            .jsonObject["properties"]!!.jsonObject
        val entry = props["signatures"]!!.jsonArray.single().jsonObject
        assertEquals(fixed, entry["bundle"]!!.jsonPrimitive.content)
        assertEquals("verified", entry["status"]!!.jsonPrimitive.content)
        assertTrue(entry["text"]!!.jsonPrimitive.content.startsWith("verified (StrongBox"))
    }

    @Test
    fun `a bundle changed after signing is NOT verified, and requireSignature fails the gate`() {
        signFixed()
        val file = File(dir, fixed)
        file.writeText(file.readText().replace(SampleBundles.EXPORTED_AT, "2026-10-10T00:00:01Z"), Charsets.UTF_8)

        val r = runTrusting(dir, roots, "check", fixed, "--policy", examplePolicy())
        assertEquals("not required, so a broken signature is reported but does not fail\n$r", Exit.PASS, r.exit)
        assertTrue(r.out, r.out.contains("Signature: NOT verified (2 checks failed: Bundle unchanged, Signature) · leakyshop-fixed_sample.json.sig.json"))
        assertTrue(r.out, r.out.contains("traceveil-ci verify"))

        val strict = runTrusting(dir, roots, "check", fixed, "--policy", strictPolicy())
        assertEquals(strict.toString(), Exit.VIOLATION, strict.exit)
        assertTrue(strict.out, has(strict.out, "FAIL\\s+requireSignature\\s+the signature did not verify \\(2 checks failed: Bundle unchanged, Signature\\)"))
        assertTrue(strict.out, strict.out.contains("Result: FAIL (1 policy violation)"))

        val j = runTrusting(dir, roots, "check", fixed, "--policy", "strict.json", "--format", "json")
        val o = Json.parseToJsonElement(j.out).jsonObject
        val sig = o["bundles"]!!.jsonArray.single().jsonObject["signature"]!!.jsonObject
        assertEquals("failed", sig["status"]!!.jsonPrimitive.content)
        assertFalse(sig["verified"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("hash", "signature"), sig["failedChecks"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertTrue(o["policy"]!!.jsonObject["requireSignature"]!!.jsonPrimitive.boolean)

        val s = runTrusting(dir, roots, "check", fixed, "--policy", "strict.json", "--format", "sarif")
        val props = Json.parseToJsonElement(s.out).jsonObject["runs"]!!.jsonArray.single().jsonObject["invocations"]!!.jsonArray.single()
            .jsonObject["properties"]!!.jsonObject
        assertEquals("failed", props["signatures"]!!.jsonArray.single().jsonObject["status"]!!.jsonPrimitive.content)
        assertTrue(props.toString(), props["policyViolations"]!!.jsonArray.any { it.jsonPrimitive.content.contains("requireSignature") })
    }

    @Test
    fun `requireSignature fails an unsigned bundle and passes a verified one`() {
        val unsigned = runTrusting(dir, roots, "check", fixed, "--policy", strictPolicy())
        assertEquals(unsigned.toString(), Exit.VIOLATION, unsigned.exit)
        assertTrue(
            unsigned.out,
            has(unsigned.out, "FAIL\\s+requireSignature\\s+no signature file next to the bundle \\(looked for leakyshop-fixed_sample.json.sig.json and leakyshop-fixed_sample.sig.json\\)"),
        )
        signFixed()
        val signed = runTrusting(dir, roots, "check", fixed, "--policy", "strict.json")
        assertEquals(signed.toString(), Exit.PASS, signed.exit)
        assertTrue(signed.out, has(signed.out, "PASS\\s+requireSignature\\s+verified: StrongBox key, chain ends at TraceVeil test root \\(not Google\\)"))
    }

    @Test
    fun `--require-signature turns the rule on over any policy`() {
        val r = runTrusting(dir, roots, "check", fixed, "--policy", examplePolicy(), "--require-signature")
        assertEquals(r.toString(), Exit.VIOLATION, r.exit)
        assertTrue(r.out, r.out.contains("Policy: policy file policy.json + --require-signature"))
        assertTrue(r.out, has(r.out, "FAIL\\s+requireSignature\\s+no signature file next to the bundle"))
        signFixed()
        assertEquals(Exit.PASS, runTrusting(dir, roots, "check", fixed, "--policy", "policy.json", "--require-signature").exit)
        assertEquals(Exit.USAGE, run(dir, "check", fixed, "--require-signature=yes").exit)
    }

    @Test
    fun `a self-made chain is not trusted by default`() {
        signFixed()
        val r = run(dir, "check", fixed, "--policy", strictPolicy())
        assertEquals(r.toString(), Exit.VIOLATION, r.exit)
        assertTrue(r.out, r.out.contains("Signature: NOT verified (1 check failed: Root)"))
    }

    @Test
    fun `--sig names the signature file of one bundle`() {
        signFixed().renameTo(File(dir, "evidence.json"))
        assertTrue(runTrusting(dir, roots, "check", fixed).out.contains("Signature: absent"))
        val r = runTrusting(dir, roots, "check", fixed, "--policy", strictPolicy(), "--sig", "evidence.json")
        assertEquals(r.toString(), Exit.PASS, r.exit)
        assertTrue(r.out, r.out.contains("Signature: verified (StrongBox, chain ends at TraceVeil test root (not Google)) · evidence.json"))

        val two = runTrusting(dir, roots, "check", fixed, SampleBundles.LEAKY, "--sig", "evidence.json")
        assertEquals(two.toString(), Exit.USAGE, two.exit)
        assertTrue(two.err, two.err.contains("--sig names the signature of one bundle"))
        val missing = runTrusting(dir, roots, "check", fixed, "--sig", "nope.json")
        assertEquals(Exit.USAGE, missing.exit)
        assertTrue(missing.err, missing.err.contains("signature file not found: nope.json"))
    }

    @Test
    fun `an unreadable signature file is NOT verified, not a usage error`() {
        writeText(dir, "$fixed.sig.json", "{ not json")
        val r = runTrusting(dir, roots, "check", fixed, "--policy", strictPolicy())
        assertEquals(r.toString(), Exit.VIOLATION, r.exit)
        assertTrue(r.out, r.out.contains("Signature: NOT verified (1 check failed: Signature file)"))
    }

    @Test
    fun `json carries the signature object even when it is absent`() {
        val o: JsonObject = Json.parseToJsonElement(runTrusting(dir, roots, "check", fixed, "--format", "json").out).jsonObject
        val sig = o["bundles"]!!.jsonArray.single().jsonObject["signature"]!!.jsonObject
        assertEquals("absent", sig["status"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, sig["file"])
        assertEquals(listOf("$fixed.sig.json", "leakyshop-fixed_sample.sig.json"), sig["looked"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(false, o["policy"]!!.jsonObject["requireSignature"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `help documents the signature option and policy key`() {
        val r = run(dir, "--help")
        assertTrue(r.out, r.out.contains("[--sig file] [--require-signature]"))
        assertTrue(r.out, r.out.contains("requireSignature  [false]"))
    }
}
