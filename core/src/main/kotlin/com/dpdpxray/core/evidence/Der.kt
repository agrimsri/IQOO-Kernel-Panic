package com.dpdpxray.core.evidence

import java.math.BigInteger

/** The bytes are not the DER this reader expects; the message says what was wrong. */
class DerException(message: String) : IllegalArgumentException(message)

/**
 * One DER value (X.690 distinguished encoding): identifier, definite length, content. Constructed values are parsed
 * eagerly into [children] with a depth limit, so damaged or hostile input fails here with [DerException] instead of
 * later. Only what the key attestation extension needs is supported: universal and context-specific tags (including
 * the high-tag-number form, e.g. [709]), INTEGER, ENUMERATED, BOOLEAN, OCTET STRING, NULL, SEQUENCE and SET.
 */
class DerValue internal constructor(
    val tagClass: Int,
    val constructed: Boolean,
    val tagNumber: Int,
    private val content: ByteArray,
    private val parsedChildren: List<DerValue>?,
) {
    val isSequence get() = tagClass == Der.UNIVERSAL && constructed && tagNumber == 0x10
    val isSet get() = tagClass == Der.UNIVERSAL && constructed && tagNumber == 0x11

    fun children(): List<DerValue> = parsedChildren ?: throw DerException("expected a constructed value, found tag $tagNumber")

    /** The single value inside an EXPLICIT context tag such as [701]. */
    fun explicitInner(): DerValue = children().singleOrNull() ?: throw DerException("tag [$tagNumber] does not hold exactly one value")

    fun bigInteger(): BigInteger {
        if (constructed || tagClass != Der.UNIVERSAL || (tagNumber != 0x02 && tagNumber != 0x0A)) throw DerException("expected an INTEGER or ENUMERATED")
        if (content.isEmpty()) throw DerException("empty INTEGER")
        return BigInteger(content)
    }

    fun long(): Long {
        val v = bigInteger()
        if (v.bitLength() > 63) throw DerException("INTEGER too large")
        return v.toLong()
    }

    fun int(): Int {
        val v = long()
        if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) throw DerException("INTEGER out of range")
        return v.toInt()
    }

    fun boolean(): Boolean {
        if (constructed || tagClass != Der.UNIVERSAL || tagNumber != 0x01 || content.size != 1) throw DerException("expected a BOOLEAN")
        return content[0] != 0.toByte()
    }

    fun octets(): ByteArray {
        if (constructed || tagClass != Der.UNIVERSAL || tagNumber != 0x04) throw DerException("expected an OCTET STRING")
        return content.copyOf()
    }

    override fun toString(): String = "DerValue(class=$tagClass, tag=$tagNumber, constructed=$constructed, ${content.size} bytes)"
}

object Der {
    const val UNIVERSAL = 0
    const val CONTEXT = 2
    private const val MAX_DEPTH = 32

    /** Reads exactly one value that fills [bytes]; trailing bytes are an error. */
    fun read(bytes: ByteArray): DerValue {
        val (value, end) = readAt(bytes, 0, bytes.size, 0)
        if (end != bytes.size) throw DerException("${bytes.size - end} unexpected bytes after the value")
        return value
    }

    private fun readAt(b: ByteArray, start: Int, limit: Int, depth: Int): Pair<DerValue, Int> {
        if (depth > MAX_DEPTH) throw DerException("nested more than $MAX_DEPTH levels deep")
        var p = start
        fun next(): Int {
            if (p >= limit) throw DerException("truncated")
            return b[p++].toInt() and 0xFF
        }
        val id = next()
        val tagClass = id ushr 6
        val constructed = id and 0x20 != 0
        var tag = id and 0x1F
        if (tag == 0x1F) {
            tag = 0
            var count = 0
            while (true) {
                val x = next()
                if (count == 0 && x == 0x80) throw DerException("non-minimal tag number")
                tag = (tag shl 7) or (x and 0x7F)
                if (++count > 4) throw DerException("tag number too large")
                if (x and 0x80 == 0) break
            }
        }
        val first = next()
        val length = when {
            first < 0x80 -> first
            first == 0x80 -> throw DerException("indefinite length is not DER")
            else -> {
                val n = first and 0x7F
                if (n > 3) throw DerException("length too large")
                var len = 0
                repeat(n) { len = (len shl 8) or next() }
                len
            }
        }
        if (length > limit - p) throw DerException("truncated: value says $length bytes, ${limit - p} left")
        val content = b.copyOfRange(p, p + length)
        val children = if (constructed) {
            val list = mutableListOf<DerValue>()
            var q = p
            while (q < p + length) {
                val (child, end) = readAt(b, q, p + length, depth + 1)
                list += child
                q = end
            }
            list
        } else null
        return DerValue(tagClass, constructed, tag, content, children) to (p + length)
    }
}
