package com.dpdpxray.core.geo

import java.util.Locale

/**
 * What a GeoLite2-Country record says about one address. [located] is where the database places the address;
 * [registered] is the country the address block is registered in. They can differ, and neither says where traffic went.
 */
data class IpCountry(val located: String?, val registered: String?)

class IpCountryLookup(private val reader: MmdbReader) {
    val buildEpochSeconds: Long? get() = reader.buildEpochSeconds

    /** Null when [ip] is not a literal address, is not in the database, or the record has no valid country code. */
    fun lookup(ip: String): IpCountry? {
        val bytes = IpLiteral.parse(ip) ?: return null
        val record = try { reader.find(bytes) } catch (_: MmdbFormatException) { null } ?: return null
        val located = iso(record["country"])
        val registered = iso(record["registered_country"])
        return if (located == null && registered == null) null else IpCountry(located, registered)
    }

    private fun iso(country: Any?): String? =
        ((country as? Map<*, *>)?.get("iso_code") as? String)?.takeIf { CountryNames.isIso(it) }
}

/** Strict literal parsing, so an address string can never trigger a DNS lookup. */
object IpLiteral {
    fun parse(text: String): ByteArray? = when {
        ':' in text -> v6(text)
        '.' in text -> v4(text)
        else -> null
    }

    private fun v4(t: String): ByteArray? {
        val parts = t.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for ((i, p) in parts.withIndex()) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' } || (p.length > 1 && p[0] == '0')) return null
            val n = p.toInt()
            if (n > 255) return null
            out[i] = n.toByte()
        }
        return out
    }

    private fun v6(t: String): ByteArray? {
        if (!t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) return null
        var text = t
        val dot = text.lastIndexOf('.')
        var tail: ByteArray? = null
        if (dot >= 0) { // embedded IPv4 in the last 32 bits
            val colon = text.lastIndexOf(':')
            tail = v4(text.substring(colon + 1)) ?: return null
            text = text.substring(0, colon + 1) + "0:0"
        }
        val halves = text.split("::")
        if (halves.size > 2) return null
        fun groups(s: String) = if (s.isEmpty()) emptyList() else s.split(':')
        val head = groups(halves[0])
        val rest = if (halves.size == 2) groups(halves[1]) else emptyList()
        val missing = 8 - head.size - rest.size
        if (halves.size == 1 && head.size != 8 || halves.size == 2 && missing < 1) return null
        val all = head + List(if (halves.size == 2) missing else 0) { "0" } + rest
        if (all.size != 8 || all.any { it.isEmpty() || it.length > 4 }) return null
        val out = ByteArray(16)
        all.forEachIndexed { i, g -> val v = g.toInt(16); out[2 * i] = (v shr 8).toByte(); out[2 * i + 1] = v.toByte() }
        tail?.copyInto(out, 12)
        return out
    }
}

/** Country names and flags derived from ISO 3166 codes through the JDK; never invented or guessed. */
object CountryNames {
    /** XK (Kosovo) is user-assigned rather than ISO 3166, but GeoLite2 and the bundled map both use it. */
    private const val KOSOVO = "XK"
    private val codes: Set<String> = Locale.getISOCountries().toSet() + KOSOVO

    fun isIso(code: String) = code in codes

    /** English name for a valid code, otherwise the code itself unchanged. */
    fun name(code: String): String = when {
        code == KOSOVO -> "Kosovo"
        isIso(code) -> Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.ENGLISH)
        else -> code
    }

    fun flag(code: String): String =
        if (isIso(code)) code.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("") else ""
}
