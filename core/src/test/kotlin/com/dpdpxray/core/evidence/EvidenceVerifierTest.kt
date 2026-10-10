package com.dpdpxray.core.evidence

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Sign-and-verify with a self-made chain (fresh test keys, test root passed in explicitly). */
class EvidenceVerifierTest {
    private val bundle = """{"schema":"traceveil.audit.v1","exportedAt":"2026-10-11T08:00:00Z"}""".toByteArray()

    private fun signed(
        chain: TestPki.Chain = TestPki.chain(),
        bytes: ByteArray = bundle,
        level: SecurityLevel = SecurityLevel.STRONGBOX,
        createdAt: String = "2026-10-11T08:00:05Z",
    ): String = EvidenceSignatures.encode(
        EvidenceSignatures.create(
            bundle = bytes, signature = chain.sign(bytes), certificateChain = chain.derChain, createdAt = createdAt,
            appVersion = "1.1.0", securityLevel = level, signedFile = "TraceVeil-audit-1.json",
        ),
    )

    private fun verify(sig: String, chain: TestPki.Chain, bytes: ByteArray = bundle, expectedSigner: String? = null, pkg: String = "com.dpdpxray.app") =
        EvidenceVerifier.verify(bytes, sig, roots = listOf(chain.trustedRoot), expectedPackage = pkg, expectedSignerSha256 = expectedSigner)

    private fun EvidenceReport.status(id: String) = checks.firstOrNull { it.id == id }?.status

    @Test
    fun `a bundle signed by the attested key verifies, and every check says why`() {
        val chain = TestPki.chain()
        val r = verify(signed(chain), chain)
        assertTrue(r.checks.joinToString("\n"), r.verified)
        for (id in listOf("file", "hash", "signature", "chain", "root", "attestation", "hardware", "origin", "app")) {
            assertEquals(id, CheckStatus.PASS, r.status(id))
        }
        assertEquals(SecurityLevel.STRONGBOX, r.keyDescription!!.attestationSecurityLevel)
        assertTrue(r.checks.first { it.id == "app" }.detail.contains("com.dpdpxray.app"))
        assertTrue(r.checks.first { it.id == "hardware" }.detail.contains("StrongBox"))
        // Honest limits are always part of the result.
        assertTrue(r.limits.any { it.contains("Revocation") })
        assertTrue(r.limits.any { it.contains("does not prove") })
    }

    @Test
    fun `a tampered bundle fails both the hash and the signature`() {
        val chain = TestPki.chain()
        val sig = signed(chain)
        val tampered = bundle.copyOf().also { it[it.size - 3] = '9'.code.toByte() }
        val r = verify(sig, chain, tampered)
        assertFalse(r.verified)
        assertEquals(CheckStatus.FAIL, r.status("hash"))
        assertEquals(CheckStatus.FAIL, r.status("signature"))
        assertTrue(r.checks.first { it.id == "hash" }.detail.contains("changed"))
    }

    @Test
    fun `even one added byte (a trailing newline) fails`() {
        val chain = TestPki.chain()
        assertFalse(verify(signed(chain), chain, bundle + '\n'.code.toByte()).verified)
    }

    @Test
    fun `a self-made chain fails against Google's real roots`() {
        val chain = TestPki.chain()
        val r = EvidenceVerifier.verify(bundle, signed(chain))
        assertFalse(r.verified)
        assertEquals(CheckStatus.PASS, r.status("signature"))
        assertEquals(CheckStatus.PASS, r.status("chain"))
        assertEquals(CheckStatus.FAIL, r.status("root"))
        assertTrue(r.checks.first { it.id == "root" }.detail.contains("not a Google hardware attestation root"))
        // What an untrusted chain says about itself is never shown as a pass.
        for (id in listOf("hardware", "origin", "app")) {
            val c = r.checks.first { it.id == id }
            assertEquals(id, CheckStatus.WARN, c.status)
            assertTrue(c.detail, c.detail.startsWith("Not established"))
        }
        assertTrue(r.checks.first { it.id == "boot" }.detail.startsWith("Not established"))
    }

    @Test
    fun `a broken chain fails - the intermediate swapped for another chain's`() {
        val a = TestPki.chain()
        val b = TestPki.chain()
        val json = Json.parseToJsonElement(signed(a)).jsonObject
        val chain = a.derChain.toMutableList().also { it[1] = b.derChain[1] }
        val broken = JsonObject(json + ("certificateChain" to kotlinx.serialization.json.JsonArray(chain.map { JsonPrimitive(Base64.getEncoder().encodeToString(it)) })))
        val r = verify(broken.toString(), a)
        assertFalse(r.verified)
        assertEquals(CheckStatus.FAIL, r.status("chain"))
    }

    @Test
    fun `a signature by a different key fails even with a valid chain`() {
        val chain = TestPki.chain()
        val other = TestPki.chain()
        val sig = EvidenceSignatures.encode(
            EvidenceSignatures.create(bundle, other.sign(bundle), chain.derChain, "2026-10-11T08:00:05Z", "1.1.0", SecurityLevel.STRONGBOX, "x.json"),
        )
        val r = verify(sig, chain)
        assertEquals(CheckStatus.FAIL, r.status("signature"))
        assertFalse(r.verified)
    }

