package com.dpdpxray.core.web

import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.Bounds
import com.dpdpxray.core.model.Phase
import kotlinx.serialization.Serializable

/*
 * Website check evidence. A website check opens the address the person typed in TraceVeil's own built-in browser, so
 * TraceVeil sees every request the page makes from that browser (full http and https URLs, because it owns the browser),
 * the cookies Android's cookie store holds for the hosts the page contacted, the page's own storage keys and its consent
 * banner. Nothing here comes from another app or from decrypting traffic.
 *
 * Saved on AuditSession.web (null for app audits). Saved audits store these by name: add fields with defaults and add
 * enum values at the end; never rename one.
 */

/**
 * What a request was for. Android reports only the URL, method, headers and whether it is the main page, so the type is
 * read, in order, from the page's own Resource Timing entry for the same URL (BROWSER), the main-page flag, the Accept
 * header, or the file extension (all INFERRED). UNKNOWN when none of them says.
 */
@Serializable
enum class WebResourceType(val label: String) {
    DOCUMENT("page"),
    SUBFRAME("frame"),
    SCRIPT("script"),
    STYLESHEET("stylesheet"),
    IMAGE("image"),
    FONT("font"),
    MEDIA("media"),
    XHR_FETCH("fetch/XHR"),
    BEACON("beacon"),
    OTHER("other"),
    UNKNOWN("unknown type"),
}

/** One request the page (or one of its service workers) made from TraceVeil's browser during a plan. */
@Serializable
data class WebRequest(
    /** When TraceVeil saw the request, on the same clock as the session's start, end and consent events. */
    val tNanos: Long,
    /** The full URL as requested, cut at [WebLimits.MAX_URL] characters. Includes any query string. */
    val url: String,
    val host: String,
    val method: String = "GET",
    val type: WebResourceType = WebResourceType.UNKNOWN,
    /** true when [type] came from the page's Resource Timing entry for this URL; false when it was inferred. */
    val typeFromPage: Boolean = false,
    val mainFrame: Boolean = false,
    /** Made by one of the site's service workers rather than by the page itself. */
    val serviceWorker: Boolean = false,
    /** The host belongs to a different site than the page (see [WebSites.siteOf]). */
    val thirdParty: Boolean = false,
    /** Catalogue entity when a documented tracking path decided the match (for example Meta's /tr pixel); null otherwise. */
    val pathEntity: String? = null,
    /** "https" or "http". Field names host, port, scheme, phase and trackerEntity match the app capture's connection records. */
    val scheme: String = "https",
    /** The port in the address, or the scheme's default (443 for https, 80 for http). */
    val port: Int = 443,
    /** Where the request sat relative to the plan's consent choice when the check ran; the rules recompute it the same way. */
    val phase: Phase = Phase.NO_CONSENT_SEEN,
    /** Tracker-catalogue entity of the host or documented path when the check ran; null = not a catalogued tracker. */
    val trackerEntity: String? = null,
)

/** Where in a plan a cookie or storage key was first read. */
@Serializable
enum class WebSnapshot(val label: String) {
    BANNER("when the consent banner was found"),
    BEFORE_CHOICE("just before the consent choice"),
    DURING("during the plan"),
    END("at the end of the plan"),
}

/**
 * A cookie Android's cookie store held for a host the page contacted. The value is never stored: only its length and the
 * first 12 hex characters of its SHA-256 hash, enough to tell two readings apart. Expiry and domain come only from the
 * page's Cookie Store API for first-party cookies the page itself can read; Android's cookie store does not report them.
 */
@Serializable
data class WebCookie(
    val name: String,
    /** Hosts TraceVeil asked the cookie store about that returned this cookie (it would be sent to them). */
    val hosts: List<String>,
    val site: String,
    val thirdParty: Boolean,
    val valueLength: Int,
    val valueHash: String,
    /** When the reading that first found the cookie had finished (session clock): it existed by then. */
    val firstSeenNanos: Long,
    val firstSeenAt: WebSnapshot,
    /**
     * When the last reading that did not find it started: it was set after this moment. null = it was there at the first
     * reading of the plan.
     */
    val absentSinceNanos: Long? = null,
    /** Still present at the end of the plan. false = it was removed (or expired) after it was first read. */
    val presentAtEnd: Boolean = true,
    /** The value changed between two readings: it was written again after this moment (start of the last old-value reading). */
    val rewrittenAfterNanos: Long? = null,
    val domain: String? = null,
    /** Expiry from the page's Cookie Store API (epoch ms); null when not readable. */
    val expiresEpochMs: Long? = null,
    /** Catalogue entity this cookie belongs to; null when it is not a known tracking cookie. */
    val trackerEntity: String? = null,
    /** How [trackerEntity] was decided: by the tracker host it is stored for, or by its documented name. */
    val trackerBasis: WebCookieBasis? = null,
)

@Serializable
enum class WebCookieBasis { TRACKER_HOST, DOCUMENTED_NAME }

/** A key in the page's own localStorage or sessionStorage (top page only). Values are never stored, only their length. */
@Serializable
data class WebStorageItem(
    val area: String,
    val key: String,
    val valueLength: Int,
    val firstSeenNanos: Long,
    val firstSeenAt: WebSnapshot,
)

/** A clickable element of the consent banner, as TraceVeil's in-page script read it. */
@Serializable
data class WebControl(
    val index: Int,
    val text: String,
    /** "accept", "reject", "settings", "save" or "close" when the banner tool's own element decided it; null otherwise. */
    val cmpRole: String? = null,
    /** Shown as a button (a button element or role=button); false for links and plain text. */
    val isButton: Boolean = false,
    val visible: Boolean = true,
    /** In the banner's second layer (preferences), not its first screen. */
    val secondLayer: Boolean = false,
    /** CSS pixels in the page's viewport; null when not measured. */
    val bounds: Bounds? = null,
)

