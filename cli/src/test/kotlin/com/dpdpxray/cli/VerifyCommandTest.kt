package com.dpdpxray.cli

import com.dpdpxray.core.evidence.EvidenceSignatures
import com.dpdpxray.core.evidence.SecurityLevel
import com.dpdpxray.core.evidence.TestAttestation
import com.dpdpxray.core.evidence.TestPki
import com.dpdpxray.core.evidence.TrustedRoot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/** `traceveil-ci verify` with a self-made test chain (fresh keys per run; the test root is passed in, never Google's). */
class VerifyCommandTest {
    @get:Rule val temp = TemporaryFolder()

    private val bundleText = "{\n  \"schema\": \"traceveil.audit.v1\",\n  \"exportedAt\": \"2026-10-11T08:00:00Z\"\n}\n"

    private fun run(cwd: File, roots: List<TrustedRoot>?, vararg args: String): CliRun {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val ci = if (roots == null) TraceVeilCi(PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8"), cwd)
        else TraceVeilCi(PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8"), cwd, trustedRoots = { roots })
        return CliRun(ci.run(arrayOf(*args)), out.toString("UTF-8"), err.toString("UTF-8"))
    }

    /** Writes bundle.json and its signature (app naming) into a new folder; returns the folder. */
    private fun signedFolder(chain: TestPki.Chain = TestPki.chain(), sigName: String? = null, level: SecurityLevel = SecurityLevel.STRONGBOX): File {
        val dir = temp.newFolder()
        val bytes = File(dir, "TraceVeil-audit-1.json").apply { writeText(bundleText, Charsets.UTF_8) }.readBytes()
        val sig = EvidenceSignatures.create(bytes, chain.sign(bytes), chain.derChain, "2026-10-11T08:00:05Z", "1.1.0", level, "TraceVeil-audit-1.json")
        File(dir, sigName ?: EvidenceSignatures.fileNameFor("TraceVeil-audit-1.json")).writeText(EvidenceSignatures.encode(sig), Charsets.UTF_8)
        return dir
    }

    @Test
    fun `a signed bundle verifies with exit 0 and lists every check`() {
        val chain = TestPki.chain()
        val dir = signedFolder(chain)
        val r = run(dir, listOf(chain.trustedRoot), "verify", "TraceVeil-audit-1.json")
        assertEquals(r.toString(), Exit.PASS, r.exit)
        assertTrue(r.out, r.out.contains("Signature file: TraceVeil-audit-1.json.sig.json"))
        for (line in listOf("✓ Bundle unchanged", "✓ Signature", "✓ Certificate chain", "✓ Root", "✓ Key attestation", "✓ Secure hardware", "✓ Key origin", "✓ App that created the key")) {
            assertTrue("$line\n${r.out}", r.out.contains(line))
        }
        assertTrue(r.out, r.out.contains("StrongBox"))
        assertTrue(r.out, r.out.contains("com.dpdpxray.app"))
        assertTrue(r.out, r.out.contains("Result: VERIFIED"))
        assertTrue(r.out, r.out.contains("Revocation is not checked"))
        assertTrue(r.err, r.err.isEmpty())
    }

    @Test
    fun `a bundle changed after signing fails with exit 1`() {
        val chain = TestPki.chain()
        val dir = signedFolder(chain)
        File(dir, "TraceVeil-audit-1.json").writeText(bundleText.replace("08:00:00", "09:00:00"), Charsets.UTF_8)
        val r = run(dir, listOf(chain.trustedRoot), "verify", "TraceVeil-audit-1.json")
        assertEquals(r.toString(), Exit.VIOLATION, r.exit)
        assertTrue(r.out, r.out.contains("✗ Bundle unchanged"))
        assertTrue(r.out, r.out.contains("✗ Signature"))
        assertTrue(r.out, r.out.contains("Result: NOT VERIFIED"))
    }

    @Test
    fun `a self-made chain fails against the built-in Google roots`() {
        val dir = signedFolder()
        val r = run(dir, null, "verify", "TraceVeil-audit-1.json")
        assertEquals(r.toString(), Exit.VIOLATION, r.exit)
        assertTrue(r.out, r.out.contains("✗ Root"))
        assertTrue(r.out, r.out.contains("not a Google hardware attestation root"))
    }

    @Test
    fun `the shorter name and an explicit --sig are both found`() {
        val chain = TestPki.chain()
        val short = signedFolder(chain, sigName = "TraceVeil-audit-1.sig.json")
        assertEquals(Exit.PASS, run(short, listOf(chain.trustedRoot), "verify", "TraceVeil-audit-1.json").exit)
        val other = signedFolder(chain, sigName = "evidence.json")
        val r = run(other, listOf(chain.trustedRoot), "verify", "TraceVeil-audit-1.json", "--sig", "evidence.json")
        assertEquals(r.toString(), Exit.PASS, r.exit)
    }

    @Test
    fun `no signature file is a failed verification, a missing --sig or bundle is a usage error`() {
        val dir = temp.newFolder()
        File(dir, "b.json").writeText(bundleText)
        val none = run(dir, null, "verify", "b.json")
        assertEquals(none.toString(), Exit.VIOLATION, none.exit)
        assertTrue(none.out, none.out.contains("✗ Signature file") && none.out.contains("b.json.sig.json") && none.out.contains("b.sig.json"))
        assertEquals(Exit.USAGE, run(dir, null, "verify", "b.json", "--sig", "nope.json").exit)
        assertEquals(Exit.USAGE, run(dir, null, "verify", "missing.json").exit)
        assertEquals(Exit.USAGE, run(dir, null, "verify").exit)
        assertEquals(Exit.USAGE, run(dir, null, "verify", "b.json", "--format", "sarif").exit)
        assertEquals(Exit.USAGE, run(dir, null, "verify", "a.json", "b.json").exit)
    }

    @Test
    fun `json output carries the verdict, the attested facts and every check`() {
        val chain = TestPki.chain(TestAttestation(securityLevel = 1, keyMintSecurityLevel = 1))
        val dir = signedFolder(chain, level = SecurityLevel.TRUSTED_ENVIRONMENT)
        val r = run(dir, listOf(chain.trustedRoot), "verify", "TraceVeil-audit-1.json", "--format", "json")
        assertEquals(r.toString(), Exit.PASS, r.exit)
        val o = Json.parseToJsonElement(r.out).jsonObject
        assertEquals("traceveil.verify.v1", o["schema"]!!.jsonPrimitive.content)
        assertTrue(o["verified"]!!.jsonPrimitive.boolean)
        assertEquals("verified", o["status"]!!.jsonPrimitive.content)
        assertEquals("TrustedEnvironment", o["securityLevel"]!!.jsonPrimitive.content)
        assertEquals("GENERATED", o["keyOrigin"]!!.jsonPrimitive.content)
        assertEquals("com.dpdpxray.app", o["app"]!!.jsonObject["packages"]!!.jsonArray.first().jsonObject["name"]!!.jsonPrimitive.content)
        val ids = o["checks"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertTrue(ids.toString(), ids.containsAll(listOf("hash", "signature", "chain", "root", "attestation", "hardware", "origin", "app")))
        assertTrue(o["limits"]!!.jsonArray.isNotEmpty())
    }

    @Test
    fun `a key made for another app fails unless --package names it`() {
        val chain = TestPki.chain(TestAttestation(packages = listOf("com.example.other" to 1L)))
        val dir = signedFolder(chain)
        val r = run(dir, listOf(chain.trustedRoot), "verify", "TraceVeil-audit-1.json")
        assertEquals(r.toString(), Exit.VIOLATION, r.exit)
        assertTrue(r.out, r.out.contains("✗ App that created the key"))
        assertEquals(Exit.PASS, run(dir, listOf(chain.trustedRoot), "verify", "TraceVeil-audit-1.json", "--package", "com.example.other").exit)
        val digest = ByteArray(32) { (it + 1).toByte() }.joinToString(":") { "%02X".format(it) }
        assertEquals(Exit.PASS, run(dir, listOf(chain.trustedRoot), "verify", "TraceVeil-audit-1.json", "--package", "com.example.other", "--signer-sha256", digest).exit)
        assertEquals(Exit.VIOLATION, run(dir, listOf(chain.trustedRoot), "verify", "TraceVeil-audit-1.json", "--package", "com.example.other", "--signer-sha256", "ab".repeat(32)).exit)
    }

    @Test
    fun `help lists the verify command and its exit codes`() {
        val r = run(temp.newFolder(), null, "--help")
        assertTrue(r.out, r.out.contains("traceveil-ci verify <bundle.json> [--sig file] [--format text|json]"))
    }
}
