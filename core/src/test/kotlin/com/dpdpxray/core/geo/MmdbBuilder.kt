package com.dpdpxray.core.geo

import java.io.ByteArrayOutputStream

/**
 * Test-only writer for tiny MaxMind DB files, so the reader is checked against the public format spec with
 * hand-made data instead of a licensed database. Not the production format authority: it only covers what tests need.
 */
internal class MmdbBuilder(private val ipVersion: Int, private val recordSize: Int = 24) {
    private val bits = if (ipVersion == 4) 32 else 128
    private val nodes = ArrayList<IntArray>()
    private val data = ArrayList<ByteArray>()

    init { nodes += intArrayOf(EMPTY, EMPTY) }

    /** Adds a data record and returns its index for [insert]. */
    fun record(value: ByteArray): Int = data.size.also { data += value }

    /** [address] has [bits]/8 bytes; for a v6 tree an IPv4 range is inserted as ::a.b.c.d (96 extra leading bits). */
    fun insert(address: ByteArray, prefixLen: Int, dataIndex: Int) {
        val full = if (ipVersion == 6 && address.size == 4) ByteArray(12) + address else address
        val len = if (ipVersion == 6 && address.size == 4) prefixLen + 96 else prefixLen
        require(full.size * 8 == bits)
        var node = 0
        for (i in 0 until len) {
            val bit = (full[i / 8].toInt() shr (7 - i % 8)) and 1
            if (i == len - 1) { nodes[node][bit] = -(dataIndex + 1); return }
            var next = nodes[node][bit]
            if (next < 0 || next == EMPTY) { nodes += intArrayOf(EMPTY, EMPTY); next = nodes.size - 1; nodes[node][bit] = next }
            node = next
        }
    }

    fun build(buildEpoch: Long = 1_700_000_000L, type: String = "GeoLite2-Country", trailer: ByteArray = ByteArray(0)): ByteArray {
        val offsets = IntArray(data.size).also { var o = 0; data.forEachIndexed { i, d -> it[i] = o; o += d.size } }
        val nodeCount = nodes.size
        val out = ByteArrayOutputStream()
        fun rec(v: Int) = when {
            v == EMPTY -> nodeCount
            v >= 0 -> v
            else -> nodeCount + 16 + offsets[-v - 1]
        }
        for (n in nodes) {
            val l = rec(n[0]); val r = rec(n[1])
            when (recordSize) {
                24 -> { out.write(be(l, 3)); out.write(be(r, 3)) }
                28 -> { out.write(be(l and 0xFFFFFF, 3)); out.write(((l ushr 24 and 0xF) shl 4) or (r ushr 24 and 0xF)); out.write(be(r and 0xFFFFFF, 3)) }
                32 -> { out.write(be(l, 4)); out.write(be(r, 4)) }
                else -> error("record size")
            }
        }
        out.write(ByteArray(16))
        data.forEach { out.write(it) }
        out.write(trailer)
        out.write(byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte()) + "MaxMind.com".toByteArray())
        out.write(map("node_count" to uint(6, nodeCount.toLong()), "record_size" to uint(5, recordSize.toLong()),
            "ip_version" to uint(5, ipVersion.toLong()), "database_type" to str(type), "build_epoch" to uint(9, buildEpoch)))
        return out.toByteArray()
    }

    companion object {
        private const val EMPTY = Int.MIN_VALUE

        private fun be(v: Int, n: Int) = ByteArray(n) { (v ushr (8 * (n - 1 - it))).toByte() }

        private fun ctrl(type: Int, size: Int): ByteArray {
            val t = if (type <= 7) type shl 5 else 0
            val ext = if (type > 7) byteArrayOf((type - 7).toByte()) else ByteArray(0)
            return when {
                size < 29 -> byteArrayOf((t or size).toByte()) + ext
                size < 285 -> byteArrayOf((t or 29).toByte()) + ext + byteArrayOf((size - 29).toByte())
                else -> byteArrayOf((t or 30).toByte()) + ext + be(size - 285, 2)
            }
        }

        fun str(s: String): ByteArray = s.toByteArray().let { ctrl(2, it.size) + it }
        fun uint(type: Int, v: Long): ByteArray {
            var n = 0; while (n < 8 && (v ushr (8 * n)) != 0L) n++
            return ctrl(type, n) + ByteArray(n) { (v ushr (8 * (n - 1 - it))).toByte() }
        }
        fun map(vararg e: Pair<String, ByteArray>): ByteArray =
            e.fold(ctrl(7, e.size)) { acc, (k, v) -> acc + str(k) + v }
        fun array(vararg items: ByteArray): ByteArray = items.fold(ctrl(11, items.size)) { a, b -> a + b }
        fun pointer(offset: Int): ByteArray = byteArrayOf((0x20 or (offset shr 8 and 7)).toByte(), offset.toByte())

        fun bool(v: Boolean): ByteArray = ctrl(14, if (v) 1 else 0)

        /**
         * The data-section shape DB-IP "IP to Country Lite" uses (read from the real file's records on 10 October 2026):
         * continent and country only, with a geoname id, an EU flag and names in several languages. There is no
         * registered_country.
         */
        fun dbipCountry(iso: String, continent: String, geonameId: Long, inEu: Boolean = false): ByteArray = map(
            "continent" to map("code" to str(continent), "geoname_id" to uint(6, geonameId + 1), "names" to map("en" to str(continent))),
            "country" to map(
                "geoname_id" to uint(6, geonameId), "is_in_european_union" to bool(inEu), "iso_code" to str(iso),
                "names" to map("en" to str(iso), "zh-CN" to str("国家"), "ja" to str("国")),
            ),
        )

        /** The data-section shape GeoLite2-Country uses: country, registered_country (and optionally represented). */
        fun country(located: String?, registered: String?): ByteArray = map(
            *listOfNotNull(
                located?.let { "country" to map("iso_code" to str(it)) },
                registered?.let { "registered_country" to map("iso_code" to str(it)) },
            ).toTypedArray(),
        )
    }
}
