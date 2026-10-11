package com.dpdpxray.core.evidence

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/*
 * Test-only PKI: a self-made "attestation" chain (test root -> test intermediate -> leaf with a key attestation
 * extension), built with the JDK alone. Keys are generated fresh in memory for each test run; none is ever written to
 * disk or committed. A chain made here never ends at a Google root, so it only verifies against the test root passed in.
 */
object TestDer {
    fun tlv(identifier: ByteArray, content: ByteArray): ByteArray = identifier + length(content.size) + content
    fun tlv(tag: Int, content: ByteArray): ByteArray = tlv(byteArrayOf(tag.toByte()), content)

    private fun length(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    }

    fun seq(vararg parts: ByteArray) = tlv(0x30, concat(parts.toList()))
    fun seq(parts: List<ByteArray>) = tlv(0x30, concat(parts))
    fun set(vararg parts: ByteArray) = tlv(0x31, concat(parts.toList()))
    fun set(parts: List<ByteArray>) = tlv(0x31, concat(parts))
    fun int(v: Long) = tlv(0x02, BigInteger.valueOf(v).toByteArray())
    fun int(v: BigInteger) = tlv(0x02, v.toByteArray())
    fun enumerated(v: Int) = tlv(0x0A, BigInteger.valueOf(v.toLong()).toByteArray())
    fun octets(b: ByteArray) = tlv(0x04, b)
    fun bool(b: Boolean) = tlv(0x01, byteArrayOf(if (b) 0xFF.toByte() else 0))
    fun nul() = tlv(0x05, ByteArray(0))
    fun utf8(s: String) = tlv(0x0C, s.toByteArray(Charsets.UTF_8))
    fun bitString(b: ByteArray) = tlv(0x03, byteArrayOf(0) + b)

    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((arcs[0] * 40 + arcs[1]).toInt())
        for (arc in arcs.drop(2)) out.write(base128(arc))
        return tlv(0x06, out.toByteArray())
    }

    /** [n] EXPLICIT, constructed, context-specific; tag numbers of 31 and above use the high-tag-number form. */
    fun explicit(n: Int, inner: ByteArray): ByteArray =
        if (n < 31) tlv(0xA0 or n, inner) else tlv(byteArrayOf(0xBF.toByte()) + base128(n.toLong()), inner)

    fun time(t: Instant): ByteArray {
        val utc = t.atZone(ZoneOffset.UTC)
        return if (utc.year in 1950..2049) tlv(0x17, DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").format(utc).toByteArray())
        else tlv(0x18, DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'").format(utc).toByteArray())
    }

    private fun base128(v: Long): ByteArray {
        val groups = mutableListOf<Int>()
        var x = v
        do { groups += (x and 0x7F).toInt(); x = x shr 7 } while (x > 0)
        return groups.reversed().mapIndexed { i, g -> (if (i < groups.size - 1) g or 0x80 else g).toByte() }.toByteArray()
    }

    fun concat(parts: List<ByteArray>): ByteArray = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
}

/** The fields of a KeyDescription that tests vary; the defaults describe a healthy StrongBox key made by TraceVeil. */
data class TestAttestation(
    val attestationVersion: Long = 300,
    val securityLevel: Int = 2,
    val keyMintVersion: Long = 300,
    val keyMintSecurityLevel: Int = 2,
    val challenge: ByteArray = "traceveil-evidence-v1:0123456789abcdef0123456789abcdef".toByteArray(),
    val packages: List<Pair<String, Long>> = listOf("com.dpdpxray.app" to 1L),
    val signatureDigests: List<ByteArray> = listOf(ByteArray(32) { (it + 1).toByte() }),
    val origin: Int? = 0,
    val purposes: List<Int> = listOf(2),
    val deviceLocked: Boolean = true,
    val verifiedBootState: Int = 0,
    val osVersion: Long = 160000,
    val osPatchLevel: Long = 202609,
    val creationMs: Long = 1_791_600_000_000,
    /** Put the application id in the hardware-enforced list instead (some KeyMint versions do). */
    val appIdInHardwareList: Boolean = false,
)

object TestPki {
    const val ATTESTATION_OID = "1.3.6.1.4.1.11129.2.1.17"
    private const val ECDSA_SHA256 = "1.2.840.10045.4.3.2"
    private var serial = 1L

    fun ecKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun keyDescription(a: TestAttestation): ByteArray {
        val appId = TestDer.seq(
            TestDer.set(a.packages.map { (name, version) -> TestDer.seq(TestDer.octets(name.toByteArray()), TestDer.int(version)) }),
            TestDer.set(a.signatureDigests.map { TestDer.octets(it) }),
        )
        val software = mutableListOf(TestDer.explicit(701, TestDer.int(a.creationMs)))
        val hardware = mutableListOf(
            TestDer.explicit(1, TestDer.set(a.purposes.map { TestDer.int(it.toLong()) })),
            TestDer.explicit(2, TestDer.int(3)),
            TestDer.explicit(3, TestDer.int(256)),
            TestDer.explicit(5, TestDer.set(TestDer.int(4))),
            TestDer.explicit(10, TestDer.int(1)),
            TestDer.explicit(503, TestDer.nul()),
        )
        a.origin?.let { hardware += TestDer.explicit(702, TestDer.int(it.toLong())) }
        hardware += TestDer.explicit(
            704,
            TestDer.seq(TestDer.octets(ByteArray(32)), TestDer.bool(a.deviceLocked), TestDer.enumerated(a.verifiedBootState), TestDer.octets(ByteArray(32) { 7 })),
        )
        hardware += TestDer.explicit(705, TestDer.int(a.osVersion))
        hardware += TestDer.explicit(706, TestDer.int(a.osPatchLevel))
        (if (a.appIdInHardwareList) hardware else software) += TestDer.explicit(709, TestDer.octets(appId))
        return TestDer.seq(
            TestDer.int(a.attestationVersion), TestDer.enumerated(a.securityLevel),
            TestDer.int(a.keyMintVersion), TestDer.enumerated(a.keyMintSecurityLevel),
            TestDer.octets(a.challenge), TestDer.octets(ByteArray(0)),
            TestDer.seq(software), TestDer.seq(hardware),
        )
    }

    /**
     * One X.509 v3 certificate for [subjectKey]'s public key, signed by [issuerKey] (ECDSA with SHA-256). [attestation]
     * adds the key attestation extension with that exact content.
     */
    fun certificate(
        subject: String,
        subjectKey: java.security.PublicKey,
        issuer: String,
        issuerKey: PrivateKey,
        ca: Boolean,
        attestation: ByteArray? = null,
        notBefore: Instant = Instant.parse("2026-01-01T00:00:00Z"),
        notAfter: Instant = Instant.parse("2036-01-01T00:00:00Z"),
    ): X509Certificate {
        val algorithm = TestDer.seq(TestDer.oid(ECDSA_SHA256))
        val extensions = buildList {
            if (ca) add(TestDer.seq(TestDer.oid("2.5.29.19"), TestDer.bool(true), TestDer.octets(TestDer.seq(TestDer.bool(true)))))
            attestation?.let { add(TestDer.seq(TestDer.oid(ATTESTATION_OID), TestDer.octets(it))) }
        }
        val tbs = TestDer.seq(
            listOfNotNull(
                TestDer.explicit(0, TestDer.int(2)),
                TestDer.int(serial++),
                algorithm,
                name(issuer),
                TestDer.seq(TestDer.time(notBefore), TestDer.time(notAfter)),
                name(subject),
                subjectKey.encoded,
                extensions.takeIf { it.isNotEmpty() }?.let { TestDer.explicit(3, TestDer.seq(it)) },
            ),
        )
        val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(issuerKey); update(tbs) }.sign()
        val der = TestDer.seq(tbs, algorithm, TestDer.bitString(sig))
        return CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    }

    private fun name(cn: String) = TestDer.seq(TestDer.set(TestDer.seq(TestDer.oid("2.5.4.3"), TestDer.utf8(cn))))

    /** A test root, one intermediate and a leaf for [leafKey] carrying [attestation]. */
    class Chain(val leafKey: KeyPair, val root: X509Certificate, val certificates: List<X509Certificate>) {
        val trustedRoot = TrustedRoot("TraceVeil test root (not Google)", root)
        fun sign(bytes: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").apply { initSign(leafKey.private); update(bytes) }.sign()
        val derChain: List<ByteArray> get() = certificates.map { it.encoded }
    }

    fun chain(attestation: TestAttestation? = TestAttestation(), leafKey: KeyPair = ecKeyPair()): Chain {
        val rootKey = ecKeyPair()
        val interKey = ecKeyPair()
        val root = certificate("TraceVeil Test Root", rootKey.public, "TraceVeil Test Root", rootKey.private, ca = true)
        val inter = certificate("TraceVeil Test Attestation Key", interKey.public, "TraceVeil Test Root", rootKey.private, ca = true)
        val leaf = certificate(
            "Android Keystore Key", leafKey.public, "TraceVeil Test Attestation Key", interKey.private, ca = false,
            attestation = attestation?.let { keyDescription(it) },
            notBefore = Instant.EPOCH, notAfter = Instant.parse("2048-01-01T00:00:00Z"),
        )
        return Chain(leafKey, root, listOf(leaf, inter, root))
    }

    /** Reads every certificate in a PEM file (leaf first, as the files are written). */
    fun readPem(text: String): List<X509Certificate> =
        CertificateFactory.getInstance("X.509").generateCertificates(text.byteInputStream()).map { it as X509Certificate }
}
