package com.dpdpxray.app.evidence

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import com.dpdpxray.core.evidence.EvidenceSignatures
import com.dpdpxray.core.evidence.SecurityLevel
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * This install's evidence key in Android Keystore: alias "traceveil-evidence-v1", EC P-256, sign only, SHA-256, made
 * the first time an export is signed. It asks for StrongBox and falls back to the TEE when the phone has no StrongBox or
 * StrongBox refuses. The private key is created inside that hardware and cannot be exported; TraceVeil uses it only to
 * sign files the person exports.
 *
 * Attestation challenge: "traceveil-evidence-v1:" + 16 random bytes (hex), made once with the key. The key signs many
 * exports, so a per-export value (such as the bundle hash) cannot be the challenge; the random part only tells this
 * install's key apart. It is not a freshness check, and the verifier shows it only as information.
 */
class AndroidEvidenceKey(private val context: Context) : EvidenceKey {
    override fun sign(bytes: ByteArray): HardwareSignature {
        val (key, chain) = entry()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(bytes)
            sign()
        }
        return HardwareSignature(signature, chain.map { it.encoded }, levelOf(key))
    }

    private fun entry(): Pair<PrivateKey, List<java.security.cert.Certificate>> {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (!ks.containsAlias(ALIAS)) generate()
        val key = ks.getKey(ALIAS, null) as? PrivateKey ?: error("the evidence key could not be read from Android Keystore")
        val chain = ks.getCertificateChain(ALIAS)?.toList().orEmpty()
        return key to chain
    }

    /** One key per install, even if an export and a Runner bundle are signed at the same moment. */
    private fun generate() {
        synchronized(LOCK) {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (ks.containsAlias(ALIAS)) return
            val challenge = EvidenceSignatures.challenge(ByteArray(16).also { SecureRandom().nextBytes(it) })
            fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAttestationChallenge(challenge)
                .setIsStrongBoxBacked(strongBox)
                .build()
            fun make(strongBox: Boolean) = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).run {
                initialize(spec(strongBox))
                generateKeyPair()
            }
            if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)) {
                if (runCatching { make(strongBox = true) }.isSuccess) return
                // StrongBoxUnavailableException, or StrongBox refusing this key or its attestation: use the TEE instead.
                runCatching { ks.deleteEntry(ALIAS) }
            }
            make(strongBox = false)
        }
    }

    private fun levelOf(key: PrivateKey): SecurityLevel = runCatching {
        val info = KeyFactory.getInstance(key.algorithm, KEYSTORE).getKeySpec(key, KeyInfo::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) KeystoreLevels.fromKeyInfo(info.securityLevel)
        else @Suppress("DEPRECATION") KeystoreLevels.legacy(info.isInsideSecureHardware)
    }.getOrDefault(SecurityLevel.UNKNOWN)

    companion object {
        const val ALIAS = EvidenceSignatures.KEY_ALIAS
        private const val KEYSTORE = "AndroidKeyStore"
        private val LOCK = Any()
    }
}
