package com.dpdpxray.core.netcapture

/** What a TLS ClientHello states in the clear. Nothing is decrypted. */
data class TlsHelloInfo(
    val sni: String?,
    val alpn: List<String>,
    /** The hello carries an Encrypted Client Hello extension, so the real server name may be hidden. */
    val ech: Boolean,
    val offersTls13: Boolean,
)

/** Bounded ClientHello reader. Returns null when the bytes are not a complete, well-formed ClientHello record. */
object TlsHello {
    fun parse(b: ByteArray, length: Int = b.size): TlsHelloInfo? {
        if (length < 6 || length > b.size) return null
        if (u8(b, 0) != 0x16 || u8(b, 1) != 3) return null // handshake record, SSL3/TLS major version
        val recLen = u16(b, 3)
        if (5 + recLen > length) return null
        val end = 5 + recLen
        if (u8(b, 5) != 1) return null // ClientHello
        val hsLen = (u8(b, 6) shl 16) or u16(b, 7)
        if (9 + hsLen > end) return null
        var i = 9 + 2 + 32 // version + random
        if (i + 1 > end) return null
        i += 1 + u8(b, i) // session id
        if (i + 2 > end) return null
        i += 2 + u16(b, i) // cipher suites
        if (i + 1 > end) return null
        i += 1 + u8(b, i) // compression
        if (i == end) return TlsHelloInfo(null, emptyList(), false, false) // no extensions
        if (i + 2 > end) return null
        val extEnd = i + 2 + u16(b, i)
        i += 2
        if (extEnd > end) return null
        var sni: String? = null
        val alpn = mutableListOf<String>()
        var ech = false
        var tls13 = false
        while (i + 4 <= extEnd) {
            val type = u16(b, i)
            val len = u16(b, i + 2)
            val s = i + 4
            if (s + len > extEnd) return null
            when (type) {
                0 -> if (len >= 5 && u8(b, s + 2) == 0) {
                    val nl = u16(b, s + 3)
                    if (s + 5 + nl <= s + len) sni = hostName(String(b, s + 5, nl, Charsets.US_ASCII))
                }
                16 -> if (len >= 2) {
                    var j = s + 2
                    while (j < s + len) {
                        val pl = u8(b, j)
                        if (pl == 0 || j + 1 + pl > s + len) break
                        alpn += String(b, j + 1, pl, Charsets.US_ASCII)
                        j += 1 + pl
                    }
                }
                43 -> if (len >= 3) {
                    var j = s + 1
                    while (j + 1 < s + len) { if (u16(b, j) == 0x0304) tls13 = true; j += 2 }
                }
                0xfe0d -> ech = true
            }
            i = s + len
        }
        return TlsHelloInfo(sni, alpn, ech, tls13)
    }

    private val LABEL = Regex("[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?")

    /**
     * The server name as stored and shown: lower-case, no trailing dot, and only when it is a plausible DNS host name.
     * Anything else (control characters, spaces, empty labels, an IP literal, which SNI must not carry) gives null, so
     * odd bytes from a hello never reach a report or the screen.
     */
    internal fun hostName(raw: String): String? {
        val host = raw.lowercase().removeSuffix(".")
        if (host.isEmpty() || host.length > 253) return null
        val labels = host.split('.')
        if (labels.any { !LABEL.matches(it) } || labels.last().all { it.isDigit() }) return null
        return host
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff
    private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
}
