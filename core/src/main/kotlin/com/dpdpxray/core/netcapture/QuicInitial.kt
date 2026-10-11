package com.dpdpxray.core.netcapture

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A CRYPTO frame's data at its offset in the Initial crypto stream. */
class CryptoChunk(val offset: Long, val data: ByteArray)

/** What one UDP datagram is, as far as reading a QUIC client's server name goes. */
sealed interface QuicRead {
    /** Too short to be a QUIC long-header packet. */
    data object NotQuic : QuicRead

    /** A short-header packet, version negotiation, or a long-header packet that is not an Initial. */
    data object NotInitial : QuicRead

    /** A long-header packet of a QUIC version whose Initial protection this reader does not know (for example v2). */
    data class OtherVersion(val version: Int) : QuicRead

    /** A version 1 Initial whose header or payload did not decrypt: corrupted, truncated, or not really QUIC. */
    data object Unreadable : QuicRead

    class Initial(val dcid: ByteArray, val packetNumber: Long, val crypto: List<CryptoChunk>) : QuicRead
}

/**
 * Reads the CRYPTO frames of a QUIC version 1 client Initial packet (RFC 9000 §17.2.2), so the TLS ClientHello inside can
 * be read by [TlsHello]. Initial packets are protected with keys derived from the packet's own Destination Connection ID
 * and a salt published in RFC 9001 §5.2: any observer can remove that protection (it gives integrity, not secrecy), and
 * doing so is not interception. Nothing after the handshake can be read: that uses keys only the two ends know.
 *
 * Written from RFC 9000/9001; tested against the RFC 9001 Appendix A.2 sample packet. Uses only the JDK's HMAC-SHA256,
 * AES-ECB and AES-GCM (available on Android).
 */
object QuicInitial {
    const val VERSION_1 = 0x00000001
    private val SALT_V1 = hex("38762cf7f55934b34d179ae6a4c80cadccbb7f0a")
    private const val MAX_CID = 20

    class Keys(val key: ByteArray, val iv: ByteArray, val hp: ByteArray)

    /** RFC 9001 §5.2: the client's Initial packet key, IV and header-protection key for [dcid]. */
    fun clientKeys(dcid: ByteArray): Keys {
        val initial = hmac(SALT_V1, dcid) // HKDF-Extract
        val client = expandLabel(initial, "client in", 32)
        return Keys(expandLabel(client, "quic key", 16), expandLabel(client, "quic iv", 12), expandLabel(client, "quic hp", 16))
    }

    fun read(datagram: ByteArray, off: Int = 0, len: Int = datagram.size - off): QuicRead {
        if (off < 0 || len < 5 || off + len > datagram.size) return QuicRead.NotQuic
        val first = datagram[off].toInt() and 0xff
        if (first and 0x80 == 0) return QuicRead.NotInitial
        val version = u32(datagram, off + 1)
        if (version == 0) return QuicRead.NotInitial
        if (version != VERSION_1) return QuicRead.OtherVersion(version)
        if ((first shr 4) and 0x03 != 0) return QuicRead.NotInitial
        return try {
            readV1(datagram, off, off + len)
        } catch (_: java.security.GeneralSecurityException) {
            QuicRead.Unreadable // the authentication tag did not match
        }
    }

