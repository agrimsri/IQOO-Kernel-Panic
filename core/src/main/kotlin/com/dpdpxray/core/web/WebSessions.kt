package com.dpdpxray.core.web

import com.dpdpxray.core.audit.PhaseAssigner
import com.dpdpxray.core.model.AnswerStatus
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.Category
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.trackers.TrackerCatalog
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** A request as the browser reported it, before TraceVeil classifies it. [accept] and [fetchDest] are request headers. */
data class RawWebRequest(
    val tNanos: Long,
    val url: String,
    val method: String = "GET",
    val mainFrame: Boolean = false,
    val accept: String? = null,
    val fetchDest: String? = null,
    val serviceWorker: Boolean = false,
)

/** One cookie Android's cookie store returned for [host] (name and value as the store gave them). */
data class CookieReading(val host: String, val name: String, val value: String)

/**
 * One reading of the cookie store, for every host asked about. It ran from [startNanos] to [endNanos] (session clock): a
 * cookie in it existed by [endNanos]; a cookie missing from it was set after [startNanos].
 */
data class CookieSnapshot(val at: WebSnapshot, val startNanos: Long, val endNanos: Long, val readings: List<CookieReading>)

/** One reading of the page's own storage keys. */
data class StorageSnapshot(val at: WebSnapshot, val tNanos: Long, val local: List<WebStorageKey>, val session: List<WebStorageKey>)

/** Everything one website plan captured, as the phone recorded it. [WebSessions.build] turns it into an [AuditSession]. */
data class WebPlanCapture(
    val id: String,
    val plan: AuditPlan,
    /** The normalised address the person typed (see [WebUrl]). */
    val url: String,
    val startedAtNanos: Long,
    val endedAtNanos: Long,
    val startedAtEpochMs: Long,
    val requests: List<RawWebRequest> = emptyList(),
    val requestsDropped: Int = 0,
    /** Resource Timing types the page reported, by URL. */
    val resourceTypes: Map<String, WebResourceType> = emptyMap(),
    val cookieSnapshots: List<CookieSnapshot> = emptyList(),
    val storageSnapshots: List<StorageSnapshot> = emptyList(),
    val cookieMeta: List<WebCookieMeta> = emptyList(),
    /** The banner as first found (its tree also travels on the SCREEN_APPEARED consent event). */
    val banner: WebBanner? = null,
    val consentEvents: List<ConsentEvent> = emptyList(),
    val profileCleared: Boolean? = null,
    val agentTaps: Boolean = true,
    val pageLoaded: Boolean? = null,
    val errors: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val deviceModel: String = "",
    val androidVersion: String = "",
    /** Addresses TraceVeil's own resolver returned for each host after the check (for the server map). */
    val addresses: Map<String, List<String>> = emptyMap(),
)

/**
 * Builds a saved [AuditSession] from one website plan: each request becomes a [NetEvent] (so the timeline, the server map,
 * "Which company?" and the consent-phase logic work as they do for apps) and the full detail goes on [AuditSession.web].
 * Cookie values are reduced to a length and a hash prefix here, before anything is saved.
 */
class WebSessions(private val catalog: TrackerCatalog, private val trackers: WebTrackers = WebTrackers.default) {

