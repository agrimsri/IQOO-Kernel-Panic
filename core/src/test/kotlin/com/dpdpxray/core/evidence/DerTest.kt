package com.dpdpxray.core.evidence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger

class DerTest {
    private fun bad(bytes: ByteArray) {
        try {
            Der.read(bytes)
            fail("accepted malformed DER")
        } catch (e: DerException) {
            assertTrue(e.message.orEmpty().isNotBlank())
        }
    }

    @Test
    fun `reads a sequence of an integer, an octet string and a boolean`() {
        val v = Der.read(TestDer.seq(TestDer.int(300), TestDer.octets(byteArrayOf(1, 2)), TestDer.bool(true)))
        assertTrue(v.isSequence)
        val (i, o, b) = v.children()
        assertEquals(300L, i.long())
        assertEquals(listOf<Byte>(1, 2), o.octets().toList())
        assertTrue(b.boolean())
    }

    @Test
    fun `reads high tag numbers such as 709 and long lengths`() {
        val big = ByteArray(300) { 5 }
        val v = Der.read(TestDer.seq(TestDer.explicit(709, TestDer.octets(big)), TestDer.explicit(1, TestDer.int(2))))
        val (a, b) = v.children()
        assertEquals(709, a.tagNumber)
        assertEquals(Der.CONTEXT, a.tagClass)
        assertEquals(300, a.explicitInner().octets().size)
        assertEquals(1, b.tagNumber)
        assertEquals(2L, b.explicitInner().long())
    }

    @Test
    fun `negative and large integers decode exactly`() {
        assertEquals(-1L, Der.read(TestDer.int(-1)).long())
        assertEquals(BigInteger("123456789012345678901234567890"), Der.read(TestDer.int(BigInteger("123456789012345678901234567890"))).bigInteger())
    }

    @Test
    fun `truncated, indefinite-length and trailing-garbage input is refused`() {
        bad(byteArrayOf())
        bad(byteArrayOf(0x04, 0x05, 1, 2))                 // says 5 bytes, has 2
        bad(byteArrayOf(0x30, 0x80.toByte(), 0, 0))        // indefinite length is not DER
        bad(TestDer.int(1) + byteArrayOf(0))               // trailing byte
        bad(byteArrayOf(0x04, 0x84.toByte(), 0x7F, 0, 0, 0)) // absurd length
    }

    @Test
    fun `deep nesting is refused instead of overflowing the stack`() {
        var v = TestDer.nul()
        repeat(200) { v = TestDer.seq(v) }
        bad(v)
    }
}