    @Test
    fun `a software key is not hardware evidence`() {
        val chain = TestPki.chain(TestAttestation(securityLevel = 0, keyMintSecurityLevel = 0))
        val r = verify(signed(chain, level = SecurityLevel.SOFTWARE), chain)
        assertEquals(CheckStatus.FAIL, r.status("hardware"))
        assertFalse(r.verified)
    }

    @Test
    fun `TEE keys pass and are named TEE`() {
        val chain = TestPki.chain(TestAttestation(securityLevel = 1, keyMintSecurityLevel = 1))
        val r = verify(signed(chain, level = SecurityLevel.TRUSTED_ENVIRONMENT), chain)
        assertTrue(r.checks.joinToString("\n"), r.verified)
        assertTrue(r.checks.first { it.id == "hardware" }.detail.contains("TEE"))
    }

    @Test
    fun `an imported key, or one with no hardware-enforced origin, fails the generated-inside check`() {
        for (origin in listOf(2, null)) {
            val chain = TestPki.chain(TestAttestation(origin = origin))
            val r = verify(signed(chain), chain)
            assertEquals("origin=$origin", CheckStatus.FAIL, r.status("origin"))
            assertFalse(r.verified)
        }
    }

    @Test
    fun `a key made for another app fails the app check`() {
        val chain = TestPki.chain(TestAttestation(packages = listOf("com.example.other" to 3L)))
        val r = verify(signed(chain), chain)
        assertEquals(CheckStatus.FAIL, r.status("app"))
        assertTrue(r.checks.first { it.id == "app" }.detail.contains("com.example.other"))
        assertFalse(r.verified)
        // ...and passes when that app is the one expected.
        assertTrue(verify(signed(chain), chain, pkg = "com.example.other").verified)
    }

    @Test
    fun `an expected signing certificate is enforced when given`() {
        val chain = TestPki.chain()
        val real = ByteArray(32) { (it + 1).toByte() }.joinToString("") { "%02x".format(it) }
        assertTrue(verify(signed(chain), chain, expectedSigner = real.uppercase()).verified)
        val r = verify(signed(chain), chain, expectedSigner = "ab".repeat(32))
        assertEquals(CheckStatus.FAIL, r.status("app"))
    }

    @Test
    fun `the application id is also read from the hardware-enforced list`() {
        val chain = TestPki.chain(TestAttestation(appIdInHardwareList = true))
        assertTrue(verify(signed(chain), chain).verified)
    }

    @Test
    fun `no attestation extension at all fails`() {
        val chain = TestPki.chain(attestation = null)
        val r = verify(signed(chain), chain)
        assertEquals(CheckStatus.FAIL, r.status("attestation"))
        assertNull(r.keyDescription)
        assertFalse(r.verified)
    }

    @Test
    fun `a label in the file that disagrees with the attestation is a warning, and the attestation wins`() {
        val chain = TestPki.chain(TestAttestation(securityLevel = 1, keyMintSecurityLevel = 1))
        val r = verify(signed(chain, level = SecurityLevel.STRONGBOX), chain)
        assertEquals(CheckStatus.WARN, r.status("claim"))
        assertTrue(r.verified)
        assertEquals(SecurityLevel.TRUSTED_ENVIRONMENT, r.keyDescription!!.attestationSecurityLevel)
    }

    @Test
    fun `an unspecific secure-hardware label (Android 10 and 11) is not a disagreement`() {
        val chain = TestPki.chain()
        val r = verify(signed(chain, level = SecurityLevel.UNKNOWN_SECURE), chain)
        assertNull(r.status("claim"))
        assertTrue(r.verified)
    }

    @Test
    fun `boot state and challenge are reported as information, never as a pass`() {
        val chain = TestPki.chain(TestAttestation(deviceLocked = false, verifiedBootState = 2))
        val r = verify(signed(chain), chain)
        assertEquals(CheckStatus.INFO, r.status("boot"))
        assertTrue(r.checks.first { it.id == "boot" }.detail.contains("unlocked"))
        assertEquals(CheckStatus.INFO, r.status("challenge"))
        assertTrue(r.checks.first { it.id == "challenge" }.detail.contains("traceveil-evidence-v1:"))
    }

    @Test
    fun `certificate dates outside the stated signing time give a warning, not a pass`() {
        val chain = TestPki.chain()
        val r = verify(signed(chain, createdAt = "2040-01-01T00:00:00Z"), chain)
        assertEquals(CheckStatus.WARN, r.status("dates"))
        assertTrue(r.verified)
    }

    @Test
    fun `unreadable, wrong-schema and wrong-algorithm signature files fail at the first check`() {
        val chain = TestPki.chain()
        val good = Json.parseToJsonElement(signed(chain)).jsonObject
        val cases = listOf(
            "not json",
            "[]",
            JsonObject(good + ("schema" to JsonPrimitive("traceveil.audit.v1"))).toString(),
            JsonObject(good + ("algorithm" to JsonPrimitive("HS256"))).toString(),
            JsonObject(good + ("signature" to JsonPrimitive("%%%"))).toString(),
            JsonObject(good + ("certificateChain" to kotlinx.serialization.json.JsonArray(emptyList()))).toString(),
            JsonObject(good + ("certificateChain" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("AAAA"))))).toString(),
        )
        for (c in cases) {
            val r = verify(c, chain)
            assertFalse(c, r.verified)
            assertTrue(c + "\n" + r.checks, r.checks.any { it.status == CheckStatus.FAIL && it.id in setOf("file", "certificates", "signature") })
        }
    }
}