    fun build(c: WebPlanCapture): AuditSession {
        val typedHost = WebUrl.hostOf(c.url) ?: ""
        val sorted = c.requests.sortedBy { it.tNanos }
        val parsed = sorted.mapNotNull { r -> parse(r)?.let { r to it } }
        val pageHost = parsed.lastOrNull { it.first.mainFrame }?.second?.host ?: typedHost
        val finalUrl = parsed.lastOrNull { it.first.mainFrame }?.first?.url
        val kept = parsed.take(WebLimits.MAX_REQUESTS)
        val phases = PhaseAssigner.assign(kept.map { (r, a) -> NetEvent(r.tNanos, a.host) }, c.consentEvents).map { it.phase }
        val requests = kept.mapIndexed { i, (r, a) ->
            val pageType = c.resourceTypes[r.url]
            val pathEntity = trackers.pathRule(a.host, a.path)?.entity
            WebRequest(
                tNanos = r.tNanos, url = r.url.take(WebLimits.MAX_URL), host = a.host, method = r.method.uppercase(Locale.ROOT).take(10),
                type = if (r.mainFrame) WebResourceType.DOCUMENT else pageType ?: inferType(r.url, false, r.accept, r.fetchDest, r.method),
                typeFromPage = !r.mainFrame && pageType != null,
                mainFrame = r.mainFrame, serviceWorker = r.serviceWorker,
                thirdParty = WebSites.isThirdParty(a.host, pageHost),
                pathEntity = pathEntity,
                scheme = a.scheme, port = a.port, phase = phases[i],
                trackerEntity = pathEntity ?: catalog.classify(a.host).takeIf { it.category.isTracking }?.entity,
            )
        }
        val dropped = c.requestsDropped + (parsed.size - kept.size)
        val events = requests.map { w ->
            val ips = c.addresses[w.host].orEmpty()
            NetEvent(
                tNanos = w.tNanos, hostname = w.host,
                entity = w.pathEntity,
                category = w.pathEntity?.let { trackers.categoryOf(it, catalog) } ?: Category.UNKNOWN,
                answerIps = ips,
                answerStatus = if (ips.isNotEmpty()) AnswerStatus.COMPLETE else null,
                rcode = if (ips.isNotEmpty()) 0 else null,
            )
        }
        val trackerHosts = trackerHosts(requests)
        val web = WebEvidence(
            url = c.url, pageHost = pageHost, finalUrl = finalUrl?.take(WebLimits.MAX_URL),
            requests = requests, requestsDropped = dropped,
            cookies = cookies(c, pageHost, trackerHosts),
            storage = storage(c.storageSnapshots),
            banner = c.banner, profileCleared = c.profileCleared, agentTaps = c.agentTaps, pageLoaded = c.pageLoaded,
            errors = c.errors.distinct().take(10),
        )
        return AuditSession(
            id = c.id, targetPackage = WebAudit.packageFor(typedHost), targetLabel = WebUrl.label(c.url), plan = c.plan,
            startedAtNanos = c.startedAtNanos, endedAtNanos = c.endedAtNanos, startedAtEpochMs = c.startedAtEpochMs,
            netEvents = events, consentEvents = c.consentEvents.sortedBy { it.tNanos },
            screenAudit = c.banner?.let { WebBanners.screenAudit(it) },
            deviceModel = c.deviceModel, androidVersion = c.androidVersion,
            notes = c.notes, freshState = c.profileCleared, web = web,
        )
    }

    /** Hosts the page contacted that the catalogue lists as tracking, or that a documented tracking path decided. */
    private fun trackerHosts(requests: List<WebRequest>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (r in requests) {
            if (r.host in out) continue
            val entity = r.pathEntity ?: catalog.classify(r.host).takeIf { it.category.isTracking }?.entity
            if (entity != null) out[r.host] = entity
        }
        return out
    }

    private fun cookies(c: WebPlanCapture, pageHost: String, trackerHosts: Map<String, String>): List<WebCookie> {
        val snaps = c.cookieSnapshots.sortedBy { it.startNanos }
        if (snaps.isEmpty()) return emptyList()
        data class Key(val site: String, val name: String)
        data class Seen(val hash: String, val length: Int, val hosts: MutableSet<String>)
        val perSnap: List<Map<Key, Seen>> = snaps.map { s ->
            val m = linkedMapOf<Key, Seen>()
            for (r in s.readings) {
                val host = r.host.lowercase(Locale.ROOT)
                val key = Key(WebSites.siteOf(host), r.name.take(WebLimits.MAX_KEY))
                val seen = m.getOrPut(key) { Seen(hash(r.value), r.value.length, linkedSetOf()) }
                seen.hosts += host
            }
            m
        }
        val endIdx = snaps.indexOfLast { it.at == WebSnapshot.END }.takeIf { it >= 0 } ?: snaps.lastIndex
        val pageSite = WebSites.siteOf(pageHost)
        val keys = perSnap.flatMap { it.keys }.distinct()
        return keys.map { k ->
            val present = perSnap.indices.filter { k in perSnap[it] }
            val first = present.first()
            val latest = perSnap[present.last()].getValue(k)
            var rewrittenAfter: Long? = null
            for (i in 1 until present.size) {
                val before = perSnap[present[i - 1]].getValue(k)
                if (perSnap[present[i]].getValue(k).hash != before.hash) { rewrittenAfter = snaps[present[i - 1]].startNanos; break }
            }
            val hosts = present.flatMap { perSnap[it].getValue(k).hosts }.distinct().sorted()
            val thirdParty = k.site != pageSite
            val meta = if (thirdParty) null else c.cookieMeta.firstOrNull { it.name == k.name }
            val hostEntity = hosts.firstNotNullOfOrNull { h -> trackerHosts[h] ?: catalog.classify(h).takeIf { it.category.isTracking }?.entity }
            val nameEntity = if (thirdParty) null else trackers.cookieRule(k.name)?.entity
            WebCookie(
                name = k.name, hosts = hosts, site = k.site, thirdParty = thirdParty,
                valueLength = latest.length, valueHash = latest.hash,
                firstSeenNanos = snaps[first].endNanos, firstSeenAt = snaps[first].at,
                absentSinceNanos = if (first > 0) snaps[first - 1].startNanos else null,
                presentAtEnd = k in perSnap[endIdx],
                rewrittenAfterNanos = rewrittenAfter,
                domain = meta?.domain, expiresEpochMs = meta?.expiresEpochMs,
                trackerEntity = if (thirdParty) hostEntity else nameEntity,
                trackerBasis = when {
                    thirdParty && hostEntity != null -> WebCookieBasis.TRACKER_HOST
                    !thirdParty && nameEntity != null -> WebCookieBasis.DOCUMENTED_NAME
                    else -> null
                },
            )
        }.sortedWith(compareBy<WebCookie> { it.firstSeenNanos }.thenBy { it.site }.thenBy { it.name }).take(WebLimits.MAX_COOKIES)
    }

