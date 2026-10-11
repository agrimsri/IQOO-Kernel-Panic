package com.dpdpxray.core.web

import java.net.IDN
import java.net.URI
import java.util.Locale

/** The result of checking a typed website address. */
sealed interface WebUrlCheck {
    /** [url] is the normalised address TraceVeil opens; [https] is false for plain http (not encrypted). */
    data class Valid(val url: String, val host: String, val https: Boolean) : WebUrlCheck

    /** [reason] is one plain sentence to show under the field. */
    data class Invalid(val reason: String) : WebUrlCheck
}

/**
 * Checks and normalises the address a person types for a website check. Pure text work: nothing is resolved or fetched.
 * A bare name opens over https. Only public http and https sites are accepted: other schemes, user names or passwords in
 * the address, local names and private or loopback addresses are refused.
 */
object WebUrl {
    const val MAX_LENGTH = 2048

    private val EXPLICIT_SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://")
    private val OPAQUE_SCHEME = Regex(
        "^(javascript|data|file|content|intent|about|chrome|chrome-extension|mailto|tel|sms|blob|vbscript|market|geo|view-source):",
        RegexOption.IGNORE_CASE,
    )
    private val LABEL = Regex("^[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?$")
    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    const val ONLY_HTTP = "Only http and https website addresses can be checked."

    fun check(input: String): WebUrlCheck {
        val raw = input.trim()
        if (raw.isEmpty()) return invalid("Type a website address, for example example.com.")
        if (raw.length > MAX_LENGTH) return invalid("That address is too long to check.")
        if (raw.any { it.isWhitespace() }) return invalid("A website address has no spaces.")
        if (OPAQUE_SCHEME.containsMatchIn(raw)) return invalid(ONLY_HTTP)
        val scheme = EXPLICIT_SCHEME.find(raw)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
        val text = when {
            scheme != null && scheme != "http" && scheme != "https" -> return invalid(ONLY_HTTP)
            scheme != null -> raw
            raw.startsWith("//") -> "https:$raw"
            else -> "https://$raw"
        }
        val uri = runCatching { URI(text) }.getOrElse { return invalid("That does not look like a website address.") }
        val https = uri.scheme.lowercase(Locale.ROOT) == "https"
        if (uri.rawUserInfo != null) return invalid("Addresses with a user name or password in them (name@site) are not opened.")
        val host = hostOf(uri) ?: return invalid("Could not read a site name in that address.")
        hostProblem(host)?.let { return invalid(it) }
        val port = if (uri.port in 1..65535) ":${uri.port}" else if (uri.port == -1) "" else return invalid("That port number is not valid.")
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val url = "${if (https) "https" else "http"}://$host$port$path$query"
        if (url.length > MAX_LENGTH) return invalid("That address is too long to check.")
        return WebUrlCheck.Valid(url, host, https)
    }

    /** The address as a report label: the URL without a lone trailing "/". */
    fun label(url: String): String {
        val schemeEnd = url.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: 0
        val firstSlash = url.indexOf('/', schemeEnd)
        return if (firstSlash >= 0 && firstSlash == url.length - 1) url.dropLast(1) else url
    }

    /** The host of an http(s) URL, lower-case and without a trailing dot; null when there is none. */
    fun hostOf(url: String): String? = runCatching { hostOf(URI(url)) }.getOrNull()

    private fun hostOf(uri: URI): String? {
        val direct = uri.host
        val raw = direct ?: uri.rawAuthority?.substringAfterLast('@')?.let { a ->
            if (a.startsWith("[")) a.substringBefore("]") + "]" else a.substringBefore(':')
        } ?: return null
        if (raw.isEmpty()) return null
        if (raw.startsWith("[")) return raw.lowercase(Locale.ROOT)
        val ascii = runCatching { IDN.toASCII(raw, IDN.ALLOW_UNASSIGNED) }.getOrNull() ?: return null
        return ascii.lowercase(Locale.ROOT).trimEnd('.').ifEmpty { null }
    }

    private fun hostProblem(host: String): String? {
        if (host.startsWith("[")) return "Use the site's name rather than an IPv6 address."
        IPV4.matchEntire(host)?.let { m ->
            val parts = m.groupValues.drop(1).map { it.toInt() }
            if (parts.any { it > 255 }) return "That is not a valid address."
            return if (privateIpv4(parts)) PRIVATE else null
        }
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) return PRIVATE
        val labels = host.split('.')
        if (labels.size < 2) return "Type a public website address with a dot in it, such as example.com."
        if (labels.any { it.isEmpty() || !LABEL.matches(it) }) return "That site name is not valid."
        val tld = labels.last()
        if (!(tld.all { it in 'a'..'z' } || tld.startsWith("xn--")) || tld.length < 2) return "That site name is not valid."
        return null
    }

    private const val PRIVATE = "That is a local or private network address. TraceVeil checks public websites."

    private fun privateIpv4(p: List<Int>): Boolean = p[0] == 0 || p[0] == 10 || p[0] == 127 ||
        (p[0] == 169 && p[1] == 254) || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) ||
        (p[0] == 100 && p[1] in 64..127) || p[0] >= 224

    private fun invalid(reason: String) = WebUrlCheck.Invalid(reason)
}
