package com.dpdpxray.core.netcapture

import kotlinx.serialization.Serializable

/** Kinds of sensitive-looking values found in a URL or header. The values themselves are never kept. */
@Serializable
enum class SensitiveKind(val label: String) {
    EMAIL("email address"),
    PHONE("phone number"),
    DEVICE_ID("device or advertising identifier"),
    IMEI_LIKE("15-digit device number"),
    LOCATION("coordinates"),
    TOKEN("long token or key"),
    CREDENTIAL("credential header"),
}

data class Redacted(val text: String, val found: Set<SensitiveKind>)

/**
 * Removes secrets and personal values from request lines and headers before anything is stored, shown or given to a
 * model. It reports *what kind* of value was seen (presence is evidence) but never the value.
 */
object Redaction {
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val uuid = Regex("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b")
    private val imei = Regex("(?<![0-9])[0-9]{15}(?![0-9])")
    private val phone = Regex("(?<![0-9])(?:\\+91[- ]?)?[6-9][0-9]{9}(?![0-9])")
    private val coords = Regex("(?<![0-9])-?[0-9]{1,3}\\.[0-9]{4,}(?![0-9])")
    private val longToken = Regex("[A-Za-z0-9_\\-]{32,}")

    private val sensitiveHeaders = setOf(
        "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key", "x-auth-token", "x-csrf-token",
        "x-access-token", "api-key",
    )
    private val keptHeaders = setOf(
        "host", "user-agent", "content-type", "content-length", "origin", "referer", "accept", "x-requested-with",
        "content-encoding", "server", "location",
    )

    fun value(s: String): Redacted {
        val found = linkedSetOf<SensitiveKind>()
        var t = s
        fun sub(r: Regex, kind: SensitiveKind, label: String) {
            if (r.containsMatchIn(t)) { found += kind; t = r.replace(t, label) }
        }
        sub(email, SensitiveKind.EMAIL, "[email]")
        sub(uuid, SensitiveKind.DEVICE_ID, "[device-id]")
        sub(imei, SensitiveKind.IMEI_LIKE, "[15-digit-id]")
        sub(phone, SensitiveKind.PHONE, "[phone]")
        sub(coords, SensitiveKind.LOCATION, "[coordinate]")
        sub(longToken, SensitiveKind.TOKEN, "[token]")
        return Redacted(t, found)
    }

    /** A request target (path and query) with every value scrubbed; the path's own words are kept. */
    fun target(target: String): Redacted {
        val q = target.indexOf('?')
        if (q < 0) return value(target)
        val found = linkedSetOf<SensitiveKind>()
        val path = value(target.substring(0, q)).also { found += it.found }
        val params = target.substring(q + 1).split('&').map { kv ->
            val eq = kv.indexOf('=')
            if (eq < 0) kv else {
                val r = value(kv.substring(eq + 1))
                found += r.found
                kv.substring(0, eq) + "=" + r.text
            }
        }
        return Redacted(path.text + "?" + params.joinToString("&"), found)
    }

    /** Only an allow-list of harmless headers is kept (values scrubbed); credential headers become a [SensitiveKind.CREDENTIAL] flag. */
    fun headers(h: Map<String, String>): Pair<Map<String, String>, Set<SensitiveKind>> {
        val found = linkedSetOf<SensitiveKind>()
        val kept = LinkedHashMap<String, String>()
        for ((k, v) in h) {
            when {
                k in sensitiveHeaders -> found += SensitiveKind.CREDENTIAL
                k in keptHeaders -> value(v).let { kept[k] = it.text; found += it.found }
                else -> value(v).let { found += it.found }
            }
        }
        return kept to found
    }
}
