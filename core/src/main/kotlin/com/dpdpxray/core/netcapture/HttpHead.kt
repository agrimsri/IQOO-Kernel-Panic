package com.dpdpxray.core.netcapture

/** The first line and headers of a plaintext HTTP/1.x message. Bodies are never read. */
data class HttpHead(
    val isRequest: Boolean,
    val method: String?,
    val target: String?,
    val status: Int?,
    val headers: Map<String, String>,
)

object HttpHeadParser {
    private const val MAX_HEAD = 16 * 1024
    private val METHODS = setOf("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH", "CONNECT", "TRACE")

    /** HTTP/2 over cleartext (prior knowledge) starts with this preface; it can't be read without a HPACK decoder. */
    fun isHttp2Preface(b: ByteArray, length: Int = b.size): Boolean {
        val p = "PRI * HTTP/2.0".toByteArray(Charsets.US_ASCII)
        return length >= p.size && p.indices.all { b[it] == p[it] }
    }

    /** Null unless [b] begins with a complete HTTP/1.x request or response head (blank line included). */
    fun parse(b: ByteArray, length: Int = b.size): HttpHead? {
        val n = minOf(length, b.size, MAX_HEAD)
        if (n < 16) return null
        val end = indexOfBlankLine(b, n) ?: return null
        val text = String(b, 0, end, Charsets.ISO_8859_1)
        val lines = text.split("\r\n")
        val first = lines[0].split(' ', limit = 3)
        val headers = LinkedHashMap<String, String>()
        for (l in lines.drop(1)) {
            val c = l.indexOf(':')
            if (c <= 0) continue
            headers.putIfAbsent(l.substring(0, c).trim().lowercase(), l.substring(c + 1).trim())
        }
        return when {
            first.size == 3 && first[0] in METHODS && first[2].startsWith("HTTP/1.") ->
                HttpHead(true, first[0], first[1], null, headers)
            first.size >= 2 && first[0].startsWith("HTTP/1.") && first[1].toIntOrNull() != null ->
                HttpHead(false, null, null, first[1].toInt(), headers)
            else -> null
        }
    }

    private fun indexOfBlankLine(b: ByteArray, n: Int): Int? {
        for (i in 0..n - 4) {
            if (b[i] == '\r'.code.toByte() && b[i + 1] == '\n'.code.toByte() && b[i + 2] == '\r'.code.toByte() && b[i + 3] == '\n'.code.toByte()) return i
        }
        return null
    }
}
