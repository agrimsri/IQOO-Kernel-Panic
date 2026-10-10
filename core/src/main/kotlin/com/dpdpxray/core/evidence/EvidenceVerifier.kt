package com.dpdpxray.core.evidence

import com.dpdpxray.core.automation.AutomationContract
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.time.Instant
import java.util.Base64
import java.util.Date

/** Everything a verification found. [verified] is true only when no check failed. */
class EvidenceReport(
    val checks: List<EvidenceCheck>,
    val keyDescription: KeyDescription?,
    val root: TrustedRoot?,
    val signatureFile: EvidenceSignatureV1?,
    val limits: List<String> = EvidenceVerifier.LIMITS,
) {
    val verified: Boolean get() = checks.isNotEmpty() && checks.none { it.status == CheckStatus.FAIL }
}

/**
 * Verifies a traceveil.signature.v1 file against the exact bytes of the file it signs, offline:
 *  (a) the bytes are unchanged (SHA-256, and the ECDSA signature by the leaf key);
 *  (b) the leaf key was generated inside Android secure hardware, and at which level (key attestation, Google root);
 *  (c) which app asked for the key (attestationApplicationId: package and signing-certificate digests);
 *  (d) the chain is unbroken and ends at a Google hardware attestation root.
 * What it cannot check is listed in [LIMITS] and printed with every result.
 */
object EvidenceVerifier {
    val LIMITS: List<String> = listOf(
        "Revocation is not checked: Google lists revoked attestation keys online (${GoogleAttestationRoots.REVOCATION_URL}), " +
            "and this offline check does not read it.",
        "Attestation shows where the signing key lives and which app asked for it. It does not prove that the phone's " +
            "Android was uncompromised or that what the audit recorded is true; it proves these exact bytes were signed by that key.",
        "The app name and signing-certificate digest are filled in by Android's keystore service; the secure hardware " +
            "vouches for the key, its origin and the boot state when the key was created.",
        "createdAt, appVersion and the security label in the signature file are written by the app and are not covered by the signature.",
    )

    fun verify(
        bundle: ByteArray,
        signatureFile: String,
        roots: List<TrustedRoot> = GoogleAttestationRoots.all,
        expectedPackage: String = AutomationContract.APP_PACKAGE,
        expectedSignerSha256: String? = null,
    ): EvidenceReport {
        val checks = mutableListOf<EvidenceCheck>()

        // ---- the file itself ------------------------------------------------------------------------------------
        val sig = try {
            EvidenceSignatures.decode(signatureFile)
        } catch (e: SignatureFormatException) {
            checks += fail("file", "Signature file", "The signature file is ${e.message}.")
            return EvidenceReport(checks, null, null, null)
        }
        val problem = when {
            sig.schema != EvidenceSignatures.SCHEMA -> "it is \"${sig.schema}\", not ${EvidenceSignatures.SCHEMA}"
            sig.algorithm != EvidenceSignatures.ALGORITHM -> "its algorithm is \"${sig.algorithm}\"; this version verifies only ${EvidenceSignatures.ALGORITHM} (ECDSA P-256, SHA-256)"
            sig.certificateChain.isEmpty() -> "it has no certificate chain"
            else -> null
        }
        val signatureBytes = runCatching { Base64.getDecoder().decode(sig.signature) }.getOrNull()
        if (problem != null || signatureBytes == null) {
            checks += fail("file", "Signature file", "The signature file cannot be used: ${problem ?: "its signature is not valid base64"}.")
            return EvidenceReport(checks, null, null, sig)
        }
        checks += EvidenceCheck(
            "file", CheckStatus.PASS, "Signature file",
            "${sig.schema}, ${sig.algorithm}, written by TraceVeil ${sig.appVersion} at ${sig.createdAt}" +
                (sig.signedFile?.let { " for $it" } ?: "") + ".",
        )

        // ---- (a) the bytes ----------------------------------------------------------------------------------------
        val actual = EvidenceSignatures.sha256Hex(bundle)
        checks += if (actual.equals(sig.sha256.trim(), ignoreCase = true)) {
            EvidenceCheck("hash", CheckStatus.PASS, "Bundle unchanged", "SHA-256 of the file matches the signed hash ($actual).")
        } else {
            fail(
                "hash", "Bundle unchanged",
                "The file's SHA-256 is $actual, but the signature was made for ${sig.sha256}: the file was changed after signing, or it is a different file.",
            )
        }

        val factory = CertificateFactory.getInstance("X.509")
        val certs = mutableListOf<X509Certificate>()
        for ((i, b64) in sig.certificateChain.withIndex()) {
            val cert = runCatching { factory.generateCertificate(Base64.getDecoder().decode(b64).inputStream()) as X509Certificate }.getOrNull()
            if (cert == null) {
                checks += fail("certificates", "Certificates", "Certificate ${i + 1} of ${sig.certificateChain.size} could not be read.")
                return EvidenceReport(checks, null, null, sig)
            }
            certs += cert
        }

        checks += signatureCheck(bundle, signatureBytes, certs.first())

        // ---- (b), (d) the chain and its attestation ------------------------------------------------------------------
        val chain = AttestationChains.verify(certs, roots)
        checks += chain.checks
        val kd = chain.keyDescription ?: return EvidenceReport(checks, null, chain.root, sig)

        // ---- (c) the app that asked for the key ---------------------------------------------------------------------
        checks += appCheck(kd, expectedPackage, expectedSignerSha256)

        val claimed = SecurityLevel.parse(sig.securityLevel)
        val agrees = claimed == kd.attestationSecurityLevel || (claimed == SecurityLevel.UNKNOWN_SECURE && kd.attestationSecurityLevel.isHardware)
        if (!agrees) {
            checks += EvidenceCheck(
                "claim", CheckStatus.WARN, "Security label",
                "The signature file says \"${sig.securityLevel}\" but the attestation says ${kd.attestationSecurityLevel.wire}; the attestation is what counts.",
            )
        }
        checks += datesCheck(certs, sig.createdAt)
        checks += EvidenceCheck("challenge", CheckStatus.INFO, "Attestation challenge", "${printable(kd.challenge)} (identifies this install's key; not a freshness check).")
        checks += bootCheck(kd)
        return EvidenceReport(checks, kd, chain.root, sig)
    }

