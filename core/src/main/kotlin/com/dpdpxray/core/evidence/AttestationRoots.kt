package com.dpdpxray.core.evidence

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** A root a chain may end at. Trust rests on the root's public key, as Google's documentation says to check. */
class TrustedRoot(val name: String, val certificate: X509Certificate) {
    private val keyBytes: ByteArray = certificate.publicKey.encoded

    /** SHA-256 (hex) of the root's SubjectPublicKeyInfo. */
    val publicKeySha256: String = MessageDigest.getInstance("SHA-256").digest(keyBytes).joinToString("") { "%02x".format(it) }

    fun matches(cert: X509Certificate): Boolean = MessageDigest.isEqual(keyBytes, cert.publicKey.encoded)

    override fun toString() = "$name (public key SHA-256 $publicKeySha256)"
}

/*
 * Google Hardware Attestation roots, embedded so verification works offline.
 *
 * Resource evidence/google-attestation-roots.json is, byte for byte, Google's published root list downloaded on
 * 11 October 2026 from https://android.googleapis.com/attestation/root (the "JSON-formatted array" linked from
 * https://developer.android.com/privacy-and-security/security-key-attestation#root_certificate, page last updated
 * 2026-07-09). The same file is roots.json in https://github.com/android/keyattestation. It holds two certificates:
 *
 *   1. RSA-4096, subject serialNumber=f92009e853b6b045, 2022-03-20 to 2042-03-15,
 *      certificate SHA-256 cedb1cb6dc896ae5ec797348bce9286753c2b38ee71ce0fbe34a9a1248800dfc,
 *      public key SHA-256 feb2ea7551ee316ed4bb443c8293b884dbfdea40b603ee3e4f4a897e4580fbae.
 *      Earlier issues of this root (2016, 2019, 2021, listed on the same page) carry the same public key, so chains
 *      that end at any of them match too.
 *   2. ECDSA P-384, subject CN=Key Attestation CA1, OU=Android, O=Google LLC, C=US, 2025-07-17 to 2035-07-15,
 *      certificate SHA-256 6d9db4ce6c5c0b293166d08986e05774a8776ceb525d9e4329520de12ba4bcc0,
 *      public key SHA-256 3ee44512a1af2beb39c889490c60ea3f82e43f5d5a5532f5ab9419f676cd07ec.
 *      Google: it "will begin signing attestation certificate chains on February 1, 2026".
 *
 * RealAttestationChainTest pins both public-key hashes, so an edit to the resource fails the build.
 */
object GoogleAttestationRoots {
    const val SOURCE_URL = "https://android.googleapis.com/attestation/root"
    const val DOCS_URL = "https://developer.android.com/privacy-and-security/security-key-attestation#root_certificate"
    const val REVOCATION_URL = "https://android.googleapis.com/attestation/status"

    val all: List<TrustedRoot> by lazy {
        val text = GoogleAttestationRoots::class.java.getResourceAsStream("/evidence/google-attestation-roots.json")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("missing resource evidence/google-attestation-roots.json")
        val factory = CertificateFactory.getInstance("X.509")
        Json.parseToJsonElement(text).jsonArray.map { pem ->
            val cert = factory.generateCertificate(pem.jsonPrimitive.content.byteInputStream()) as X509Certificate
            val subject = cert.subjectX500Principal.name
            val name = when {
                subject.contains("f92009e853b6b045") -> "Google Hardware Attestation Root (RSA-4096, serialNumber=f92009e853b6b045)"
                subject.contains("Key Attestation CA1") -> "Google Key Attestation CA1 (ECDSA P-384)"
                else -> "Google attestation root $subject"
            }
            TrustedRoot(name, cert)
        }
    }
}
