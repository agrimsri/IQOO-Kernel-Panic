package com.dpdpxray.app.evidence

import com.dpdpxray.app.trust.TrustText
import com.dpdpxray.core.evidence.EvidenceSignatures
import com.dpdpxray.core.evidence.EvidenceVerifier
import com.dpdpxray.core.evidence.SecurityLevel
import com.dpdpxray.core.evidence.TestAttestation
import com.dpdpxray.core.evidence.TestPki
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Writing the signature next to an export, with a test key standing in for the phone's Keystore. */
class EvidenceFilesTest {
    @get:Rule val temp = TemporaryFolder()

    private fun export(): File = File(temp.newFolder(), "TraceVeil-audit-1.json").apply { writeText("{\"schema\":\"traceveil.audit.v1\"}\n") }

    private fun testKey(chain: TestPki.Chain, level: SecurityLevel = SecurityLevel.STRONGBOX) =
        EvidenceKey { bytes -> HardwareSignature(chain.sign(bytes), chain.derChain, level) }

    @Test
    fun `the signature lands next to the export and verifies against its exact bytes`() {
        val chain = TestPki.chain()
        val file = export()
        val result = EvidenceFiles.signNextTo(file, testKey(chain), "1.1.0", createdAt = "2026-10-11T08:00:05Z")
        assertTrue(result.toString(), result is EvidenceSigning.Signed)
        val sig = (result as EvidenceSigning.Signed).file
        assertEquals("TraceVeil-audit-1.json.sig.json", sig.name)
        assertEquals(file.parentFile, sig.parentFile)
        assertEquals("Signed in this phone's secure hardware (StrongBox) · verify with traceveil-ci verify", result.line)
        val report = EvidenceVerifier.verify(file.readBytes(), sig.readText(), roots = listOf(chain.trustedRoot))
        assertTrue(report.checks.joinToString("\n"), report.verified)
        assertEquals("TraceVeil-audit-1.json", EvidenceSignatures.decode(sig.readText()).signedFile)
    }

    @Test
    fun `a phone that cannot sign still exports, with no signature file and a plain line`() {
        val file = export()
        val stale = EvidenceFiles.signatureFileFor(file).apply { writeText("old") }
        val result = EvidenceFiles.signNextTo(file, { throw IllegalStateException("Attestation is not supported") }, "1.1.0")
        assertTrue(result is EvidenceSigning.Unavailable)
        assertEquals("Signing unavailable on this phone", result.line)
        assertFalse("a stale signature must not sit next to a new export", stale.exists())
        assertTrue(file.exists())
    }

    @Test
    fun `a software key or a missing attestation chain is not written as evidence`() {
        val chain = TestPki.chain(TestAttestation(securityLevel = 0, keyMintSecurityLevel = 0))
        val file = export()
        assertTrue(EvidenceFiles.signNextTo(file, testKey(chain, SecurityLevel.SOFTWARE), "1.1.0") is EvidenceSigning.Unavailable)
        assertFalse(EvidenceFiles.signatureFileFor(file).exists())
        val noChain = EvidenceKey { bytes -> HardwareSignature(chain.sign(bytes), chain.derChain.take(1), SecurityLevel.STRONGBOX) }
        assertTrue(EvidenceFiles.signNextTo(file, noChain, "1.1.0") is EvidenceSigning.Unavailable)
        assertFalse(EvidenceFiles.signatureFileFor(file).exists())
    }

    @Test
    fun `runner bundles get the same signature text for the exact bytes written`() {
        val chain = TestPki.chain()
        val bytes = "{\"schema\":\"traceveil.audit.v1\"}\n".toByteArray()
        val text = EvidenceFiles.signatureText(bytes, "bundle.json", testKey(chain), "1.1.0")!!
        assertTrue(EvidenceVerifier.verify(bytes, text, roots = listOf(chain.trustedRoot)).verified)
        assertEquals(null, EvidenceFiles.signatureText(bytes, "bundle.json", { error("no key") }, "1.1.0"))
    }

    @Test
    fun `Keystore's reported level maps to the names the verifier uses`() {
        // KeyProperties.SECURITY_LEVEL_*: STRONGBOX 2, TRUSTED_ENVIRONMENT 1, SOFTWARE 0, UNKNOWN_SECURE -1, UNKNOWN -2.
        assertEquals(SecurityLevel.STRONGBOX, KeystoreLevels.fromKeyInfo(2))
        assertEquals(SecurityLevel.TRUSTED_ENVIRONMENT, KeystoreLevels.fromKeyInfo(1))
        assertEquals(SecurityLevel.SOFTWARE, KeystoreLevels.fromKeyInfo(0))
        assertEquals(SecurityLevel.UNKNOWN_SECURE, KeystoreLevels.fromKeyInfo(-1))
        assertEquals(SecurityLevel.UNKNOWN, KeystoreLevels.fromKeyInfo(-2))
        assertEquals(SecurityLevel.UNKNOWN_SECURE, KeystoreLevels.legacy(insideSecureHardware = true))
        assertEquals(SecurityLevel.SOFTWARE, KeystoreLevels.legacy(insideSecureHardware = false))
    }

    @Test
    fun `the trust screen describes the evidence key truthfully`() {
        val claim = TrustText.staysOnPhone(false, false).first { it.title == "Signing key for exports" }
        assertTrue(claim.line, claim.line.contains("secure hardware") && claim.line.contains("never leaves"))
        val detail = claim.detail.orEmpty()
        assertTrue(detail, detail.contains(EvidenceSignatures.KEY_ALIAS))
        assertTrue(detail, detail.contains("traceveil-ci verify"))
        assertTrue(detail, detail.contains("does not prove"))
        assertTrue(detail, detail.contains(EvidenceSignatures.UNAVAILABLE))
        assertEquals(AndroidEvidenceKey.ALIAS, EvidenceSignatures.KEY_ALIAS)
    }
}
