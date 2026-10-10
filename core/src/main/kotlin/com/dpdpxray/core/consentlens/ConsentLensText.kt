package com.dpdpxray.core.consentlens

import java.text.Normalizer

/**
 * Input preprocessing for ConsentLens-1, TraceVeil's on-device string-role model.
 *
 * Mirrors `ml/consentlens/consentlens_text.py` exactly (NFKC, lower case, curly apostrophes to ', NUL removed,
 * whitespace runs collapsed, then UTF-8 bytes truncated to [MAX_BYTES] and zero padded). The golden vectors in
 * `core/src/test/resources/consentlens/golden.json` are produced by the Python side and replayed in the unit test.
 */
object ConsentLensText {
    const val MODEL_ID = "ConsentLens-1"
    const val MAX_BYTES = 128

    /** Same explicit set as the Python side, because Kotlin/Java and Python disagree on what `\s` matches. */
    private val SPACES: Set<Char> = buildSet {
        "\t\n\u000b\u000c\r \u0085      　".forEach { add(it) }
        for (c in 0x2000..0x200A) add(c.toChar())
    }

    fun normalize(text: String): String {
        val s = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
        val out = StringBuilder(s.length)
        var pendingSpace = false
        for (raw in s) {
            val ch = if (raw == '‘' || raw == '’') '\'' else raw
            if (ch == '\u0000') continue
            if (ch in SPACES) {
                pendingSpace = out.isNotEmpty()
                continue
            }
            if (pendingSpace) {
                out.append(' ')
                pendingSpace = false
            }
            out.append(ch)
        }
        return out.toString()
    }

    /** Byte ids 1..255 for the text (a multi-byte character may be cut at the limit), 0 = padding. */
    fun encode(text: String): IntArray {
        val bytes = normalize(text).toByteArray(Charsets.UTF_8)
        val ids = IntArray(MAX_BYTES)
        for (i in 0 until minOf(bytes.size, MAX_BYTES)) ids[i] = bytes[i].toInt() and 0xFF
        return ids
    }
}
