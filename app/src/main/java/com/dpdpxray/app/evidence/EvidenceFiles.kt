package com.dpdpxray.app.evidence

import com.dpdpxray.core.evidence.EvidenceSignatures
import com.dpdpxray.core.evidence.SecurityLevel
import com.dpdpxray.core.export.AuditBundles
import java.io.File

/** What the phone's evidence key returned for one signature: the ECDSA signature, its attestation chain (leaf first) and level. */
class HardwareSignature(val signature: ByteArray, val chain: List<ByteArray>, val level: SecurityLevel)

/** Signs bytes with this install's evidence key ([AndroidEvidenceKey] on the phone; a test key in unit tests). */
fun interface EvidenceKey {
    fun sign(bytes: ByteArray): HardwareSignature
}

/** The outcome of signing one export, with the one line the export screen shows. */
sealed interface EvidenceSigning {
    val line: String

    data class Signed(val file: File, val level: SecurityLevel) : EvidenceSigning {
        override val line: String get() = EvidenceSignatures.exportLine(level)
    }

    data class Unavailable(val reason: String) : EvidenceSigning {
        override val line: String get() = EvidenceSignatures.UNAVAILABLE
    }
}

/**
 * Writes a traceveil.signature.v1 file next to an export. Signing never blocks an export: any failure (no secure
 * hardware, no attestation, a Keystore error) gives [EvidenceSigning.Unavailable] and no signature file.
 */
object EvidenceFiles {
    fun signatureFileFor(file: File): File = File(file.absoluteFile.parentFile, EvidenceSignatures.fileNameFor(file.name))

    fun signNextTo(file: File, key: EvidenceKey, versionName: String, createdAt: String = AuditBundles.now()): EvidenceSigning {
        val target = signatureFileFor(file)
        // A signature left from an earlier export of the same name must never sit next to new bytes.
        target.delete()
        return try {
            val bytes = file.readBytes()
            val (text, level) = build(bytes, file.name, key, versionName, createdAt)
            target.writeText(text, Charsets.UTF_8)
            EvidenceSigning.Signed(target, level)
        } catch (e: Exception) {
            target.delete()
            EvidenceSigning.Unavailable(e.message ?: e.javaClass.simpleName)
        }
    }

    /** The signature file's text for [bytes] (the Runner writes it next to bundle.json), or null when signing is unavailable. */
    fun signatureText(bytes: ByteArray, signedFile: String, key: EvidenceKey, versionName: String, createdAt: String = AuditBundles.now()): String? =
        try {
            build(bytes, signedFile, key, versionName, createdAt).first
        } catch (e: Exception) {
            null
        }

    private fun build(bytes: ByteArray, signedFile: String, key: EvidenceKey, versionName: String, createdAt: String): Pair<String, SecurityLevel> {
        val s = key.sign(bytes)
        check(s.level.isHardware) { "the key is not in secure hardware (${s.level.wire})" }
        check(s.chain.size >= 2) { "Android gave no attestation chain for the key" }
        return EvidenceSignatures.encode(EvidenceSignatures.create(bytes, s.signature, s.chain, createdAt, versionName, s.level, signedFile)) to s.level
    }
}

/** Maps what Android's KeyInfo reports to the level names in traceveil.signature.v1. */
object KeystoreLevels {
    /** KeyInfo.getSecurityLevel() (API 31+): KeyProperties.SECURITY_LEVEL_* values. */
    fun fromKeyInfo(level: Int): SecurityLevel = when (level) {
        2 -> SecurityLevel.STRONGBOX
        1 -> SecurityLevel.TRUSTED_ENVIRONMENT
        0 -> SecurityLevel.SOFTWARE
        -1 -> SecurityLevel.UNKNOWN_SECURE
        else -> SecurityLevel.UNKNOWN
    }

    /** API 29-30 only say whether the key is inside secure hardware, not which kind. */
    fun legacy(insideSecureHardware: Boolean): SecurityLevel = if (insideSecureHardware) SecurityLevel.UNKNOWN_SECURE else SecurityLevel.SOFTWARE
}
