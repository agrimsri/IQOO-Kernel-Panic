package com.dpdpxray.core.evidence

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Base64

/*
 * "traceveil.signature.v1": a detached signature written next to an exported file (TraceVeil-audit-1.json ->
 * TraceVeil-audit-1.json.sig.json). The signed bytes are the exported file's exact bytes, so the audit bundle format
 * (traceveil.audit.v1) and every reader of it stay unchanged.
 *
 *   { "schema": "traceveil.signature.v1",
 *     "algorithm": "ES256",                     ECDSA P-256 with SHA-256
 *     "signedFile": "TraceVeil-audit-1.json",   name of the file when it was signed (informational)
 *     "sha256": hex,                            SHA-256 of the signed file's bytes
 *     "signature": base64,                      ASN.1 DER ECDSA signature over those bytes
 *     "certificateChain": [base64 DER, ...],    Android Key Attestation chain of the signing key, leaf first
 *     "createdAt": ISO-8601, "appVersion": versionName,
 *     "keyAlias": "traceveil-evidence-v1",
 *     "securityLevel": "StrongBox" | "TrustedEnvironment" | "UnknownSecure",  what the phone's Keystore reported
 *     "about": plain-language note }
 *
 * Only "signature" and the attested chain are evidence. createdAt, appVersion and securityLevel are written by the app
 * and are not covered by the signature; a verifier reports the attestation's own security level.
 */
@Serializable
data class EvidenceSignatureV1(
    val schema: String = EvidenceSignatures.SCHEMA,
    val algorithm: String = EvidenceSignatures.ALGORITHM,
    val signedFile: String? = null,
    val sha256: String,
    val signature: String,
    val certificateChain: List<String>,
    val createdAt: String,
    val appVersion: String,
    val keyAlias: String = EvidenceSignatures.KEY_ALIAS,
    val securityLevel: String,
    val about: String = EvidenceSignatures.ABOUT,
)

/** The text is not a readable signature file; the message says why. */
class SignatureFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

object EvidenceSignatures {
    const val SCHEMA = "traceveil.signature.v1"
    const val ALGORITHM = "ES256"
    const val KEY_ALIAS = "traceveil-evidence-v1"
    const val SUFFIX = ".sig.json"
    const val MIME = "application/json"
    const val CHALLENGE_PREFIX = "traceveil-evidence-v1:"

    const val ABOUT = "Detached signature for the file named in signedFile: ECDSA P-256 with SHA-256 over its exact bytes, " +
        "made by a key that TraceVeil keeps in this phone's Android Keystore. certificateChain is that key's Android Key " +
        "Attestation chain, leaf first. Verify offline with: traceveil-ci verify <file>."

    const val UNAVAILABLE = "Signing unavailable on this phone"

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun create(
        bundle: ByteArray,
        signature: ByteArray,
        certificateChain: List<ByteArray>,
        createdAt: String,
        appVersion: String,
        securityLevel: SecurityLevel,
        signedFile: String?,
    ) = EvidenceSignatureV1(
        signedFile = signedFile,
        sha256 = sha256Hex(bundle),
        signature = Base64.getEncoder().encodeToString(signature),
        certificateChain = certificateChain.map { Base64.getEncoder().encodeToString(it) },
        createdAt = createdAt,
        appVersion = appVersion,
        securityLevel = securityLevel.wire,
    )

    fun encode(sig: EvidenceSignatureV1): String = json.encodeToString(EvidenceSignatureV1.serializer(), sig) + "\n"

    fun decode(text: String): EvidenceSignatureV1 = try {
        json.decodeFromString(EvidenceSignatureV1.serializer(), text)
    } catch (e: IllegalArgumentException) {
        val reason = e.message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        throw SignatureFormatException("not a readable $SCHEMA file ($reason)", e)
    }

    /** "TraceVeil-audit-1.json" -> "TraceVeil-audit-1.json.sig.json", the name the app writes. */
    fun fileNameFor(signedFileName: String) = signedFileName + SUFFIX

    /** Names a verifier looks for next to a bundle, in order: the app's name, then the shorter "<name>.sig.json". */
    fun candidatesFor(signedFileName: String): List<String> =
        listOfNotNull(fileNameFor(signedFileName), signedFileName.removeSuffix(".json").takeIf { it != signedFileName }?.plus(SUFFIX))

    /**
     * The attestation challenge for this install's key: a fixed label plus a random value made once with the key. Only
     * the verifier on the laptop reads it; it is not a freshness nonce (the key signs many exports), it just tells
     * this install's key apart.
     */
    fun challenge(random: ByteArray): ByteArray = (CHALLENGE_PREFIX + hex(random)).toByteArray(Charsets.US_ASCII)

    /** The one line shown after an export. It names only the level Android reported for the key. */
    fun exportLine(level: SecurityLevel): String = when (level) {
        SecurityLevel.STRONGBOX, SecurityLevel.TRUSTED_ENVIRONMENT -> "Signed in this phone's secure hardware (${level.label}) · verify with traceveil-ci verify"
        SecurityLevel.UNKNOWN_SECURE -> "Signed in this phone's secure hardware · verify with traceveil-ci verify"
        else -> UNAVAILABLE
    }

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
