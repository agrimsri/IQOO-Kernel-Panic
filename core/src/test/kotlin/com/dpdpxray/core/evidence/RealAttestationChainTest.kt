package com.dpdpxray.core.evidence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.cert.X509Certificate

/*
 * Real Android Key Attestation chains from Google's android/keyattestation test data (Apache-2.0; source, commit and
 * licence in src/test/resources/evidence/keyattestation/SOURCE.txt). The expected values are the ones upstream decoded
 * into the .json file next to each chain.
 */
class RealAttestationChainTest {
    private fun chain(name: String): List<X509Certificate> =
        TestPki.readPem(javaClass.getResource("/evidence/keyattestation/$name.pem")!!.readText())

    private val googleRoots = GoogleAttestationRoots.all

    private fun statusOf(v: ChainVerification, id: String) = v.checks.firstOrNull { it.id == id }?.status

    @Test
    fun `Google's published root list holds exactly the two root keys from the attestation docs`() {
        // SHA-256 of each root's SubjectPublicKeyInfo, computed with openssl from https://android.googleapis.com/attestation/root
        // on 11 October 2026 and matching the certificates printed on the Android key attestation page.
        assertEquals(
            setOf(
                "feb2ea7551ee316ed4bb443c8293b884dbfdea40b603ee3e4f4a897e4580fbae", // RSA-4096, serialNumber=f92009e853b6b045
                "3ee44512a1af2beb39c889490c60ea3f82e43f5d5a5532f5ab9419f676cd07ec", // ECDSA P-384, CN=Key Attestation CA1
            ),
            googleRoots.map { it.publicKeySha256 }.toSet(),
        )
    }

    @Test
    fun `a Pixel 9 Pro StrongBox chain verifies to the Google RSA root and decodes like upstream`() {
        val v = AttestationChains.verify(chain("caiman_sdk36_SB_EC_RKP"), googleRoots)
        assertTrue(v.checks.joinToString("\n"), v.ok)
        assertEquals(CheckStatus.PASS, statusOf(v, "chain"))
        assertEquals(CheckStatus.PASS, statusOf(v, "root"))
        assertEquals(CheckStatus.PASS, statusOf(v, "hardware"))
        assertEquals(CheckStatus.PASS, statusOf(v, "origin"))
        assertEquals("feb2ea7551ee316ed4bb443c8293b884dbfdea40b603ee3e4f4a897e4580fbae", v.root!!.publicKeySha256)
        val kd = v.keyDescription!!
        assertEquals(300L, kd.attestationVersion)
        assertEquals(SecurityLevel.STRONGBOX, kd.attestationSecurityLevel)
        assertEquals(SecurityLevel.STRONGBOX, kd.keyMintSecurityLevel)
        assertEquals("7ccac1ea-4845-482e-858d-f6fa9aa8c295", kd.challenge.toString(Charsets.US_ASCII))
        val app = kd.applicationId!!
        assertEquals(listOf(AttestedPackage("com.google.android.attestation", 0)), app.packages)
        assertEquals(listOf("103938ee4537e59e8ee792f654504fb8346fc6b346d0bbc4415fc339fcfc8ec1"), app.signatureDigestsHex)
        assertEquals(KeyOrigin.GENERATED, kd.hardwareEnforced.origin)
        assertEquals(listOf(2L, 3L), kd.hardwareEnforced.purposes)
        assertEquals(1758900646327L, kd.softwareEnforced.creationDateTime)
        val rot = kd.hardwareEnforced.rootOfTrust!!
        assertTrue(rot.deviceLocked)
        assertEquals(VerifiedBootState.VERIFIED, rot.verifiedBootState)
        assertEquals(160000L, kd.hardwareEnforced.osVersion)
        assertEquals(202511L, kd.hardwareEnforced.osPatchLevel)
    }

    @Test
    fun `a Pixel 9a TEE chain verifies to the new Key Attestation CA1 root`() {
        val v = AttestationChains.verify(chain("tegu_sdk36_TEE_EC_2026_ROOT"), googleRoots)
        assertTrue(v.checks.joinToString("\n"), v.ok)
        assertEquals("3ee44512a1af2beb39c889490c60ea3f82e43f5d5a5532f5ab9419f676cd07ec", v.root!!.publicKeySha256)
        val kd = v.keyDescription!!
        assertEquals(400L, kd.attestationVersion)
        assertEquals(SecurityLevel.TRUSTED_ENVIRONMENT, kd.attestationSecurityLevel)
        assertEquals("6417f92c-daef-4cc1-8828-5bb39338ffd5", kd.challenge.toString(Charsets.US_ASCII))
        assertEquals(202602L, kd.hardwareEnforced.osPatchLevel)
    }