    private fun signatureCheck(bundle: ByteArray, signature: ByteArray, leaf: X509Certificate): EvidenceCheck {
        val key = leaf.publicKey
        if (key !is ECPublicKey || key.params.curve.field.fieldSize != 256) {
            return fail("signature", "Signature", "The signing key is ${key.algorithm}, not an ECDSA P-256 key, so it cannot have made an ES256 signature.")
        }
        val ok = try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(bundle)
                verify(signature)
            }
        } catch (e: Exception) {
            false
        }
        return if (ok) EvidenceCheck("signature", CheckStatus.PASS, "Signature", "Valid ECDSA P-256 / SHA-256 signature over the file's exact bytes, by the key in certificate 1.")
        else fail("signature", "Signature", "The signature does not match this file and the key in certificate 1.")
    }

    private fun appCheck(kd: KeyDescription, expectedPackage: String, expectedSigner: String?): EvidenceCheck {
        val app = kd.applicationId
            ?: return fail("app", "App that created the key", "The attestation names no app (no attestationApplicationId).")
        val names = app.packages.joinToString(", ") { "${it.name} (version code ${it.version})" }.ifEmpty { "no package" }
        val digests = app.signatureDigestsHex.joinToString(", ").ifEmpty { "none" }
        val described = "$names; signing certificate SHA-256: $digests"
        if (app.packages.none { it.name == expectedPackage }) {
            return fail("app", "App that created the key", "The key was created by $described, not by $expectedPackage.")
        }
        val wanted = expectedSigner?.lowercase()?.filter { it.isLetterOrDigit() }
        if (wanted != null && app.signatureDigestsHex.none { it == wanted }) {
            return fail("app", "App that created the key", "$described. None of these is the expected signing certificate $wanted.")
        }
        return EvidenceCheck(
            "app", CheckStatus.PASS, "App that created the key",
            "$described" + (if (wanted != null) " (matches the expected certificate)." else ". Compare with: apksigner verify --print-certs TraceVeil.apk"),
        )
    }

    private fun datesCheck(certs: List<X509Certificate>, createdAt: String): EvidenceCheck {
        val at = runCatching { Instant.parse(createdAt) }.getOrNull()
            ?: return EvidenceCheck("dates", CheckStatus.WARN, "Certificate dates", "Not checked: the signing time \"$createdAt\" is not readable.")
        val outside = certs.withIndex().firstOrNull { (_, c) -> runCatching { c.checkValidity(Date.from(at)) }.isFailure }
        return if (outside == null) {
            EvidenceCheck(
                "dates", CheckStatus.INFO, "Certificate dates",
                "All ${certs.size} certificates were within their validity dates at the stated signing time $at (that time comes from the phone and is not signed).",
            )
        } else {
            val (i, c) = outside
            EvidenceCheck(
                "dates", CheckStatus.WARN, "Certificate dates",
                "Certificate ${i + 1} (${AttestationChains.subject(c)}) was not valid at the stated signing time $at " +
                    "(valid ${c.notBefore.toInstant()} to ${c.notAfter.toInstant()}). Google says expired factory certificates under " +
                    "the RSA root stay trustworthy unless revoked, while short-lived remotely provisioned ones are meant to expire.",
            )
        }
    }

    private fun bootCheck(kd: KeyDescription): EvidenceCheck {
        val hw = kd.hardwareEnforced
        val rot = hw.rootOfTrust
        val parts = listOfNotNull(
            rot?.let { if (it.deviceLocked) "bootloader locked" else "bootloader unlocked" },
            rot?.verifiedBootState?.let { "verified boot ${it.label}" },
            hw.osPatchLevel?.let { "OS patch level ${patch(it)}" },
        )
        val text = if (parts.isEmpty()) "Not reported by the attestation." else parts.joinToString(", ").replaceFirstChar { it.uppercase() } +
            " (reported by the secure hardware when the key was created; it describes the boot, not the running system)."
        return EvidenceCheck("boot", CheckStatus.INFO, "Boot state", text)
    }

    /** 202609 -> "2026-09"; other shapes are shown as they are. */
    private fun patch(v: Long): String = v.toString().let { if (it.length == 6) "${it.take(4)}-${it.drop(4)}" else it }

    private fun printable(b: ByteArray): String =
        if (b.isNotEmpty() && b.all { it in 0x20..0x7E }) "\"${b.toString(Charsets.US_ASCII)}\"" else if (b.isEmpty()) "empty" else "hex ${EvidenceSignatures.hex(b)}"

    private fun fail(id: String, title: String, detail: String) = EvidenceCheck(id, CheckStatus.FAIL, title, detail)
}
