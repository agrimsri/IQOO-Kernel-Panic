package com.dpdpxray.core.netcapture

/** Builders for synthetic wire bytes used across the netcapture tests. */
object NetTestData {
    fun ip(s: String) = s.split('.').map { it.toInt().toByte() }.toByteArray()

    fun clientHello(sni: String?, alpn: List<String> = emptyList(), ech: Boolean = false, tls13: Boolean = true): ByteArray {
        val ext = java.io.ByteArrayOutputStream()
        fun ext(type: Int, body: ByteArray) { ext.write(type shr 8); ext.write(type and 0xff); ext.write(body.size shr 8); ext.write(body.size and 0xff); ext.write(body) }
        if (sni != null) {
            val n = sni.toByteArray()
            val list = byteArrayOf(0, (n.size shr 8).toByte(), n.size.toByte()) + n
            ext(0, byteArrayOf((list.size shr 8).toByte(), list.size.toByte()) + list)
        }
        if (alpn.isNotEmpty()) {
            val l = alpn.fold(ByteArray(0)) { a, p -> a + byteArrayOf(p.length.toByte()) + p.toByteArray() }
            ext(16, byteArrayOf((l.size shr 8).toByte(), l.size.toByte()) + l)
        }
        if (tls13) ext(43, byteArrayOf(2, 3, 4))
        if (ech) ext(0xfe0d, ByteArray(8))
        val extBytes = ext.toByteArray()
        val body = java.io.ByteArrayOutputStream()
        body.write(byteArrayOf(3, 3)); body.write(ByteArray(32)); body.write(0) // version, random, empty session id
        body.write(byteArrayOf(0, 2, 0x13, 0x01)); body.write(byteArrayOf(1, 0)) // one suite, null compression
        body.write(extBytes.size shr 8); body.write(extBytes.size and 0xff); body.write(extBytes)
        val hs = byteArrayOf(1, (body.size() shr 16).toByte(), (body.size() shr 8).toByte(), body.size().toByte()) + body.toByteArray()
        return byteArrayOf(0x16, 3, 1, (hs.size shr 8).toByte(), hs.size.toByte()) + hs
    }

    fun httpGet(host: String, path: String, extra: String = "") =
        "GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: demo\r\n$extra\r\n".toByteArray()
}
