package com.dpdpxray.core.geo

class MmdbFormatException(message: String) : Exception(message)

/**
 * Minimal reader for the MaxMind DB format (https://maxmind.github.io/MaxMind-DB/), enough to look an address up in a
 * country database. The file is untrusted input: every read is bounds-checked and decoding depth is limited, so a
 * corrupt or hostile file fails with [MmdbFormatException] instead of crashing, hanging or reading out of range.
 */
class MmdbReader private constructor(
    private val buf: ByteArray,
    private val nodeCount: Int,
    private val recordSize: Int,
    val ipVersion: Int,
    val databaseType: String?,
    val buildEpochSeconds: Long?,
) {
    private val nodeBytes = recordSize / 4
    private val treeSize = nodeCount.toLong() * nodeBytes
    private val dataStart = treeSize + 16
    private val ipv4Start: Int by lazy { if (ipVersion == 4) 0 else (0 until 96).fold(0) { n, _ -> if (n < nodeCount) record(n, 0) else n } }

    /** The decoded record for [address] (4 or 16 bytes), or null when the address is not in the database. */
    fun find(address: ByteArray): Map<String, Any?>? {
        val bits = when {
            address.size == 4 -> 32
            address.size == 16 && ipVersion == 6 -> 128
            else -> return null
        }
        var node = if (address.size == 4) ipv4Start else 0
        var i = 0
        while (i < bits && node < nodeCount) {
            node = record(node, (address[i shr 3].toInt() shr (7 - (i and 7))) and 1)
            i++
        }
        if (node == nodeCount || node < nodeCount) return null
        val offset = node.toLong() - nodeCount + treeSize
        if (offset >= buf.size) throw MmdbFormatException("data pointer outside the file")
        @Suppress("UNCHECKED_CAST")
        return Decoder(buf, dataStart.toInt(), 0).decode(offset.toInt()).first as? Map<String, Any?>
    }

    private fun record(node: Int, side: Int): Int {
        val p = node * nodeBytes
        if (p + nodeBytes > buf.size) throw MmdbFormatException("search tree runs past the end of the file")
        fun u(o: Int) = buf[p + o].toInt() and 0xFF
        return when (recordSize) {
            24 -> if (side == 0) (u(0) shl 16) or (u(1) shl 8) or u(2) else (u(3) shl 16) or (u(4) shl 8) or u(5)
            28 -> if (side == 0) ((u(3) and 0xF0) shl 20) or (u(0) shl 16) or (u(1) shl 8) or u(2)
            else ((u(3) and 0x0F) shl 24) or (u(4) shl 16) or (u(5) shl 8) or u(6)
            else -> if (side == 0) (u(0) shl 24) or (u(1) shl 16) or (u(2) shl 8) or u(3) else (u(4) shl 24) or (u(5) shl 16) or (u(6) shl 8) or u(7)
        }
    }

    /** Decodes values of the MaxMind data section; pointers are relative to [base], recursion is capped by [MAX_DEPTH]. */
    private class Decoder(val b: ByteArray, val base: Int, val depth: Int) {
        /** Returns the value and the offset just after it. */
        fun decode(start: Int): Pair<Any?, Int> {
            if (depth > MAX_DEPTH) throw MmdbFormatException("data nested too deeply")
            var p = start
            fun byteAt(i: Int): Int { if (i < 0 || i >= b.size) throw MmdbFormatException("read past the end"); return b[i].toInt() and 0xFF }
            val ctrl = byteAt(p++)
            var type = ctrl ushr 5
            if (type == 1) { // pointer: not followed in place, it replaces the whole value
                val ss = (ctrl ushr 3) and 3
                val vvv = ctrl and 7
                val target = when (ss) {
                    0 -> (vvv shl 8) or byteAt(p)
                    1 -> ((vvv shl 16) or (byteAt(p) shl 8) or byteAt(p + 1)) + 2048
                    2 -> ((vvv shl 24) or (byteAt(p) shl 16) or (byteAt(p + 1) shl 8) or byteAt(p + 2)) + 526336
                    else -> (byteAt(p) shl 24) or (byteAt(p + 1) shl 16) or (byteAt(p + 2) shl 8) or byteAt(p + 3)
                }
                val after = p + ss + 1
                if (target < 0) throw MmdbFormatException("bad pointer")
                return Decoder(b, base, depth + 1).decode(base + target).first to after
            }
            if (type == 0) type = 7 + byteAt(p++)
            var size = ctrl and 0x1F
            when (size) {
                29 -> size = 29 + byteAt(p++)
                30 -> { size = 285 + ((byteAt(p) shl 8) or byteAt(p + 1)); p += 2 }
                31 -> { size = 65821 + ((byteAt(p) shl 16) or (byteAt(p + 1) shl 8) or byteAt(p + 2)); p += 3 }
            }
            fun need(n: Int) { if (n < 0 || p + n > b.size) throw MmdbFormatException("value runs past the end of the file") }
            fun number(n: Int): Long { need(n); var v = 0L; for (i in 0 until n) v = (v shl 8) or (b[p + i].toLong() and 0xFF); return v }
            return when (type) {
                2 -> { need(size); String(b, p, size, Charsets.UTF_8) to p + size }
                3 -> { need(8); java.lang.Double.longBitsToDouble(number(8)) to p + 8 }
                4 -> { need(size); b.copyOfRange(p, p + size) to p + size }
                5, 6, 9, 10 -> { if (size > 16) throw MmdbFormatException("integer too wide"); number(minOf(size, 8)) to p + size }
                8 -> { need(size); number(size).toInt() to p + size }
                7 -> {
                    if (size > MAX_ENTRIES) throw MmdbFormatException("map too large")
                    val map = LinkedHashMap<String, Any?>()
                    var q = p
                    repeat(size) {
                        val (k, afterKey) = Decoder(b, base, depth + 1).decode(q)
                        val (v, afterValue) = Decoder(b, base, depth + 1).decode(afterKey)
                        map[k as? String ?: throw MmdbFormatException("map key is not a string")] = v
                        q = afterValue
                    }
                    map to q
                }
                11 -> {
                    if (size > MAX_ENTRIES) throw MmdbFormatException("array too large")
                    var q = p
                    val list = ArrayList<Any?>()
                    repeat(size) { val (v, after) = Decoder(b, base, depth + 1).decode(q); list += v; q = after }
                    list to q
                }
                14 -> (size != 0) to p
                15 -> { need(4); java.lang.Float.intBitsToFloat(number(4).toInt()) to p + 4 }
                else -> throw MmdbFormatException("unsupported data type $type")
            }
        }
    }

    companion object {
        private const val MAX_DEPTH = 24
        private const val MAX_ENTRIES = 4096
        private val MARKER = byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte()) + "MaxMind.com".toByteArray()

        fun open(bytes: ByteArray): MmdbReader {
            val at = lastIndexOf(bytes, MARKER)
            if (at < 0) throw MmdbFormatException("not a MaxMind database (no metadata marker)")
            @Suppress("UNCHECKED_CAST")
            val meta = Decoder(bytes, at + MARKER.size, 0).decode(at + MARKER.size).first as? Map<String, Any?>
                ?: throw MmdbFormatException("metadata is not a map")
            val nodes = (meta["node_count"] as? Long)?.takeIf { it in 1..Int.MAX_VALUE / 8 }?.toInt() ?: throw MmdbFormatException("bad node_count")
            val size = (meta["record_size"] as? Long)?.toInt()?.takeIf { it == 24 || it == 28 || it == 32 } ?: throw MmdbFormatException("unsupported record_size")
            val version = (meta["ip_version"] as? Long)?.toInt()?.takeIf { it == 4 || it == 6 } ?: throw MmdbFormatException("bad ip_version")
            if (nodes.toLong() * (size / 4) + 16 > at) throw MmdbFormatException("search tree larger than the file")
            return MmdbReader(bytes, nodes, size, version, meta["database_type"] as? String, meta["build_epoch"] as? Long)
        }

        private fun lastIndexOf(hay: ByteArray, needle: ByteArray): Int {
            var i = hay.size - needle.size
            while (i >= 0) {
                var j = 0
                while (j < needle.size && hay[i + j] == needle[j]) j++
                if (j == needle.size) return i
                i--
            }
            return -1
        }
    }
}
