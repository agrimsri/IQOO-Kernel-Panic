package com.dpdpxray.core.netcapture

/** Flag bits of a TCP header. */
object TcpFlags {
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10
}

/** TCP header fields the relay needs. [seq] and [ack] are unsigned 32-bit values held in a Long. */
class TcpHeader(val seq: Long, val ack: Long, val flags: Int, val window: Int, val mss: Int?) {
    fun has(flag: Int) = flags and flag != 0
}

/**
 * One IPv4 TCP or UDP packet read from the tunnel. Payload is [data] from [payloadOffset] for [payloadLength] bytes.
 * IPv6, fragments, and other protocols are not represented (parse returns null).
 */
class IpPacket(
    val protocol: Int,
    val src: ByteArray,
    val dst: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val tcp: TcpHeader?,
    val data: ByteArray,
    val payloadOffset: Int,
    val payloadLength: Int,
) {
    val isTcp get() = protocol == PacketCodec.TCP
    val isUdp get() = protocol == PacketCodec.UDP
    fun payload(): ByteArray = data.copyOfRange(payloadOffset, payloadOffset + payloadLength)
    fun srcText() = PacketCodec.ipText(src)
    fun dstText() = PacketCodec.ipText(dst)
}

/** Strictly bounded IPv4 TCP/UDP reader and builder (with checksums) for the relay. */
object PacketCodec {
    const val TCP = 6
    const val UDP = 17

    fun ipText(a: ByteArray) = a.joinToString(".") { (it.toInt() and 0xff).toString() }

    fun parse(p: ByteArray, length: Int = p.size): IpPacket? {
        if (length < 20 || length > p.size) return null
        if ((p[0].toInt() and 0xf0) shr 4 != 4) return null
        val ihl = (p[0].toInt() and 0x0f) * 4
        val total = u16(p, 2)
        if (ihl < 20 || total < ihl || total > length) return null
        if (u16(p, 6) and 0x3fff != 0) return null // fragment (MF set or offset != 0)
        val proto = u8(p, 9)
        val src = p.copyOfRange(12, 16)
        val dst = p.copyOfRange(16, 20)
        return when (proto) {
            TCP -> {
                if (total - ihl < 20) return null
                val off = (u8(p, ihl + 12) shr 4) * 4
                if (off < 20 || ihl + off > total) return null
                IpPacket(
                    proto, src, dst, u16(p, ihl), u16(p, ihl + 2),
                    TcpHeader(u32(p, ihl + 4), u32(p, ihl + 8), u8(p, ihl + 13) and 0x3f, u16(p, ihl + 14), mss(p, ihl + 20, ihl + off)),
                    p, ihl + off, total - ihl - off,
                )
            }
            UDP -> {
                if (total - ihl < 8) return null
                val ulen = u16(p, ihl + 4)
                if (ulen < 8 || ihl + ulen > total) return null
                IpPacket(proto, src, dst, u16(p, ihl), u16(p, ihl + 2), null, p, ihl + 8, ulen - 8)
            }
            else -> null
        }
    }

    private fun mss(p: ByteArray, from: Int, to: Int): Int? {
        var i = from
        while (i < to) {
            val kind = u8(p, i)
            if (kind == 0) return null
            if (kind == 1) { i++; continue }
            if (i + 1 >= to) return null
            val len = u8(p, i + 1)
            if (len < 2 || i + len > to) return null
            if (kind == 2 && len == 4) return u16(p, i + 2)
            i += len
        }
        return null
    }

    /** A TCP segment from [src]:[sport] to [dst]:[dport]; [mss] adds the MSS option (use on SYN/SYN-ACK only). */
    fun tcp(
        src: ByteArray, sport: Int, dst: ByteArray, dport: Int,
        seq: Long, ack: Long, flags: Int, window: Int = 65535,
        payload: ByteArray = ByteArray(0), payloadOffset: Int = 0, payloadLength: Int = payload.size, mss: Int? = null,
    ): ByteArray {
        val opt = if (mss != null) 4 else 0
        val tcpLen = 20 + opt + payloadLength
        val out = ByteArray(20 + tcpLen)
        ipHeader(out, 20 + tcpLen, TCP, src, dst)
        val t = 20
        put16(out, t, sport); put16(out, t + 2, dport)
        put32(out, t + 4, seq); put32(out, t + 8, ack)
        out[t + 12] = (((20 + opt) / 4) shl 4).toByte()
        out[t + 13] = flags.toByte()
        put16(out, t + 14, window.coerceIn(0, 65535))
        if (mss != null) { out[t + 20] = 2; out[t + 21] = 4; put16(out, t + 22, mss) }
        System.arraycopy(payload, payloadOffset, out, t + 20 + opt, payloadLength)
        put16(out, t + 16, transportChecksum(out, src, dst, TCP, t, tcpLen))
        return out
    }

    fun udp(src: ByteArray, sport: Int, dst: ByteArray, dport: Int, payload: ByteArray, length: Int = payload.size): ByteArray {
        val ulen = 8 + length
        val out = ByteArray(20 + ulen)
        ipHeader(out, 20 + ulen, UDP, src, dst)
        put16(out, 20, sport); put16(out, 22, dport); put16(out, 24, ulen)
        System.arraycopy(payload, 0, out, 28, length)
        val c = transportChecksum(out, src, dst, UDP, 20, ulen)
        put16(out, 26, if (c == 0) 0xffff else c)
        return out
    }

    private fun ipHeader(out: ByteArray, total: Int, proto: Int, src: ByteArray, dst: ByteArray) {
        out[0] = 0x45.toByte()
        put16(out, 2, total)
        out[6] = 0x40 // don't fragment
        out[8] = 64
        out[9] = proto.toByte()
        System.arraycopy(src, 0, out, 12, 4)
        System.arraycopy(dst, 0, out, 16, 4)
        put16(out, 10, checksum(out, 0, 20, 0))
    }

    private fun transportChecksum(p: ByteArray, src: ByteArray, dst: ByteArray, proto: Int, off: Int, len: Int): Int {
        var sum = 0L
        for (i in 0 until 4 step 2) {
            sum += ((src[i].toInt() and 0xff) shl 8) or (src[i + 1].toInt() and 0xff)
            sum += ((dst[i].toInt() and 0xff) shl 8) or (dst[i + 1].toInt() and 0xff)
        }
        sum += proto + len
        return checksum(p, off, len, sum)
    }

    fun checksum(p: ByteArray, off: Int, len: Int, initial: Long): Int {
        var sum = initial
        var i = off
        val end = off + len
        while (i + 1 < end) { sum += ((p[i].toInt() and 0xff) shl 8) or (p[i + 1].toInt() and 0xff); i += 2 }
        if (i < end) sum += (p[i].toInt() and 0xff) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xffff) + (sum shr 16)
        return (sum.inv() and 0xffff).toInt()
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff
    private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun u32(b: ByteArray, i: Int) = (u16(b, i).toLong() shl 16) or u16(b, i + 2).toLong()
    private fun put16(b: ByteArray, i: Int, v: Int) { b[i] = (v shr 8).toByte(); b[i + 1] = v.toByte() }
    private fun put32(b: ByteArray, i: Int, v: Long) { put16(b, i, (v shr 16).toInt() and 0xffff); put16(b, i + 2, v.toInt() and 0xffff) }
}