/** A checkbox or switch in the banner (first or second layer). */
@Serializable
data class WebCheckbox(
    val label: String,
    val checked: Boolean,
    val disabled: Boolean = false,
    val visible: Boolean = true,
    val secondLayer: Boolean = false,
)

/** How the banner was found. */
@Serializable
enum class WebBannerDetection {
    /** A known consent tool's own element (OneTrust, Cookiebot, Didomi, ...). */
    CMP_ELEMENT,

    /** A visible element with consent or cookie wording and at least one control. */
    GENERIC_TEXT,

    /** A known consent tool's frame from another site was found; its buttons cannot be read from the page. */
    FRAME_ONLY,
}

/** The consent banner as read from the page. */
@Serializable
data class WebBanner(
    /** Consent tool name ("OneTrust", "Cookiebot", ...); null when found by wording only. */
    val cmp: String? = null,
    val detection: WebBannerDetection,
    /** Host of the consent tool's frame when [detection] is FRAME_ONLY. */
    val frameHost: String? = null,
    val texts: List<String> = emptyList(),
    val controls: List<WebControl> = emptyList(),
    val checkboxes: List<WebCheckbox> = emptyList(),
    val viewportWidth: Int? = null,
    val viewportHeight: Int? = null,
    val devicePixelRatio: Double? = null,
)

/** Everything a website plan recorded beyond the netEvents and consentEvents of its [AuditSession]. */
@Serializable
data class WebEvidence(
    /** The address the person typed, normalised (see [WebUrl]). */
    val url: String,
    /** Host of the last main-page document; first- and third-party are judged against its site. */
    val pageHost: String,
    val finalUrl: String? = null,
    val requests: List<WebRequest> = emptyList(),
    /** Requests not kept because the plan reached [WebLimits.MAX_REQUESTS]. */
    val requestsDropped: Int = 0,
    val cookies: List<WebCookie> = emptyList(),
    val storage: List<WebStorageItem> = emptyList(),
    val banner: WebBanner? = null,
    /** TraceVeil cleared its browser's cookies, storage and cache before this plan. null = not recorded. */
    val profileCleared: Boolean? = null,
    /** TraceVeil was allowed to tap the banner's buttons (otherwise the person tapped them). */
    val agentTaps: Boolean = true,
    /** The main page finished loading at least once. */
    val pageLoaded: Boolean? = null,
    /** Load errors the browser reported, in plain words. */
    val errors: List<String> = emptyList(),
)

/** Size caps so one busy page cannot fill the phone. */
object WebLimits {
    const val MAX_URL = 2048
    const val MAX_REQUESTS = 2500
    const val MAX_COOKIES = 300
    const val MAX_STORAGE_KEYS = 150
    const val MAX_KEY = 100
    const val MAX_TEXTS = 40
    const val MAX_TEXT = 300
    const val MAX_CONTROLS = 40
    const val MAX_CHECKBOXES = 40
}

/** Website-check facts shared by the rules, the screens, the reports and the export. */
object WebAudit {
    /** AuditSession.targetPackage of a website check: "web:" + the host. Never an Android package name. */
    const val PACKAGE_PREFIX = "web:"
    /** AuditMeta.targetKind and the export's target.kind of a website check. */
    const val KIND = "web"

    fun packageFor(host: String) = PACKAGE_PREFIX + host.lowercase()

    /** True when every session is a website plan (an app audit never has [AuditSession.web]). */
    fun isWeb(sessions: List<AuditSession>): Boolean = sessions.isNotEmpty() && sessions.all { it.web != null }

    fun isWebPackage(pkg: String?): Boolean = pkg?.startsWith(PACKAGE_PREFIX) == true

    const val METHOD = "TraceVeil opened the site in its own built-in browser on this phone and recorded every request the page " +
        "made from that browser, with the full address (http and https), the cookies Android's cookie store held for the hosts " +
        "it contacted, the page's own storage keys and its consent banner. Each plan started with the browser's cookies, " +
        "storage and cache cleared. No other app's traffic was read and nothing was decrypted: TraceVeil owns this browser."

    const val REQUEST_CAVEAT = "A request shows the page asked to contact that address; TraceVeil saw the address, not the " +
        "request body or the reply, so it is not proof of what personal data was sent."

    val LIMITS = listOf(
        "TraceVeil's built-in browser is not your own browser: it starts empty each plan, allows third-party cookies, and some " +
            "sites detect it and behave differently.",
        "A consent banner drawn inside a frame from another site (for example Sourcepoint) is noticed but its buttons cannot be " +
            "read or tapped; you can tap it yourself during the check.",
        "WebSocket connections and requests the browser makes for itself are not listed. Service-worker requests are listed " +
            "when Android reports them.",
        "The request type is read from the page's own Resource Timing entry when it has one, otherwise inferred from headers and " +
            "the file extension.",
        "Cookie expiry is known only for first-party cookies the page can read; Android's cookie store does not report it. " +
            "Cookies scoped to a path below the site's root are not read.",
        "Plain http pages are not opened: TraceVeil uses only encrypted connections, so an http address is checked as https.",
        "Pages behind a sign-in are checked only if you sign in during the check. TraceVeil clears its browser before each " +
            "plan and after the check, so the sign-in does not stay.",
    )
}
