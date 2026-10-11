package com.dpdpxray.core.evidence

import java.security.cert.X509Certificate

/*
 * Android Key Attestation extension (OID 1.3.6.1.4.1.11129.2.1.17), the part TraceVeil reports. Schema:
 * https://source.android.com/docs/security/features/keystore/attestation#schema
 *
 *   KeyDescription ::= SEQUENCE {
 *     attestationVersion INTEGER, attestationSecurityLevel SecurityLevel,
 *     keyMintVersion INTEGER, keyMintSecurityLevel SecurityLevel,
 *     attestationChallenge OCTET STRING, uniqueId OCTET STRING,
 *     softwareEnforced AuthorizationList, hardwareEnforced AuthorizationList }
 *   SecurityLevel ::= ENUMERATED { Software (0), TrustedEnvironment (1), StrongBox (2) }
 *
 * Only these AuthorizationList tags are read: purpose [1], algorithm [2], keySize [3], digest [5], ecCurve [10],
 * creationDateTime [701], origin [702], rootOfTrust [704], osVersion [705], osPatchLevel [706],
 * attestationApplicationId [709], vendorPatchLevel [718], bootPatchLevel [719]. Every other tag is skipped.
 */

/** Where a key lives. [wire] is the name used in traceveil.signature.v1 and in the attestation schema. */
enum class SecurityLevel(val wire: String, val label: String) {
    SOFTWARE("Software", "software"),
    TRUSTED_ENVIRONMENT("TrustedEnvironment", "TEE"),
    STRONGBOX("StrongBox", "StrongBox"),
    /** Android said "secure hardware" without saying which kind (KeyInfo SECURITY_LEVEL_UNKNOWN_SECURE). */
    UNKNOWN_SECURE("UnknownSecure", "secure hardware"),
    UNKNOWN("Unknown", "unknown");

    val isHardware: Boolean get() = this == TRUSTED_ENVIRONMENT || this == STRONGBOX || this == UNKNOWN_SECURE

    companion object {
        fun fromAttestation(value: Int): SecurityLevel = when (value) {
            0 -> SOFTWARE
            1 -> TRUSTED_ENVIRONMENT
            2 -> STRONGBOX
            else -> UNKNOWN
        }

        fun parse(wire: String?): SecurityLevel = entries.firstOrNull { it.wire == wire } ?: UNKNOWN
    }
}

/** KeyMint KeyOrigin: how the key came to be in the keystore. */
enum class KeyOrigin(val value: Int, val label: String) {
    GENERATED(0, "generated inside the secure hardware"),
    DERIVED(1, "derived inside the secure hardware"),
    IMPORTED(2, "imported from outside"),
    RESERVED(3, "unknown origin"),
    SECURELY_IMPORTED(4, "securely imported from outside");

    companion object {
        fun of(value: Int): KeyOrigin? = entries.firstOrNull { it.value == value }
    }
}

enum class VerifiedBootState(val label: String) {
    VERIFIED("Verified"), SELF_SIGNED("SelfSigned"), UNVERIFIED("Unverified"), FAILED("Failed");

    companion object {
        fun of(value: Int): VerifiedBootState? = entries.getOrNull(value)
    }
}

data class RootOfTrust(val deviceLocked: Boolean, val verifiedBootState: VerifiedBootState?)

data class AttestedPackage(val name: String, val version: Long)

/** attestationApplicationId: the app(s) with the key's uid and their signing-certificate SHA-256 digests. */
data class ApplicationId(val packages: List<AttestedPackage>, val signatureDigestsHex: List<String>)

data class AuthorizationList(
    val purposes: List<Long> = emptyList(),
    val algorithm: Long? = null,
    val keySize: Long? = null,
    val digests: List<Long> = emptyList(),
    val ecCurve: Long? = null,
    val creationDateTime: Long? = null,
    /** null when the tag is absent, or its value is not a known origin. */
    val origin: KeyOrigin? = null,
    val rootOfTrust: RootOfTrust? = null,
    val osVersion: Long? = null,
    val osPatchLevel: Long? = null,
    val applicationId: ApplicationId? = null,
    val vendorPatchLevel: Long? = null,
    val bootPatchLevel: Long? = null,
)

