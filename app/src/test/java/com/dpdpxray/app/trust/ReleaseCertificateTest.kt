package com.dpdpxray.app.trust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * The trust screen on a release-signed install: TraceVeil's own release certificate (public part only, exported from the
 * release keystore; docs/RELEASE.md) goes through the same mapping as Android's signer bytes.
 */
class ReleaseCertificateTest {
    private val cert: X509Certificate = javaClass.getResourceAsStream("/trust/traceveil-release-cert.crt")!!.use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }

    @Test
    fun `a release-signed install shows the published certificate digest, as apksigner prints it`() {
        val signer = TrustReader.signer(cert.encoded)
        assertEquals("c927b0797bacf173366ed1f6456dc5eaf09adceae92ffa4ba2cdf8da4bedce21", signer.sha256)
    }

    @Test
    fun `a release-signed install gets no shared-debug-key warning`() {
        val signer = TrustReader.signer(cert.encoded)
        assertTrue(signer.subject, signer.subject!!.contains("CN=TraceVeil"))
        assertTrue(signer.subject, signer.subject!!.contains("O=Team Kernel Panic"))
        assertFalse(TrustText.isDebugCertificate(signer.subject))
    }

    @Test
    fun `bytes that are not a certificate still give a digest, with no subject and no debug note`() {
        val signer = TrustReader.signer(byteArrayOf(1, 2, 3))
        assertEquals(64, signer.sha256.length)
        assertEquals(null, signer.subject)
        assertFalse(TrustText.isDebugCertificate(signer.subject))
    }
}
