package com.dpdpxray.core.quarantine

import java.io.ByteArrayOutputStream

/** Builds raw IPv4/IPv6 packets byte by byte, so header-parser tests do not depend on the parser. */
object QuarantinePackets {
    val APP_V4 = byteArrayOf(10, 111, 0, 2)
    val APP_V6 = v6("fd00:111::2")

    fun v4(text: String): ByteArray = text.split('.').map { it.toInt().toByte() }.toByteArray()

    /** Minimal IPv6 text-to-bytes for tests (supports one "::"). */
    fun v6(text: String): ByteArray {
        val (head, tail) = if ("::" in text) text.split("::").let { it[0] to it[1] } else text to ""
        val h = head.split(':').filter { it.isNotEmpty() }.map { it.toInt(16) }
        val t = tail.split(':').filter { it.isNotEmpty() }.map { it.toInt(16) }
        val groups = h + List(8 - h.size - t.size) { 0 } + t
        return ByteArray(16).also { b -> groups.forEachIndexed { i, g -> b[2 * i] = (g shr 8).toByte(); b[2 * i + 1] = g.toByte() } }
    }

    fun tcp(srcPort: Int, dstPort: Int, flags: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArrayOutputStream()
        out.w16(srcPort); out.w16(dstPort)
        out.w32(1000); out.w32(0)
        out.write(0x50); out.write(flags) // data offset 5 words
        out.w16(65535); out.w16(0); out.w16(0)
        out.write(payload)
        return out.toByteArray()
    }

    fun udp(srcPort: Int, dstPort: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArrayOutputStream()
        out.w16(srcPort); out.w16(dstPort); out.w16(8 + payload.size); out.w16(0)
        out.write(payload)
        return out.toByteArray()
    }

    fun icmp(type: Int, code: Int = 0): ByteArray = byteArrayOf(type.toByte(), code.toByte(), 0, 0, 0, 1, 0, 1)

    fun ipv4(dst: ByteArray, protocol: Int, transport: ByteArray, fragmentField: Int = 0x4000, options: ByteArray = ByteArray(0)): ByteArray {
        require(options.size % 4 == 0)
        val ihl = 20 + options.size
        val out = ByteArrayOutputStream()
        out.write(0x40 or (ihl / 4)); out.write(0)
        out.w16(ihl + transport.size)
        out.w16(0x1234); out.w16(fragmentField)
        out.write(64); out.write(protocol)
        out.w16(0)
        out.write(APP_V4); out.write(dst)
        out.write(options)
        out.write(transport)
        return out.toByteArray()
    }

    /** [extensions] are (headerType, bytes) pairs placed in order before the transport header. */
    fun ipv6(dst: ByteArray, protocol: Int, transport: ByteArray, extensions: List<Pair<Int, ByteArray>> = emptyList()): ByteArray {
        val chain = extensions.map { it.first } + protocol
        val extBytes = ByteArrayOutputStream()
        extensions.forEachIndexed { i, (_, body) ->
            val copy = body.copyOf()
            copy[0] = chain[i + 1].toByte() // next header
            extBytes.write(copy)
        }
        val payload = extBytes.toByteArray() + transport
        val out = ByteArrayOutputStream()
        out.write(0x60); out.write(0); out.w16(0)
        out.w16(payload.size)
        out.write(chain.first()); out.write(64)
        out.write(APP_V6); out.write(dst)
        out.write(payload)
        return out.toByteArray()
    }

    /** An 8-byte hop-by-hop or destination-options header (next-header byte is filled in by [ipv6]). */
    fun optionsHeader(): ByteArray = byteArrayOf(0, 0, 1, 4, 0, 0, 0, 0)

    /** An IPv6 fragment header with the given offset in 8-byte units. */
    fun fragmentHeader(offsetUnits: Int, more: Boolean): ByteArray {
        val field = (offsetUnits shl 3) or (if (more) 1 else 0)
        return byteArrayOf(0, 0, (field shr 8).toByte(), field.toByte(), 0, 0, 0, 7)
    }

    private fun ByteArrayOutputStream.w16(v: Int) { write((v shr 8) and 0xff); write(v and 0xff) }
    private fun ByteArrayOutputStream.w32(v: Int) { w16(v ushr 16); w16(v and 0xffff) }
}