    private fun readV1(d: ByteArray, start: Int, end: Int): QuicRead {
        var p = start + 5
        if (p >= end) return QuicRead.Unreadable
        val dcidLen = d[p++].toInt() and 0xff
        if (dcidLen > MAX_CID || p + dcidLen > end) return QuicRead.Unreadable
        val dcid = d.copyOfRange(p, p + dcidLen)
        p += dcidLen
        if (p >= end) return QuicRead.Unreadable
        val scidLen = d[p++].toInt() and 0xff
        if (scidLen > MAX_CID || p + scidLen > end) return QuicRead.Unreadable
        p += scidLen
        val (tokenLength, tokenBytes) = varint(d, p, end) ?: return QuicRead.Unreadable
        p += tokenBytes
        if (tokenLength > end - p) return QuicRead.Unreadable
        p += tokenLength.toInt()
        val (length, lengthBytes) = varint(d, p, end) ?: return QuicRead.Unreadable
        p += lengthBytes
        val pnOffset = p
        if (length < 20 || length > end - pnOffset) return QuicRead.Unreadable
        val packetEnd = pnOffset + length.toInt()
        val sampleAt = pnOffset + 4 // RFC 9001 §5.4.2: sampled as if the packet number were 4 bytes long

        val keys = clientKeys(dcid)
        val mask = Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.hp, "AES"))
            doFinal(d, sampleAt, 16)
        }
        val header = d.copyOfRange(start, pnOffset + 4)
        header[0] = (header[0].toInt() xor (mask[0].toInt() and 0x0f)).toByte()
        val pnLength = (header[0].toInt() and 0x03) + 1
        var pn = 0L
        for (i in 0 until pnLength) {
            val at = pnOffset - start + i
            header[at] = (header[at].toInt() xor mask[1 + i].toInt()).toByte()
            pn = (pn shl 8) or (header[at].toLong() and 0xff)
        }
        val payloadStart = pnOffset + pnLength
        if (packetEnd - payloadStart < 17) return QuicRead.Unreadable
        // RFC 9001 §5.3: nonce = IV xor packet number; the header (with the packet number unmasked) is the AAD.
        val nonce = keys.iv.copyOf()
        for (i in 0 until 8) nonce[11 - i] = (nonce[11 - i].toInt() xor ((pn ushr (8 * i)).toInt() and 0xff)).toByte()
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(header, 0, pnOffset - start + pnLength)
            doFinal(d, payloadStart, packetEnd - payloadStart)
        }
        val crypto = frames(plain) ?: return QuicRead.Unreadable
        return QuicRead.Initial(dcid, pn, crypto)
    }

    /** RFC 9000 §17.2.2: the frames an Initial may carry. Anything else makes the packet invalid (null). */
    private fun frames(b: ByteArray): List<CryptoChunk>? {
        val out = mutableListOf<CryptoChunk>()
        var p = 0
        fun next(): Long? = varint(b, p, b.size)?.let { (v, n) -> p += n; v }
        while (p < b.size) {
            when (val type = next() ?: return null) {
                0x00L, 0x01L -> {} // PADDING, PING
                0x02L, 0x03L -> { // ACK, ACK with ECN counts
                    next() ?: return null; next() ?: return null
                    val ranges = next() ?: return null
                    next() ?: return null
                    if (ranges > 1000) return null
                    repeat(ranges.toInt()) { next() ?: return null; next() ?: return null }
                    if (type == 0x03L) repeat(3) { next() ?: return null }
                }
                0x06L -> { // CRYPTO
                    val offset = next() ?: return null
                    val length = next() ?: return null
                    if (length > b.size - p) return null
                    out += CryptoChunk(offset, b.copyOfRange(p, p + length.toInt()))
                    p += length.toInt()
                }
                0x1cL -> { // CONNECTION_CLOSE
                    next() ?: return null; next() ?: return null
                    val reason = next() ?: return null
                    if (reason > b.size - p) return null
                    p += reason.toInt()
                }
                else -> return null
            }
        }
        return out
    }

    /** RFC 9000 §16 variable-length integer at [at]: (value, bytes used), or null if it does not fit before [end]. */
    internal fun varint(b: ByteArray, at: Int, end: Int): Pair<Long, Int>? {
        if (at >= end) return null
        val first = b[at].toInt() and 0xff
        val n = 1 shl (first shr 6)
        if (at + n > end) return null
        var v = (first and 0x3f).toLong()
        for (i in 1 until n) v = (v shl 8) or (b[at + i].toLong() and 0xff)
        return v to n
    }

    /** TLS 1.3 HKDF-Expand-Label (RFC 8446 §7.1) with an empty context; every length used here fits one SHA-256 block. */
    private fun expandLabel(secret: ByteArray, label: String, length: Int): ByteArray {
        val full = "tls13 $label".toByteArray(Charsets.US_ASCII)
        val info = byteArrayOf((length shr 8).toByte(), length.toByte(), full.size.toByte()) + full + byteArrayOf(0)
        return hmac(secret, info + byteArrayOf(1)).copyOf(length)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    private fun u32(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xff) shl 24) or ((b[i + 1].toInt() and 0xff) shl 16) or ((b[i + 2].toInt() and 0xff) shl 8) or (b[i + 3].toInt() and 0xff)

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}

/** Where reading one QUIC connection's ClientHello stands. */
sealed interface QuicHello {
    data object Pending : QuicHello
    data object NotHello : QuicHello
    data class Read(val info: TlsHelloInfo) : QuicHello
}

/**
 * Collects the CRYPTO data of one QUIC connection's Initial packets, which may split the ClientHello across several
 * datagrams and out of order (browsers do both), until the hello is complete; then reads it with [TlsHello]. Bounded:
 * at most [maxBytes] of crypto stream and [MAX_IDS] connection IDs.
 */
class QuicHelloAssembler(private val maxBytes: Int = 16 * 1024) {
    private val streams = LinkedHashMap<String, java.util.TreeMap<Long, ByteArray>>()

    fun add(initial: QuicRead.Initial): QuicHello {
        val id = initial.dcid.joinToString("") { "%02x".format(it) }
        val chunks = streams.getOrPut(id) {
            if (streams.size >= MAX_IDS) streams.remove(streams.keys.first())
            java.util.TreeMap()
        }
        for (c in initial.crypto) {
            if (c.offset < 0 || c.offset + c.data.size > maxBytes) continue
            val old = chunks[c.offset]
            if (old == null || old.size < c.data.size) chunks[c.offset] = c.data
        }
        val s = contiguous(chunks)
        if (s.size < 4) return QuicHello.Pending
        if (s[0].toInt() != 1) return QuicHello.NotHello // the first handshake message must be a ClientHello
        val needed = 4 + (((s[1].toInt() and 0xff) shl 16) or ((s[2].toInt() and 0xff) shl 8) or (s[3].toInt() and 0xff))
        if (needed > maxBytes) return QuicHello.NotHello
        if (s.size < needed) return QuicHello.Pending
        // TlsHello reads TLS records; QUIC carries the handshake message without one, so frame it as a single record.
        val record = byteArrayOf(0x16, 3, 1, (needed shr 8).toByte(), needed.toByte()) + s.copyOf(needed)
        return TlsHello.parse(record)?.let { QuicHello.Read(it) } ?: QuicHello.NotHello
    }

    /** The bytes from offset 0 up to the first gap. */
    private fun contiguous(chunks: java.util.TreeMap<Long, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var next = 0L
        for ((offset, data) in chunks) {
            if (offset > next) break
            val skip = (next - offset).toInt()
            if (skip < data.size) {
                out.write(data, skip, data.size - skip)
                next = offset + data.size
            }
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_IDS = 4
    }
}
