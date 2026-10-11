package com.dpdpxray.core.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignerCheckTest {
    private val a = "AB".repeat(32)
    private val b = "CD".repeat(32)
    private val c = "EF".repeat(32)
    private fun twin(vararg certs: String, version: Long? = 10) = InstalledTwin("WhatsApp", certs.toList(), versionCode = version, versionName = "2.1")

    @Test fun `no installed app with the same package is a fresh install`() {
        assertEquals(InstallRelation.NEW_INSTALL, SignerCheck.relation(listOf(a), emptyList(), null))
    }

    @Test fun `the same certificate is an update by the same signer, whatever the case of the hex`() {
        assertEquals(InstallRelation.SAME_SIGNER, SignerCheck.relation(listOf(a.lowercase()), emptyList(), twin(a)))
        assertEquals(InstallRelation.SAME_SIGNER, SignerCheck.relation(listOf(a, b), emptyList(), twin(b, a)))
    }

    @Test fun `a different certificate is the big red flag`() {
        assertEquals(InstallRelation.DIFFERENT_SIGNER, SignerCheck.relation(listOf(b), emptyList(), twin(a)))
        // One shared signer out of two is still a different signer set: Android compares the whole set.
        assertEquals(InstallRelation.DIFFERENT_SIGNER, SignerCheck.relation(listOf(a, b), emptyList(), twin(a)))
        assertTrue(SignerCheck.isMismatch(InstallRelation.DIFFERENT_SIGNER))
        assertFalse(SignerCheck.isMismatch(InstallRelation.SAME_SIGNER))
    }

    @Test fun `a rotated key whose history holds the installed key is an update, not a mismatch`() {
        // APK Signature Scheme v3: the file is signed by b, and its lineage says a handed over to b.
        assertEquals(InstallRelation.ROTATED_SIGNER, SignerCheck.relation(listOf(b), listOf(a, b), twin(a)))
        // A history that does not hold the installed key does not help.
        assertEquals(InstallRelation.DIFFERENT_SIGNER, SignerCheck.relation(listOf(b), listOf(c, b), twin(a)))
        // Key rotation is single-signer only.
        assertEquals(InstallRelation.DIFFERENT_SIGNER, SignerCheck.relation(listOf(b, c), listOf(a, b), twin(a)))
    }

    @Test fun `an unreadable certificate on either side is not established, never a match or mismatch`() {
        assertEquals(InstallRelation.SIGNER_UNKNOWN, SignerCheck.relation(emptyList(), emptyList(), twin(a)))
        assertEquals(InstallRelation.SIGNER_UNKNOWN, SignerCheck.relation(listOf(a), emptyList(), twin()))
        assertEquals(InstallRelation.SIGNER_UNKNOWN, SignerCheck.relation(listOf(" "), emptyList(), twin(a)))
        assertFalse(SignerCheck.isMismatch(InstallRelation.SIGNER_UNKNOWN))
    }

    @Test fun `an older version than the installed app is a downgrade, unknown stays unknown`() {
        assertEquals(true, SignerCheck.isDowngrade(9, twin(a, version = 10)))
        assertEquals(false, SignerCheck.isDowngrade(10, twin(a, version = 10)))
        assertEquals(false, SignerCheck.isDowngrade(11, twin(a, version = 10)))
        assertNull(SignerCheck.isDowngrade(null, twin(a, version = 10)))
        assertNull(SignerCheck.isDowngrade(9, twin(a, version = null)))
        assertNull(SignerCheck.isDowngrade(9, null))
    }

    @Test fun `APK Guard's same-package signal follows the detector`() {
        fun facts(certs: List<String>, history: List<String>, twin: InstalledTwin?) = ApkFacts(
            packageName = "com.whatsapp", label = "WhatsApp", versionName = "2.2", versionCode = 11, targetSdk = 35,
            permissions = emptySet(), origin = InstallOrigin(InstallSource.NOT_INSTALLED), certSha256 = certs,
            certHistorySha256 = history, installedTwin = twin,
        )
        fun signal(f: ApkFacts) = ApkRiskRules.evaluate(f).signals.single { it.id == "same_package_installed" }
        assertEquals(ApkSeverity.REVIEW_CAREFULLY, signal(facts(listOf(b), emptyList(), twin(a))).severity)
        val rotated = signal(facts(listOf(b), listOf(a, b), twin(a)))
        assertEquals(ApkSeverity.INFO, rotated.severity)
        assertEquals(ApkEvidence.OBSERVED, rotated.evidence)
        assertTrue(rotated.title.en, rotated.title.en.contains("newer signing key"))
        assertTrue(ApkRiskRules.evaluate(facts(listOf(a), emptyList(), null)).signals.none { it.id == "same_package_installed" })
    }
}