class KeyDescription(
    val attestationVersion: Long,
    val attestationSecurityLevel: SecurityLevel,
    val keyMintVersion: Long,
    val keyMintSecurityLevel: SecurityLevel,
    val challenge: ByteArray,
    val softwareEnforced: AuthorizationList,
    val hardwareEnforced: AuthorizationList,
) {
    /** Android's keystore puts the app id in the software-enforced list; some versions use the hardware list. */
    val applicationId: ApplicationId? get() = softwareEnforced.applicationId ?: hardwareEnforced.applicationId

    companion object {
        const val OID = "1.3.6.1.4.1.11129.2.1.17"

        /** The parsed extension of [cert], null when it has none. Throws [DerException] when it is there but unreadable. */
        fun of(cert: X509Certificate): KeyDescription? = cert.getExtensionValue(OID)?.let { parse(Der.read(it).octets()) }

        /** Parses the extension value (the DER KeyDescription itself). */
        fun parse(bytes: ByteArray): KeyDescription {
            val fields = Der.read(bytes).also { if (!it.isSequence) throw DerException("KeyDescription is not a SEQUENCE") }.children()
            if (fields.size < 8) throw DerException("KeyDescription has ${fields.size} fields, expected 8")
            return KeyDescription(
                attestationVersion = fields[0].long(),
                attestationSecurityLevel = SecurityLevel.fromAttestation(fields[1].int()),
                keyMintVersion = fields[2].long(),
                keyMintSecurityLevel = SecurityLevel.fromAttestation(fields[3].int()),
                challenge = fields[4].octets(),
                softwareEnforced = authorizationList(fields[6]),
                hardwareEnforced = authorizationList(fields[7]),
            )
        }

        private fun authorizationList(v: DerValue): AuthorizationList {
            if (!v.isSequence) throw DerException("AuthorizationList is not a SEQUENCE")
            var list = AuthorizationList()
            for (item in v.children()) {
                if (item.tagClass != Der.CONTEXT) continue
                val inner by lazy { item.explicitInner() }
                list = when (item.tagNumber) {
                    1 -> list.copy(purposes = inner.children().map { it.long() })
                    2 -> list.copy(algorithm = inner.long())
                    3 -> list.copy(keySize = inner.long())
                    5 -> list.copy(digests = inner.children().map { it.long() })
                    10 -> list.copy(ecCurve = inner.long())
                    701 -> list.copy(creationDateTime = inner.long())
                    702 -> list.copy(origin = KeyOrigin.of(inner.int()))
                    704 -> list.copy(rootOfTrust = rootOfTrust(inner))
                    705 -> list.copy(osVersion = inner.long())
                    706 -> list.copy(osPatchLevel = inner.long())
                    709 -> list.copy(applicationId = applicationId(inner.octets()))
                    718 -> list.copy(vendorPatchLevel = inner.long())
                    719 -> list.copy(bootPatchLevel = inner.long())
                    else -> list
                }
            }
            return list
        }

        private fun rootOfTrust(v: DerValue): RootOfTrust {
            val f = v.children()
            if (f.size < 3) throw DerException("RootOfTrust has ${f.size} fields")
            return RootOfTrust(deviceLocked = f[1].boolean(), verifiedBootState = VerifiedBootState.of(f[2].int()))
        }

        /** AttestationApplicationId ::= SEQUENCE { package_infos SET OF AttestationPackageInfo, signature_digests SET OF OCTET STRING } */
        private fun applicationId(bytes: ByteArray): ApplicationId {
            val f = Der.read(bytes).children()
            if (f.size < 2) throw DerException("AttestationApplicationId has ${f.size} fields")
            val packages = f[0].children().map { info ->
                val p = info.children()
                AttestedPackage(p[0].octets().toString(Charsets.UTF_8), p[1].long())
            }
            val digests = f[1].children().map { d -> d.octets().joinToString("") { "%02x".format(it) } }
            return ApplicationId(packages, digests)
        }
    }
}
