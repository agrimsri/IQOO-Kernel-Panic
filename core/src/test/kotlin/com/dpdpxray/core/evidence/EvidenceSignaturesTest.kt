package com.dpdpxray.core.evidence

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class EvidenceSignaturesTest {
    private val bundle = "{\"schema\":\"traceveil.audit.v1\"}\n".toByteArray()

    @Test
    fun `the file has exactly the documented fields, leaf certificate first`() {
        val chain = TestPki.chain()
        val text = EvidenceSignatures.encode(
            EvidenceSignatures.create(bundle, chain.sign(bundle), chain.derChain, "2026-10-11T08:00:05Z", "1.1.0", SecurityLevel.STRONGBOX, "TraceVeil-audit-1.json"),
        )
        val o = Json.parseToJsonElement(text).jsonObject
        assertEquals(
            listOf("schema", "algorithm", "signedFile", "sha256", "signature", "certificateChain", "createdAt", "appVersion", "keyAlias", "securityLevel", "about"),
            o.keys.toList(),
        )
        assertEquals("traceveil.signature.v1", o["schema"]!!.jsonPrimitive.content)
        assertEquals("ES256", o["algorithm"]!!.jsonPrimitive.content)
        assertEquals("StrongBox", o["securityLevel"]!!.jsonPrimitive.content)
        assertEquals("traceveil-evidence-v1", o["keyAlias"]!!.jsonPrimitive.content)
        val sha = MessageDigest.getInstance("SHA-256").digest(bundle).joinToString("") { "%02x".format(it) }
        assertEquals(sha, o["sha256"]!!.jsonPrimitive.content)
        val first = Base64.getDecoder().decode(o["certificateChain"]!!.jsonArray.first().jsonPrimitive.content)
        assertTrue(first.contentEquals(chain.certificates.first().encoded))
        assertEquals(EvidenceSignatures.decode(text), EvidenceSignatures.decode(text.replace("\n", "\r\n")))
    }

    @Test
    fun `the signature file sits next to the bundle with a fixed suffix`() {
        assertEquals("TraceVeil-audit-1.json.sig.json", EvidenceSignatures.fileNameFor("TraceVeil-audit-1.json"))
        assertEquals(listOf("TraceVeil-audit-1.json.sig.json", "TraceVeil-audit-1.sig.json"), EvidenceSignatures.candidatesFor("TraceVeil-audit-1.json"))
        assertEquals(listOf("bundle.sig.json"), EvidenceSignatures.candidatesFor("bundle"))
    }

    @Test
    fun `the challenge is a labelled per-install random value that fits Keystore's 128-byte limit`() {
        val c = EvidenceSignatures.challenge(ByteArray(16) { it.toByte() })
        assertEquals("traceveil-evidence-v1:000102030405060708090a0b0c0d0e0f", c.toString(Charsets.US_ASCII))
        assertTrue(c.size <= 128)
    }

    @Test
    fun `the export line never claims more than the phone's key level`() {
        assertEquals("Signed in this phone's secure hardware (StrongBox) · verify with traceveil-ci verify", EvidenceSignatures.exportLine(SecurityLevel.STRONGBOX))
        assertEquals("Signed in this phone's secure hardware (TEE) · verify with traceveil-ci verify", EvidenceSignatures.exportLine(SecurityLevel.TRUSTED_ENVIRONMENT))
        assertEquals("Signed in this phone's secure hardware · verify with traceveil-ci verify", EvidenceSignatures.exportLine(SecurityLevel.UNKNOWN_SECURE))
        assertEquals(EvidenceSignatures.UNAVAILABLE, EvidenceSignatures.exportLine(SecurityLevel.SOFTWARE))
        assertEquals(EvidenceSignatures.UNAVAILABLE, EvidenceSignatures.exportLine(SecurityLevel.UNKNOWN))
        assertEquals("Signing unavailable on this phone", EvidenceSignatures.UNAVAILABLE)
        assertTrue(SecurityLevel.STRONGBOX.isHardware && SecurityLevel.TRUSTED_ENVIRONMENT.isHardware)
        assertTrue(!SecurityLevel.SOFTWARE.isHardware && !SecurityLevel.UNKNOWN.isHardware)
    }
}