    @Test
    fun `a 2018 Pixel 3 factory-key chain verifies to the 2016 issue of the RSA root (same key)`() {
        val certs = chain("blueline_sdk28_TEE_EC_NONE")
        val v = AttestationChains.verify(certs, googleRoots)
        assertTrue(v.checks.joinToString("\n"), v.ok)
        // The chain carries the 2016 certificate; trust rests on its public key, which the current list still publishes.
        assertFalse(googleRoots.any { it.certificate == certs.last() })
        val kd = v.keyDescription!!
        assertEquals(3L, kd.attestationVersion)
        assertEquals(4L, kd.keyMintVersion)
        assertEquals("challenge", kd.challenge.toString(Charsets.US_ASCII))
        assertEquals("com.google.wireless.android.security.attestationverifier.collector", kd.applicationId!!.packages.single().name)
        val rot = kd.hardwareEnforced.rootOfTrust!!
        assertFalse(rot.deviceLocked)
        assertEquals(VerifiedBootState.UNVERIFIED, rot.verifiedBootState)
    }

    @Test
    fun `dropping an intermediate breaks the chain`() {
        val certs = chain("caiman_sdk36_SB_EC_RKP")
        val v = AttestationChains.verify(certs.filterIndexed { i, _ -> i != 2 }, googleRoots)
        assertFalse(v.ok)
        val link = v.checks.first { it.id == "chain" }
        assertEquals(CheckStatus.FAIL, link.status)
        assertTrue(link.detail, link.detail.contains("certificate 2") && link.detail.contains("certificate 3"))
    }

    @Test
    fun `a leaf from one phone under another phone's intermediates fails the chain`() {
        val mixed = listOf(chain("tegu_sdk36_TEE_EC_2026_ROOT").first()) + chain("caiman_sdk36_SB_EC_RKP").drop(1)
        val v = AttestationChains.verify(mixed, googleRoots)
        assertEquals(CheckStatus.FAIL, statusOf(v, "chain"))
        assertFalse(v.ok)
    }

    @Test
    fun `a real chain checked against a root list without Google's keys fails at the root`() {
        val v = AttestationChains.verify(chain("caiman_sdk36_SB_EC_RKP"), listOf(TestPki.chain().trustedRoot))
        assertEquals(CheckStatus.PASS, statusOf(v, "chain"))
        assertEquals(CheckStatus.FAIL, statusOf(v, "root"))
        assertNull(v.root)
        assertFalse(v.ok)
    }

    @Test
    fun `a certificate appended under the attested leaf is refused - only the first extension from the root counts`() {
        // Anyone able to use an attested signing key could sign a new certificate under it with any claims they like.
        // The phone's key is not available here, so the same shape is built with a test chain: an extra leaf, with
        // its own (forged) extension, signed by the attested key.
        val t = TestPki.chain()
        val extraKey = TestPki.ecKeyPair()
        val extra = TestPki.certificate(
            "Extra", extraKey.public, "Android Keystore Key", t.leafKey.private, ca = false,
            attestation = TestPki.keyDescription(TestAttestation()),
        )
        val v = AttestationChains.verify(listOf(extra) + t.certificates, listOf(t.trustedRoot))
        assertEquals(CheckStatus.PASS, statusOf(v, "chain"))
        val att = v.checks.first { it.id == "attestation" }
        assertEquals(CheckStatus.FAIL, att.status)
        assertTrue(att.detail, att.detail.contains("not the attested key"))
        assertFalse(v.ok)
    }

    @Test
    fun `root key fingerprints are SHA-256 of the SubjectPublicKeyInfo`() {
        val root = googleRoots.first()
        val expected = MessageDigest.getInstance("SHA-256").digest(root.certificate.publicKey.encoded).joinToString("") { "%02x".format(it) }
        assertEquals(expected, root.publicKeySha256)
    }
}
