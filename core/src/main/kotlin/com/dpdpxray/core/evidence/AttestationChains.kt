package com.dpdpxray.core.evidence

import java.security.cert.X509Certificate

enum class CheckStatus { PASS, FAIL, WARN, INFO }

/** One line of a verification: [id] is stable (tests, JSON), [title] and [detail] are plain words for people. */
data class EvidenceCheck(val id: String, val status: CheckStatus, val title: String, val detail: String) {
    override fun toString() = "$status $id: $title - $detail"
}

/**
 * The chain part of a verification. [keyDescription] describes the leaf (signing) key, and is null unless it does.
 * [trusted]: every link verified and the root is a trusted key; only then do the attestation's claims count.
 */
class ChainVerification(val checks: List<EvidenceCheck>, val keyDescription: KeyDescription?, val root: TrustedRoot?, val trusted: Boolean = false) {
    val ok: Boolean get() = checks.isNotEmpty() && checks.none { it.status == CheckStatus.FAIL }
}

/** What an untrusted chain says about itself: shown as a warning, never as a pass. */
internal const val NOT_ESTABLISHED = "Not established (the chain is not trusted, so this is only what the certificate claims): "

/**
 * Checks an Android Key Attestation chain (leaf first) the way Google's key attestation page describes: each
 * certificate is signed by the next, the last one carries a trusted root public key, and the attestation extension
 * nearest the root is read. That extension must be on the leaf, the key that signs: an extension further down the chain
 * would have been written by whoever holds the attested key, not by the secure hardware.
 */
object AttestationChains {
    fun verify(chain: List<X509Certificate>, roots: List<TrustedRoot>): ChainVerification {
        val checks = mutableListOf<EvidenceCheck>()
        if (chain.size < 2) {
            checks += EvidenceCheck(
                "chain", CheckStatus.FAIL, "Certificate chain",
                "The chain has ${chain.size} certificate(s); an attestation chain has at least the key's certificate and a root.",
            )
            return ChainVerification(checks, null, null)
        }

        val broken = (0 until chain.size - 1).firstOrNull { i -> !signedBy(chain[i], chain[i + 1]) }
        checks += if (broken == null) {
            EvidenceCheck("chain", CheckStatus.PASS, "Certificate chain", "${chain.size} certificates, each signed by the next (key certificate first, root last).")
        } else {
            EvidenceCheck(
                "chain", CheckStatus.FAIL, "Certificate chain",
                "certificate ${broken + 1} (${subject(chain[broken])}) is not signed by certificate ${broken + 2} " +
                    "(${subject(chain[broken + 1])}), so the chain is broken.",
            )
        }

        val last = chain.last()
        val root = roots.firstOrNull { it.matches(last) }
        val trusted = broken == null && root != null
        fun claim(check: EvidenceCheck) =
            if (trusted || check.status != CheckStatus.PASS) check else check.copy(status = CheckStatus.WARN, detail = "$NOT_ESTABLISHED${check.detail}")
        checks += if (root != null) {
            EvidenceCheck("root", CheckStatus.PASS, "Root", "Ends at ${root.name}, public key SHA-256 ${root.publicKeySha256}.")
        } else {
            EvidenceCheck(
                "root", CheckStatus.FAIL, "Root",
                "The chain ends at ${subject(last)}, which is not a Google hardware attestation root. Google makes no claim " +
                    "about such keys: this is what Android's software attestation, or a self-made chain, looks like.",
            )
        }

        val nearestRoot = chain.indices.reversed().firstOrNull { chain[it].getExtensionValue(KeyDescription.OID) != null }
        if (nearestRoot == null) {
            checks += EvidenceCheck(
                "attestation", CheckStatus.FAIL, "Key attestation",
                "No certificate carries Android's key attestation extension (${KeyDescription.OID}), so nothing says where the key lives.",
            )
            return ChainVerification(checks, null, root)
        }
        if (nearestRoot != 0) {
            checks += EvidenceCheck(
                "attestation", CheckStatus.FAIL, "Key attestation",
                "The attestation is on certificate ${nearestRoot + 1}, not on the signing key's own certificate (certificate 1), " +
                    "so the signing key is not the attested key.",
            )
            return ChainVerification(checks, null, root)
        }
        val kd = try {
            KeyDescription.of(chain[0])!!
        } catch (e: Exception) {
            checks += EvidenceCheck("attestation", CheckStatus.FAIL, "Key attestation", "The attestation extension could not be read: ${e.message ?: e.javaClass.simpleName}.")
            return ChainVerification(checks, null, root)
        }
        checks += EvidenceCheck(
            "attestation", CheckStatus.PASS, "Key attestation",
            "The signing key's own certificate carries the attestation (attestation version ${kd.attestationVersion}, KeyMint version ${kd.keyMintVersion}).",
        )

        val level = kd.attestationSecurityLevel
        checks += claim(when {
            level.isHardware && kd.keyMintSecurityLevel == level -> EvidenceCheck(
                "hardware", CheckStatus.PASS, "Secure hardware",
                "The key is in the phone's ${level.label} (attestation and KeyMint security level ${level.wire}).",
            )
            level.isHardware -> EvidenceCheck(
                "hardware", CheckStatus.FAIL, "Secure hardware",
                "The attestation comes from ${level.wire} but says the key itself is ${kd.keyMintSecurityLevel.wire}.",
            )
            else -> EvidenceCheck(
                "hardware", CheckStatus.FAIL, "Secure hardware",
                "The attestation says security level ${level.wire}: the key is not in secure hardware.",
            )
        })

        val origin = kd.hardwareEnforced.origin
        checks += claim(if (origin == KeyOrigin.GENERATED) {
            EvidenceCheck("origin", CheckStatus.PASS, "Key origin", "The key was ${origin.label} (origin GENERATED, in the hardware-enforced list).")
        } else {
            EvidenceCheck(
                "origin", CheckStatus.FAIL, "Key origin",
                if (origin == null) "The hardware-enforced list does not say the key was generated inside the secure hardware."
                else "The key was ${origin.label} (origin ${origin.name}), not generated inside the secure hardware.",
            )
        })
        return ChainVerification(checks, kd, root, trusted)
    }

    private fun signedBy(cert: X509Certificate, issuer: X509Certificate): Boolean =
        try {
            cert.verify(issuer.publicKey)
            true
        } catch (e: Exception) {
            false
        }

    internal fun subject(cert: X509Certificate): String = cert.subjectX500Principal.name.ifBlank { "no subject" }
}