    private fun storage(snaps: List<StorageSnapshot>): List<WebStorageItem> {
        val out = linkedMapOf<Pair<String, String>, WebStorageItem>()
        for (s in snaps.sortedBy { it.tNanos }) {
            for ((area, keys) in listOf("local" to s.local, "session" to s.session)) for (k in keys) {
                val id = area to k.key
                if (id !in out) out[id] = WebStorageItem(area, k.key.take(WebLimits.MAX_KEY), k.valueLength, s.tNanos, s.at)
            }
        }
        return out.values.take(WebLimits.MAX_STORAGE_KEYS)
    }

    private data class Address(val scheme: String, val host: String, val port: Int, val path: String)

    /** Scheme, host, port and path of an http(s) URL; null for anything else (data:, blob:, unreadable). */
    private fun parse(r: RawWebRequest): Address? {
        val uri = runCatching { URI(r.url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null
        val host = WebUrl.hostOf(r.url) ?: return null
        val port = uri.port.takeIf { it in 1..65535 } ?: if (scheme == "https") 443 else 80
        return Address(scheme, host, port, uri.rawPath ?: "")
    }

    companion object {
        /** First 12 hex characters of SHA-256: enough to tell two readings apart, not the value. */
        fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .take(6).joinToString("") { "%02x".format(it) }

        private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp")
        private val FONT_EXT = setOf("woff", "woff2", "ttf", "otf", "eot")
        private val MEDIA_EXT = setOf("mp4", "webm", "m3u8", "mp3", "m4a", "ogg", "ts", "mpd")

        /**
         * A request's type when the page did not report one: main page; the Sec-Fetch-Dest header when Android passes it;
         * the Accept header (pages, images, stylesheets); the file extension; a POST is a fetch, XHR or beacon. UNKNOWN when
         * nothing says.
         */
        fun inferType(url: String, mainFrame: Boolean, accept: String?, fetchDest: String?, method: String): WebResourceType {
            if (mainFrame) return WebResourceType.DOCUMENT
            when (fetchDest?.lowercase(Locale.ROOT)) {
                "document", "iframe", "frame" -> return WebResourceType.SUBFRAME
                "script", "worker", "sharedworker", "serviceworker" -> return WebResourceType.SCRIPT
                "style" -> return WebResourceType.STYLESHEET
                "image" -> return WebResourceType.IMAGE
                "font" -> return WebResourceType.FONT
                "audio", "video", "track" -> return WebResourceType.MEDIA
                "empty" -> return WebResourceType.XHR_FETCH
            }
            val a = accept?.lowercase(Locale.ROOT).orEmpty()
            if (a.startsWith("text/html")) return WebResourceType.SUBFRAME
            if (a.startsWith("image/")) return WebResourceType.IMAGE
            if (a.startsWith("text/css")) return WebResourceType.STYLESHEET
            val path = runCatching { URI(url).rawPath }.getOrNull().orEmpty().lowercase(Locale.ROOT)
            val ext = path.substringAfterLast('/').substringAfterLast('.', "")
            return when {
                ext == "js" || ext == "mjs" -> WebResourceType.SCRIPT
                ext == "css" -> WebResourceType.STYLESHEET
                ext in IMAGE_EXT -> WebResourceType.IMAGE
                ext in FONT_EXT -> WebResourceType.FONT
                ext in MEDIA_EXT -> WebResourceType.MEDIA
                ext == "json" -> WebResourceType.XHR_FETCH
                method.equals("POST", ignoreCase = true) -> WebResourceType.XHR_FETCH
                else -> WebResourceType.UNKNOWN
            }
        }
    }
}
