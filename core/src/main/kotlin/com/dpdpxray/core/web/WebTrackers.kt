package com.dpdpxray.core.web

import com.dpdpxray.core.model.Category
import com.dpdpxray.core.trackers.TrackerCatalog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

/** A tracking endpoint on a shared host, decided by its URL path (Meta's /tr pixel on facebook.com). */
@Serializable
data class WebPathRule(
    val entity: String,
    /** Hosts the rule applies to, matched by suffix ("facebook.com" covers www.facebook.com). */
    val hosts: List<String>,
    val pathPrefix: String,
    /** Needed only when [entity] is not a tracker-catalogue entity. */
    val category: Category? = null,
    val company: String? = null,
    val source: String,
    val quote: String = "",
)

/** First-party cookie names a vendor documents for its tracking script. Patterns are exact names or use '*' for any text. */
@Serializable
data class WebCookieRule(
    val entity: String,
    val patterns: List<String>,
    val category: Category? = null,
    val company: String? = null,
    val source: String,
    val quote: String = "",
) {
    private val regexes by lazy { patterns.map { p -> Regex(p.split('*').joinToString(".*") { Regex.escape(it) }) } }

    /** Cookie names are case-sensitive, as browsers treat them. */
    fun matches(name: String): Boolean = regexes.any { it.matches(name) }
}

@Serializable
data class WebTrackerData(val pathRules: List<WebPathRule> = emptyList(), val cookies: List<WebCookieRule> = emptyList())

/** What a website check knows beyond the hostname catalogue: documented tracking paths and tracking cookie names. */
class WebTrackers(val data: WebTrackerData) {

    /** The rule for a request URL's host and path, or null. */
    fun pathRule(host: String, path: String): WebPathRule? {
        val h = host.lowercase(Locale.ROOT).trimEnd('.')
        return data.pathRules.firstOrNull { r -> r.hosts.any { s -> h == s || h.endsWith(".$s") } && pathMatches(path, r.pathPrefix) }
    }

    /** "/tr" matches "/tr" and "/tr/..." but not "/trending"; "/ct/" matches "/ct" and everything under it. */
    private fun pathMatches(path: String, prefix: String): Boolean {
        val bare = prefix.trimEnd('/')
        return path == bare || path.startsWith("$bare/")
    }

    /** The rule for a full URL, or null. */
    fun pathRule(url: String): WebPathRule? {
        val host = WebUrl.hostOf(url) ?: return null
        val path = runCatching { java.net.URI(url).rawPath }.getOrNull().orEmpty()
        return pathRule(host, path)
    }

    fun cookieRule(name: String): WebCookieRule? = data.cookies.firstOrNull { it.matches(name) }

    /** The tracking category of an entity named by a rule: the catalogue's when it has the entity, else the rule's own. */
    fun categoryOf(entity: String, catalog: TrackerCatalog): Category? =
        catalog.entryFor(entity)?.category
            ?: data.pathRules.firstOrNull { it.entity == entity }?.category
            ?: data.cookies.firstOrNull { it.entity == entity }?.category

    companion object {
        const val RESOURCE = "/web/web_trackers.json"
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String): WebTrackers = WebTrackers(json.decodeFromString(WebTrackerData.serializer(), text))

        val default: WebTrackers by lazy {
            val text = WebTrackers::class.java.getResourceAsStream(RESOURCE)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("web_trackers.json missing from resources")
            fromJson(text)
        }
    }
}
